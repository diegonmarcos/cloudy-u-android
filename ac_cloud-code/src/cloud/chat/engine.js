// cloud-code Chat <-> Cloud SuperIntel's AI engines (#1855 P2, libs:ai-engine). SuperIntel holds the engines and the keys: this tab asks it
// for the provider list, the model list and a streamed chat, and falls back to its own direct gateway road (model.js::buildRequest +
// the CloudChat plugin) only when SuperIntel does not answer. Pure logic with an injected bridge (view.js passes the CloudAiEngine Cordova
// plugin; test/aiengine.mjs passes fakes), no DOM, no imports, so every branch is testable off-device.
//
// A bridge call answers {si, ...}: si=false means SuperIntel is absent / unbound (the caller keeps its own road and shows the install
// hint), si=true with an empty list is an ANSWER ("SuperIntel knows none").

export const SI_TIMEOUT_MS = 4000;
export const NONE = Object.freeze({ si: false, providers: [], models: [] });
export const INSTALL_HINT = "Install Cloud SuperIntel to chat through the fleet's AI engines. Until then this tab talks to the gateway itself.";
export const INSTALL_SHORT = "Install Cloud SuperIntel";

export function bridge() {
	return typeof window !== "undefined" && window.CloudAiEngine ? window.CloudAiEngine : null;
}

// A bridge call that never throws and never waits longer than timeoutMs: SuperIntel absent / slow = NONE.
async function ask(b, method, args, timeoutMs) {
	if (!b || typeof b[method] !== "function") return NONE;
	try {
		const got = await Promise.race([
			Promise.resolve().then(() => b[method](...args)),
			new Promise((resolve) => setTimeout(() => resolve(null), timeoutMs)),
		]);
		return got && got.si === true ? got : NONE;
	} catch {
		return NONE;
	}
}

export async function engineProviders(b, timeoutMs = SI_TIMEOUT_MS) {
	const got = await ask(b, "providers", [], timeoutMs);
	return { si: got.si === true, providers: Array.isArray(got.providers) ? got.providers.filter((p) => p && p.id) : [] };
}

export async function engineModels(b, providerId, timeoutMs = SI_TIMEOUT_MS) {
	const got = await ask(b, "models", [providerId], timeoutMs);
	return { si: got.si === true, models: Array.isArray(got.models) ? got.models.filter((m) => m && m.id) : [] };
}

export async function engineUsage(b, timeoutMs = SI_TIMEOUT_MS) {
	const got = await ask(b, "usage", [], timeoutMs);
	return { si: got.si === true, usage: got.usage && typeof got.usage === "object" ? got.usage : {} };
}

/** The engine provider an agent runs on: OpenRouter for the direct backend, claude-cli for the gateway's claude mode, else the gateway. */
export function providerFor(agent) {
	if (!agent) return null;
	if (agent.via === "direct") return "openrouter";
	if (agent.via !== "gateway") return null;
	return agent.mode === "claude-cli" ? "claude-cli" : "gateway";
}

/** The provider row for an agent, or null. */
export function providerRow(snapshot, agent) {
	const id = providerFor(agent);
	return (snapshot?.providers || []).find((p) => p.id === id) || null;
}

/**
 * Whether the chat of [agent] goes through SuperIntel: SuperIntel answered, runs that provider, and the provider is not known to be unable
 * (down = the gateway is unreachable from SuperIntel; nokey = Cloud Account holds no OpenRouter key). Otherwise the direct road stays in force.
 */
export function engineUsable(snapshot, agent) {
	if (!snapshot || snapshot.si !== true) return false;
	const p = providerRow(snapshot, agent);
	return !!p && p.health !== "down" && p.health !== "nokey";
}

/** The state line of the engine for the picker's footer. undefined = not asked yet. */
export function engineNote(snapshot) {
	if (snapshot === undefined) return "";
	if (!snapshot || snapshot.si !== true) return INSTALL_HINT;
	if (!snapshot.providers.length) return "Cloud SuperIntel answered but lists no provider.";
	return `Cloud SuperIntel engines: ${snapshot.providers.map((p) => `${p.label || p.id} (${p.health || "unknown"})`).join(" · ")}`;
}

/** The note under the model name on the empty chat: which road the next send takes. */
export function roadLabel(snapshot, agent) {
	if (snapshot === undefined) return "";
	if (!snapshot || snapshot.si !== true) return `direct gateway · ${INSTALL_SHORT}`;
	return engineUsable(snapshot, agent) ? "via Cloud SuperIntel" : "direct gateway (SuperIntel cannot run this agent now)";
}

/**
 * The IAiEngine request JSON for a send, from the request model.js::buildRequest built for the direct road ({headers, body}): the
 * messages, the model, the effort and the toggles are the SAME ones, so an agent, an attachment, an effort level, a permission mode and
 * the More toggles behave alike on both roads. No key is part of it (the direct OpenRouter road's token is added by the plugin, and the
 * engine road's by SuperIntel). The agent's mode travels as `mode`, not as the x-agent-mode header.
 */
export function engineRequest(id, agent, req) {
	const body = JSON.parse(req.body);
	const headers = {};
	for (const [k, v] of Object.entries(req.headers || {})) if (k.toLowerCase() !== "x-agent-mode") headers[k] = v;
	return JSON.stringify({
		id,
		provider: providerFor(agent),
		mode: agent.via === "gateway" ? agent.mode || "" : "",
		model: body.model || "",
		messages: body.messages,
		effort: body.reasoning?.effort || "",
		headers,
	});
}

/** $/1M tokens from a per-token price string ("0.000003" -> "$3"); "free" for 0, "-" for none (the Model page's words). */
export function perMillion(perToken) {
	const v = perToken === "" || perToken == null ? NaN : Number(perToken);
	if (!Number.isFinite(v)) return "-";
	if (v < 0) return "var";
	if (v === 0) return "free";
	return `$${Number((v * 1e6).toPrecision(6))}`;
}

export function tokens(n) {
	const v = Number(n);
	if (!Number.isFinite(v) || v <= 0) return "-";
	if (v >= 1e6) return `${(v / 1e6).toFixed(1).replace(/\.0$/, "")}M`;
	if (v >= 1000) return `${Math.floor(v / 1000)}K`;
	return String(v);
}

/** The Model page's engine section for one agent: the rows SuperIntel lists for its provider, in the page's words. null = no section. */
export function modelSection(snapshot, agent, models) {
	const p = providerRow(snapshot, agent);
	if (!engineUsable(snapshot, agent) || !p || !models?.length) return null;
	return {
		id: `engine-${p.id}`,
		label: `Cloud SuperIntel · ${p.label || p.id}`,
		note: `Listed by Cloud SuperIntel (${p.kind || p.id}, ${p.health || "unknown"}).`,
		rows: models.map((m) => ({
			id: m.id, name: m.name || m.id, group: m.group || "", context: tokens(m.contextLength),
			input: perMillion(m.pricePrompt), output: perMillion(m.priceCompletion), note: m.note || "",
		})),
	};
}

/** Usage lines for the More > Usage page: what SuperIntel metered (only what the backends reported). */
export function usageLines(usage) {
	if (!usage || !usage.all || !usage.all.requests) return [];
	const line = (label, t) => ({ label, value: `${t.requests} requests · ${t.prompt || 0} in · ${t.completion || 0} out${typeof t.cost === "number" ? ` · $${t.cost.toFixed(4)}` : ""}` });
	return [line("Cloud SuperIntel today", usage.today || { requests: 0 }), line("Cloud SuperIntel this month", usage.month || { requests: 0 }), line("Cloud SuperIntel all metered", usage.all)];
}
