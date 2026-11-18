// Cloud fleet #1804: the single login screen's logic. Runs in the Ship build (build.json prepare).

import fs from 'fs';
import path from 'path';

import {
    MESH_SERVER_URL,
    NEW_FIELDS,
    PREVIOUS_FIELDS,
    PUBLIC_SERVER_URL,
    SERVER_URL_OPTIONS,
    SINGLE_SCREEN_FIELDS,
    STEP_ORDER,
    choiceForUrl,
    defaultServerName,
    effectiveServerName,
    initialFlow,
    loginMode,
    resolveServerUrl,
    runConnectAndLogin,
    validateCustomUrl,
    type FlowDeps,
    type FlowInput,
    type FlowMessages,
    type FlowState,
} from './single_login';

const messages: FlowMessages = {
    urlInvalid: (r) => `invalid:${r}`,
    credentialsIncomplete: 'incomplete',
    plainHttp: 'plain-http',
    tlsOk: 'tls-ok',
    mfaRequired: 'mfa',
    connectedOnly: 'connected-only',
};

type C = {DiagnosticId: string};
type L = {IsLicensed: string};
const CONFIG: C = {DiagnosticId: 'diag'};
const LICENSE: L = {IsLicensed: 'false'};

const okDeps = (): FlowDeps<C, L> & {calls: string[]} => {
    const calls: string[] = [];
    return {
        calls,
        reach: jest.fn(async (u: string) => {
            calls.push(`reach:${u}`);
            return {url: u, viaHttp: false};
        }),
        version: jest.fn(async (u: string) => {
            calls.push(`version:${u}`);
            return {config: CONFIG, license: LICENSE, version: '10.5.0'};
        }),
        login: jest.fn(async (u: string, mode: 'token' | 'password') => {
            calls.push(`login:${mode}:${u}`);
            return {ok: true as const};
        }),
    };
};

const filled = (): FlowInput => ({
    choice: 'custom',
    customText: 'https://chat.example.com',
    credentials: {token: '', loginId: 'alice', password: 's3cret', mfaToken: ''},
});

describe('server URL dropdown', () => {
    it('should offer exactly mesh, public and custom, in that order', () => {
        expect(SERVER_URL_OPTIONS.map((o) => o.id)).toEqual(['mesh', 'public', 'custom']);
        expect(SERVER_URL_OPTIONS[0].url).toBe('https://chat.mesh.diegonmarcos.com');
        expect(SERVER_URL_OPTIONS[1].url).toBe('https://chat.diegonmarcos.com');
        expect(SERVER_URL_OPTIONS[2].url).toBeUndefined();
        expect(SERVER_URL_OPTIONS[2].defaultLabel).toBe('Enter your custom URL');
    });

    it('should resolve the presets without looking at the custom text', () => {
        expect(resolveServerUrl('mesh', 'garbage')).toEqual({ok: true, url: MESH_SERVER_URL});
        expect(resolveServerUrl('public', '')).toEqual({ok: true, url: PUBLIC_SERVER_URL});
        expect(resolveServerUrl('custom', 'https://a.example.org/')).toEqual({ok: true, url: 'https://a.example.org'});
    });

    it('should pre-select the dropdown from a handed URL', () => {
        expect(choiceForUrl(undefined)).toEqual({choice: 'mesh', customText: ''});
        expect(choiceForUrl('https://chat.diegonmarcos.com/')).toEqual({choice: 'public', customText: ''});
        expect(choiceForUrl('https://CHAT.mesh.diegonmarcos.com')).toEqual({choice: 'mesh', customText: ''});
        expect(choiceForUrl('http://mattermost.mesh.diegonmarcos.com:8065')).toEqual({choice: 'custom', customText: 'http://mattermost.mesh.diegonmarcos.com:8065'});
    });
});

describe('custom URL validation', () => {
    it.each([
        ['https://chat.example.com', 'https://chat.example.com'],
        ['  HTTPS://Chat.Example.com/  ', 'https://chat.example.com'],
        ['http://mattermost.mesh.diegonmarcos.com:8065', 'http://mattermost.mesh.diegonmarcos.com:8065'],
        ['https://example.com/team/sub', 'https://example.com/team/sub'],
        ['http://10.0.0.6:8065', 'http://10.0.0.6:8065'],
        ['http://localhost:8065', 'http://localhost:8065'],
        ['https://[fd00::1]:8065', 'https://[fd00::1]:8065'],
    ])('should accept %s', (text, url) => {
        expect(validateCustomUrl(text)).toEqual({ok: true, url});
    });

    it.each([
        ['', 'empty'],
        ['   ', 'empty'],
        ['chat.example.com', 'scheme'],
        ['ftp://chat.example.com', 'scheme'],
        ['https://chat example.com', 'spaces'],
        ['https://', 'scheme'],
        ['https://nodot', 'host'],
        ['https://-bad.example.com', 'host'],
        ['https://user@example.com', 'host'],
        ['https://example.com:0', 'host'],
        ['https://example.com:70000', 'host'],
        ['http://300.1.1.1', 'host'],
    ])('should reject %p as %s', (text, reason) => {
        expect(validateCustomUrl(text)).toEqual({ok: false, reason});
    });
});

describe('default server name', () => {
    it('should be "Chat" plus the domain name when blank', () => {
        expect(defaultServerName(MESH_SERVER_URL)).toBe('Chat chat.mesh.diegonmarcos.com');
        expect(defaultServerName(PUBLIC_SERVER_URL)).toBe('Chat chat.diegonmarcos.com');
        expect(defaultServerName('http://Mattermost.example.org:8065/x')).toBe('Chat mattermost.example.org');
        expect(effectiveServerName('   ', PUBLIC_SERVER_URL)).toBe('Chat chat.diegonmarcos.com');
    });

    it('should keep a typed name as is (trimmed)', () => {
        expect(effectiveServerName('  Work  ', PUBLIC_SERVER_URL)).toBe('Work');
    });

    it('should fall back to "Chat" without a usable URL', () => {
        expect(defaultServerName('not a url')).toBe('Chat');
    });
});

describe('login mode', () => {
    it('should prefer the token, then login + password', () => {
        expect(loginMode({token: 't', loginId: 'a', password: 'p', mfaToken: ''})).toBe('token');
        expect(loginMode({token: '', loginId: 'a', password: 'p', mfaToken: ''})).toBe('password');
        expect(loginMode({token: ' ', loginId: '', password: '', mfaToken: ''})).toBe('none');
        expect(loginMode({token: '', loginId: 'a', password: '', mfaToken: ''})).toBe('incomplete');
        expect(loginMode({token: '', loginId: '', password: 'p', mfaToken: ''})).toBe('incomplete');
    });
});

describe('connect then login, in sequence', () => {
    const run = async (input: FlowInput, deps: FlowDeps<C, L>) => {
        const states: FlowState[] = [];
        const frozen = JSON.parse(JSON.stringify(input));
        const outcome = await runConnectAndLogin(Object.freeze(input), deps, messages, (s) => states.push(s));

        // the data rule: whatever happened, every value the user entered is exactly what it was
        expect(input).toEqual(frozen);
        return {outcome, states, last: states[states.length - 1]};
    };

    it('should run url, reach, version, tls, login in that order and log in', async () => {
        const deps = okDeps();
        const {outcome, states, last} = await run(filled(), deps);
        expect(outcome.kind).toBe('logged_in');
        expect(deps.calls).toEqual(['reach:https://chat.example.com', 'version:https://chat.example.com', 'login:password:https://chat.example.com']);
        expect(last.phase).toBe('done');
        expect(STEP_ORDER.map((s) => last.steps[s].status)).toEqual(['ok', 'ok', 'ok', 'ok', 'ok']);
        expect(last.steps.version.detail).toBe('10.5.0');

        // progress per step: each step is seen running before it is seen ok
        const firstRunning = STEP_ORDER.map((s) => states.findIndex((st) => st.steps[s].status === 'running'));
        expect(firstRunning.every((i) => i >= 0)).toBe(true);
        expect([...firstRunning].sort((a, b) => a - b)).toEqual(firstRunning);
    });

    it('should use the token when one is given', async () => {
        const deps = okDeps();
        const input = {...filled(), credentials: {token: 'pat', loginId: 'alice', password: 'x', mfaToken: ''}};
        const {outcome} = await run(input, deps);
        expect(outcome.kind).toBe('logged_in');
        expect(deps.calls[2]).toBe('login:token:https://chat.example.com');
    });

    it('should stop after the server check when no credentials are typed (the login screen follows)', async () => {
        const deps = okDeps();
        const {outcome, last} = await run({...filled(), credentials: {token: '', loginId: '', password: '', mfaToken: ''}}, deps);
        expect(outcome.kind).toBe('connected');
        expect(deps.login).not.toHaveBeenCalled();
        expect(last.steps.login).toEqual({status: 'skipped', detail: 'connected-only'});
    });

    it('should mark TLS as a warning when only plain HTTP answered', async () => {
        const deps = okDeps();
        deps.reach = jest.fn(async () => ({url: 'http://chat.example.com', viaHttp: true}));
        const {last} = await run(filled(), deps);
        expect(last.steps.tls).toEqual({status: 'warn', detail: 'plain-http'});
    });

    it('should fail at the url step on an invalid custom URL, before any network call, keeping the data', async () => {
        const deps = okDeps();
        const {outcome, last} = await run({...filled(), customText: 'chat.example.com'}, deps);
        expect(outcome).toEqual({kind: 'failed', step: 'url', message: 'invalid:scheme'});
        expect(deps.calls).toEqual([]);
        expect(last.failedStep).toBe('url');
        expect(STEP_ORDER.map((s) => last.steps[s].status)).toEqual(['error', 'skipped', 'skipped', 'skipped', 'skipped']);
    });

    it('should fail at the login step on half-typed credentials, before any network call, keeping the data', async () => {
        const deps = okDeps();
        const {outcome, last} = await run({...filled(), credentials: {token: '', loginId: 'alice', password: '', mfaToken: ''}}, deps);
        expect(outcome).toMatchObject({kind: 'failed', step: 'login', message: 'incomplete'});
        expect(deps.calls).toEqual([]);
        expect(last.failedStep).toBe('login');
    });

    it('should fail at reach when the server does not answer, keeping the data', async () => {
        const deps = okDeps();
        deps.reach = jest.fn(async () => ({error: 'unreachable'}));
        const {outcome, last} = await run(filled(), deps);
        expect(outcome).toEqual({kind: 'failed', step: 'reach', message: 'unreachable'});
        expect(deps.version).not.toHaveBeenCalled();
        expect(deps.login).not.toHaveBeenCalled();
        expect(STEP_ORDER.map((s) => last.steps[s].status)).toEqual(['ok', 'error', 'skipped', 'skipped', 'skipped']);
        expect(last.steps.reach.detail).toBe('unreachable');
    });

    it('should fail at version on a bad secret and flag it as a preauth error, keeping the data', async () => {
        const deps = okDeps();
        deps.version = jest.fn(async () => ({error: 'bad secret', isPreauthError: true}));
        const {outcome, last} = await run(filled(), deps);
        expect(outcome).toMatchObject({kind: 'failed', step: 'version', message: 'bad secret', isPreauthError: true});
        expect(deps.login).not.toHaveBeenCalled();
        expect(last.failedStep).toBe('version');
    });

    it('should fail at login on wrong credentials, after a passed server check, keeping the data', async () => {
        const deps = okDeps();
        deps.login = jest.fn(async () => ({error: 'wrong password'}));
        const {outcome, last} = await run(filled(), deps);
        expect(outcome).toMatchObject({kind: 'failed', step: 'login', message: 'wrong password', serverUrl: 'https://chat.example.com'});
        expect(STEP_ORDER.map((s) => last.steps[s].status)).toEqual(['ok', 'ok', 'ok', 'ok', 'error']);
    });

    it('should ask for the MFA code when the server requires it, keeping the data', async () => {
        const deps = okDeps();
        deps.login = jest.fn(async () => ({mfaRequired: true as const}));
        const {outcome, last} = await run(filled(), deps);
        expect(outcome.kind).toBe('mfa_required');
        expect(last.phase).toBe('mfa');
        expect(last.steps.login).toEqual({status: 'warn', detail: 'mfa'});
    });

    it('should stop when cancelled mid-flight, keeping the data', async () => {
        const deps = okDeps();
        let cancel = false;
        deps.reach = jest.fn(async (u: string) => {
            cancel = true;
            return {url: u, viaHttp: false};
        });
        const input = filled();
        const outcome = await runConnectAndLogin(input, deps, messages, () => undefined, () => cancel);
        expect(outcome).toEqual({kind: 'cancelled'});
        expect(deps.version).not.toHaveBeenCalled();
        expect(input).toEqual(filled());
    });

    it('should start every run from a clean flow', () => {
        expect(initialFlow().phase).toBe('idle');
        expect(STEP_ORDER.every((s) => initialFlow().steps[s].status === 'idle')).toBe(true);
    });
});

describe('field parity with the previous server + login screens', () => {
    const read = (f: string) => fs.readFileSync(path.join(__dirname, '..', f), 'utf8');
    const form = read('server/form.tsx');

    it('should map every previous field to a place on the single screen', () => {
        const previous = [...PREVIOUS_FIELDS.server, ...PREVIOUS_FIELDS.login];
        expect(previous.length).toBe(11);
        for (const id of previous) {
            expect(SINGLE_SCREEN_FIELDS[id]).toBeDefined();
            expect(form).toContain(`'${SINGLE_SCREEN_FIELDS[id]}'`);
        }
    });

    it('should render every added field', () => {
        const optionPrefix = 'server_form.server_url.option.';
        for (const id of NEW_FIELDS) {
            if (id.startsWith(optionPrefix)) {
                // the options render from SERVER_URL_OPTIONS through one templated testID
                expect(SERVER_URL_OPTIONS.map((o) => o.id)).toContain(id.slice(optionPrefix.length));
                // eslint-disable-next-line no-template-curly-in-string
                expect(form).toContain('`server_form.server_url.option.${id}`');
            } else {
                expect(form).toContain(`'${id}'`);
            }
        }
    });

    it('should keep the previous login screen with all its fields (nothing removed)', () => {
        const login = read('login/form.tsx');
        for (const id of PREVIOUS_FIELDS.login) {
            expect(login).toContain(id);
        }
        expect(read('mfa/index.tsx')).toContain('login(');
    });
});
