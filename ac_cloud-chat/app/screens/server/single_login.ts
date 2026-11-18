// Cloud fleet #1804: the ONE login screen's pure logic (no React Native imports, so jest runs it bare).
//  - the server URL dropdown (mesh, public, custom) and the custom-URL validation;
//  - the default server name ("Chat <host>") when the name is left blank;
//  - the sequential connect-then-login state machine with a status per step.
// The runner only ever RETURNS flow state: it never receives the form's setters, so no failure path
// can clear a value the user typed (URL choice, name, secret, token, login, password, MFA code).

export const MESH_SERVER_URL = 'https://chat.mesh.diegonmarcos.com';
export const MESH_ALT_SERVER_URL = 'https://cloud-chat.mesh.diegonmarcos.com';
export const PUBLIC_SERVER_URL = 'https://chat.diegonmarcos.com';

export type UrlChoice = 'mesh' | 'mesh_alt' | 'public' | 'custom';

export type UrlOption = {
    id: UrlChoice;
    url?: string;
    labelId: string;
    defaultLabel: string;
};

export const SERVER_URL_OPTIONS: readonly UrlOption[] = [
    {id: 'mesh', url: MESH_SERVER_URL, labelId: 'mobile.server_url.option.mesh', defaultLabel: 'Private (mesh)'},
    {id: 'mesh_alt', url: MESH_ALT_SERVER_URL, labelId: 'mobile.server_url.option.mesh_alt', defaultLabel: 'Private (mesh, cloud-chat)'},
    {id: 'public', url: PUBLIC_SERVER_URL, labelId: 'mobile.server_url.option.public', defaultLabel: 'Public'},
    {id: 'custom', labelId: 'mobile.server_url.option.custom', defaultLabel: 'Enter your custom URL'},
];

export type UrlInvalidReason = 'empty' | 'spaces' | 'scheme' | 'host';

export type UrlCheck = {ok: true; url: string} | {ok: false; reason: UrlInvalidReason};

const HOST_LABEL = /^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$/i;

function hostLooksValid(host: string): boolean {
    if (!host || host.length > 253) {
        return false;
    }
    if ((/^\[[0-9a-f:.]+\]$/i).test(host)) {
        return true; // bracketed IPv6 literal
    }
    if ((/^\d{1,3}(\.\d{1,3}){3}$/).test(host)) {
        return host.split('.').every((o) => Number(o) <= 255);
    }
    const labels = host.split('.');
    return labels.every((l) => HOST_LABEL.test(l)) && (labels.length > 1 || host.toLowerCase() === 'localhost');
}

/** The custom URL free text: trimmed, http(s) only, a real host, optional port and path. */
export function validateCustomUrl(text: string): UrlCheck {
    const raw = (text ?? '').trim();
    if (!raw) {
        return {ok: false, reason: 'empty'};
    }
    if ((/\s/).test(raw)) {
        return {ok: false, reason: 'spaces'};
    }
    const m = (/^(https?):\/\/([^/?#]+)(.*)$/i).exec(raw);
    if (!m) {
        return {ok: false, reason: 'scheme'};
    }
    const authority = m[2];
    if (authority.includes('@')) {
        return {ok: false, reason: 'host'};
    }
    const hp = (/^(\[[^\]]+\]|[^:]+)(?::(\d{1,5}))?$/).exec(authority);
    if (!hp || !hostLooksValid(hp[1]) || (hp[2] !== undefined && (Number(hp[2]) < 1 || Number(hp[2]) > 65535))) {
        return {ok: false, reason: 'host'};
    }
    const path = m[3].replace(/\/+$/, '');
    return {ok: true, url: `${m[1].toLowerCase()}://${authority.toLowerCase()}${path}`};
}

/** The URL the server check runs against, for the dropdown choice (and the custom text when chosen). */
export function resolveServerUrl(choice: UrlChoice, customText: string): UrlCheck {
    const preset = SERVER_URL_OPTIONS.find((o) => o.id === choice && o.url);
    if (preset?.url) {
        return {ok: true, url: preset.url};
    }
    return validateCustomUrl(customText);
}

/** Pre-select the dropdown from a URL a deep link, EMM or Fleet Setup handed us. */
export function choiceForUrl(url?: string): {choice: UrlChoice; customText: string} {
    const trimmed = (url ?? '').trim();
    if (!trimmed) {
        return {choice: 'mesh', customText: ''};
    }
    const norm = trimmed.replace(/\/+$/, '').toLowerCase();
    const preset = SERVER_URL_OPTIONS.find((o) => o.url && o.url === norm);
    if (preset) {
        return {choice: preset.id, customText: ''};
    }
    return {choice: 'custom', customText: trimmed};
}

export function hostOf(url: string): string {
    const m = (/^[a-z]+:\/\/([^/?#]+)/i).exec((url ?? '').trim());
    if (!m) {
        return '';
    }
    return m[1].replace(/:\d+$/, '').toLowerCase();
}

/** Blank name → "Chat" plus the server's domain name (its host), e.g. "Chat chat.mesh.diegonmarcos.com". */
export function defaultServerName(url: string): string {
    const host = hostOf(url);
    return host ? `Chat ${host}` : 'Chat';
}

export function effectiveServerName(name: string, url: string): string {
    const typed = (name ?? '').trim();
    return typed || defaultServerName(url);
}

// ── credentials ────────────────────────────────────────────────────────────────────────────────

export type Credentials = {
    token: string;
    loginId: string;
    password: string;
    mfaToken: string;
};

/** token wins; login + password otherwise; nothing typed = connect only (the existing login screen follows). */
export type LoginMode = 'token' | 'password' | 'none' | 'incomplete';

export function loginMode(c: Credentials): LoginMode {
    if (c.token.trim()) {
        return 'token';
    }
    const hasId = Boolean(c.loginId.trim());
    const hasPw = Boolean(c.password);
    if (hasId && hasPw) {
        return 'password';
    }
    if (!hasId && !hasPw) {
        return 'none';
    }
    return 'incomplete';
}

// ── the sequential state machine ───────────────────────────────────────────────────────────────

export const STEP_ORDER = ['url', 'dns', 'tls', 'reach', 'ping', 'version', 'login'] as const;
export type StepId = typeof STEP_ORDER[number];
export type StepStatus = 'idle' | 'running' | 'ok' | 'warn' | 'error' | 'skipped';
export type StepState = {status: StepStatus; detail?: string};
export type FlowPhase = 'idle' | 'running' | 'done' | 'failed' | 'mfa' | 'cancelled';

export type FlowState = {
    phase: FlowPhase;
    steps: Record<StepId, StepState>;
    failedStep?: StepId;
    alternative?: UrlChoice;
};

export type FlowEvent =
    | {type: 'reset'}
    | {type: 'start'}
    | {type: 'step'; step: StepId; status: StepStatus; detail?: string}
    | {type: 'finish'; phase: Exclude<FlowPhase, 'idle' | 'running'>; failedStep?: StepId; alternative?: UrlChoice};

export function initialFlow(): FlowState {
    const steps = {} as Record<StepId, StepState>;
    for (const s of STEP_ORDER) {
        steps[s] = {status: 'idle'};
    }
    return {phase: 'idle', steps};
}

export function flowReducer(state: FlowState, ev: FlowEvent): FlowState {
    switch (ev.type) {
        case 'reset':
            return initialFlow();
        case 'start':
            return {...initialFlow(), phase: 'running'};
        case 'step':
            return {...state, steps: {...state.steps, [ev.step]: {status: ev.status, detail: ev.detail}}};
        case 'finish':
            return {...state, phase: ev.phase, failedStep: ev.failedStep, alternative: ev.alternative};
        default:
            return state;
    }
}

/**
 * The mesh entry that already answers today (orchestrator, #1804): when another entry fails at DNS, TLS
 * trust or ping, the screen offers it, keeping every value. Not offered for a failure of that entry itself.
 */
export const FALLBACK_CHOICE: UrlChoice = 'mesh_alt';
const OFFER_FALLBACK_AT: readonly StepId[] = ['dns', 'tls', 'ping'];

export function alternativeFor(choice: UrlChoice, step: StepId): UrlChoice | undefined {
    return choice !== FALLBACK_CHOICE && OFFER_FALLBACK_AT.includes(step) ? FALLBACK_CHOICE : undefined;
}

export type FlowMessages = {
    urlInvalid: (reason: UrlInvalidReason) => string;
    credentialsIncomplete: string;
    plainHttp: string;
    mfaRequired: string;
    connectedOnly: string;
};

/** A probe step: passed (with what it saw), failed (inline error), or not measurable on this platform. */
export type ProbeResult = {ok: true; detail?: string} | {error: string} | {skipped: string};
export type ReachResult = {url: string} | {error: string};
export type PingResult = {ok: true; detail?: string} | {error: string; isPreauthError?: boolean};
export type VersionResult<C, L> = {config: C; license: L; version?: string} | {error: string};
export type LoginResult = {ok: true} | {mfaRequired: true} | {error: string};

export type FlowDeps<C, L> = {
    dns: (host: string) => Promise<ProbeResult>;
    tls: (host: string, port: number) => Promise<ProbeResult>;
    reach: (url: string) => Promise<ReachResult>;
    ping: (url: string) => Promise<PingResult>;
    version: (url: string) => Promise<VersionResult<C, L>>;
    login: (url: string, mode: 'token' | 'password', creds: Credentials, config: C, license: L) => Promise<LoginResult>;
};

export type FlowInput = {
    choice: UrlChoice;
    customText: string;
    credentials: Credentials;
};

export type FlowOutcome<C, L> =
    | {kind: 'logged_in'; serverUrl: string; config: C; license: L}
    | {kind: 'connected'; serverUrl: string; config: C; license: L}
    | {kind: 'mfa_required'; serverUrl: string; config: C; license: L}
    | {kind: 'failed'; step: StepId; message: string; isPreauthError?: boolean; alternative?: UrlChoice; serverUrl?: string; config?: C; license?: L}
    | {kind: 'cancelled'};

export function portOf(url: string): number {
    const m = (/^([a-z]+):\/\/(\[[^\]]+\]|[^/:?#]+)(?::(\d+))?/i).exec(url);
    if (m?.[3]) {
        return Number(m[3]);
    }
    return m?.[1].toLowerCase() === 'http' ? 80 : 443;
}

/**
 * Connect, then log in, one step after the other: URL, DNS, TLS trust, reachability, ping, version, login.
 * `emit` gets every state change (progress per step). Read-only on `input`: the caller's form values are
 * never touched, whatever fails.
 */
export async function runConnectAndLogin<C, L>(
    input: Readonly<FlowInput>,
    deps: FlowDeps<C, L>,
    messages: FlowMessages,
    emit: (s: FlowState) => void,
    isCancelled: () => boolean = () => false,
): Promise<FlowOutcome<C, L>> {
    let state = flowReducer(initialFlow(), {type: 'start'});
    const put = (ev: FlowEvent) => {
        state = flowReducer(state, ev);
        emit(state);
    };
    type Failed = Extract<FlowOutcome<C, L>, {kind: 'failed'}>;
    const fail = (step: StepId, message: string, extra: Partial<Failed> = {}): FlowOutcome<C, L> => {
        const alternative = alternativeFor(input.choice, step);
        put({type: 'step', step, status: 'error', detail: message});
        for (const s of STEP_ORDER.slice(STEP_ORDER.indexOf(step) + 1)) {
            put({type: 'step', step: s, status: 'skipped'});
        }
        put({type: 'finish', phase: 'failed', failedStep: step, alternative});
        return {kind: 'failed', step, message, ...(alternative ? {alternative} : {}), ...extra};
    };
    const cancelled = (): FlowOutcome<C, L> => {
        put({type: 'finish', phase: 'cancelled'});
        return {kind: 'cancelled'};
    };
    const probe = async (step: StepId, run: () => Promise<ProbeResult>): Promise<string | undefined> => {
        put({type: 'step', step, status: 'running'});
        const r = await run();
        if ('error' in r) {
            return r.error;
        }
        put({type: 'step', step, status: 'ok' in r ? 'ok' : 'skipped', detail: 'ok' in r ? r.detail : r.skipped});
        return undefined;
    };
    emit(state);

    // 1. URL (dropdown choice or validated custom text)
    put({type: 'step', step: 'url', status: 'running'});
    const resolved = resolveServerUrl(input.choice, input.customText);
    if (!resolved.ok) {
        return fail('url', messages.urlInvalid(resolved.reason));
    }
    put({type: 'step', step: 'url', status: 'ok', detail: resolved.url});
    const mode = loginMode(input.credentials);
    if (mode === 'incomplete') {
        // caught before any network call, so a half-typed login never costs a round trip
        for (const s of STEP_ORDER.slice(1, -1)) {
            put({type: 'step', step: s, status: 'skipped'});
        }
        return fail('login', messages.credentialsIncomplete);
    }
    const host = hostOf(resolved.url);
    const https = (/^https:/i).test(resolved.url);

    // 2. DNS
    const dnsErr = await probe('dns', () => deps.dns(host));
    if (isCancelled()) {
        return cancelled();
    }
    if (dnsErr) {
        return fail('dns', dnsErr);
    }

    // 3. TLS trust (issuer named, trusted or not); a plain-http custom URL has none, said so as a warning
    if (https) {
        const tlsErr = await probe('tls', () => deps.tls(host, portOf(resolved.url)));
        if (isCancelled()) {
            return cancelled();
        }
        if (tlsErr) {
            return fail('tls', tlsErr);
        }
    } else {
        put({type: 'step', step: 'tls', status: 'warn', detail: messages.plainHttp});
    }

    // 4. reachability (HEAD, redirects followed)
    put({type: 'step', step: 'reach', status: 'running'});
    const reach = await deps.reach(resolved.url);
    if (isCancelled()) {
        return cancelled();
    }
    if ('error' in reach) {
        return fail('reach', reach.error);
    }
    put({type: 'step', step: 'reach', status: 'ok', detail: reach.url});

    // 5. ping (GET /api/v4/system/ping, the secret header when one is set)
    put({type: 'step', step: 'ping', status: 'running'});
    const ping = await deps.ping(reach.url);
    if (isCancelled()) {
        return cancelled();
    }
    if ('error' in ping) {
        return fail('ping', ping.error, {isPreauthError: ping.isPreauthError, serverUrl: reach.url});
    }
    put({type: 'step', step: 'ping', status: 'ok', detail: ping.detail});

    // 6. version (client config: DiagnosticId, Version, licence, device security checks)
    put({type: 'step', step: 'version', status: 'running'});
    const ver = await deps.version(reach.url);
    if (isCancelled()) {
        return cancelled();
    }
    if ('error' in ver) {
        return fail('version', ver.error, {serverUrl: reach.url});
    }
    put({type: 'step', step: 'version', status: 'ok', detail: ver.version});

    // 7. login
    if (mode === 'none') {
        put({type: 'step', step: 'login', status: 'skipped', detail: messages.connectedOnly});
        put({type: 'finish', phase: 'done'});
        return {kind: 'connected', serverUrl: reach.url, config: ver.config, license: ver.license};
    }
    put({type: 'step', step: 'login', status: 'running'});
    const res = await deps.login(reach.url, mode, input.credentials, ver.config, ver.license);
    if (isCancelled()) {
        return cancelled();
    }
    if ('mfaRequired' in res) {
        put({type: 'step', step: 'login', status: 'warn', detail: messages.mfaRequired});
        put({type: 'finish', phase: 'mfa', failedStep: 'login'});
        return {kind: 'mfa_required', serverUrl: reach.url, config: ver.config, license: ver.license};
    }
    if ('error' in res) {
        return fail('login', res.error, {serverUrl: reach.url, config: ver.config, license: ver.license});
    }
    put({type: 'step', step: 'login', status: 'ok'});
    put({type: 'finish', phase: 'done'});
    return {kind: 'logged_in', serverUrl: reach.url, config: ver.config, license: ver.license};
}

// ── field parity (#1804, owner rule: a reorg loses no field) ───────────────────────────────────

/** Every input and action the previous two screens (server form + login form) offered, by testID. */
export const PREVIOUS_FIELDS = {
    server: [
        'server_form.server_url.input',
        'server_form.server_display_name.input',
        'server_form.display_help',
        'server_form.advanced_options.toggle',
        'server_form.preauth_secret.input',
        'server_form.preauth_secret_help',
        'server_form.connect.button',
    ],
    login: [
        'login_form.username.input',
        'login_form.password.input',
        'login_form.forgot_password.button',
        'login_form.signin.button',
    ],
} as const;

/** Where each previous field lives on the single screen (the login screen also stays, unchanged). */
export const SINGLE_SCREEN_FIELDS: Record<string, string> = {
    'server_form.server_url.input': 'server_form.server_url.input',
    'server_form.server_display_name.input': 'server_form.server_display_name.input',
    'server_form.display_help': 'server_form.display_help',
    'server_form.advanced_options.toggle': 'server_form.advanced_options.toggle',
    'server_form.preauth_secret.input': 'server_form.preauth_secret.input',
    'server_form.preauth_secret_help': 'server_form.preauth_secret_help',
    'server_form.connect.button': 'server_form.connect.button',
    'login_form.username.input': 'server_form.login_id.input',
    'login_form.password.input': 'server_form.password.input',
    'login_form.forgot_password.button': 'server_form.forgot_password.button',
    'login_form.signin.button': 'server_form.connect.button',
};

/** Added by #1804 (on top of everything above). */
export const NEW_FIELDS = [
    'server_form.server_url.dropdown',
    'server_form.server_url.option.mesh',
    'server_form.server_url.option.mesh_alt',
    'server_form.server_url.option.public',
    'server_form.server_url.option.custom',
    'server_form.token.input',
    'server_form.mfa.input',
    'server_form.progress',
    'server_form.try_alternative.button',
] as const;
