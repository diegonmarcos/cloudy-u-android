package com.diegonmarcos.superapp.aiengine;

import com.diegonmarcos.superapp.aiengine.IAiEngineCallback;

/**
 * Cloud SuperIntel's AI engines, as one binder: which providers there are, which models each runs, a streamed
 * chat, a cancel and the cost so far. SuperIntel performs every request with its own backends, so an API key
 * never leaves Account / SuperIntel: no client sends or receives one.
 *
 * ROWS, NOT PARCELABLES. Providers and models cross as tab-separated Strings (ProviderInfo / ModelInfo in the
 * module): read by position, a longer row is tolerated. Fields of a provider row: id, label, kind
 * (gateway|openrouter|claude-cli|local), health (up|down|unknown|nokey), default model, detail.
 * Fields of a model row: id, name, group, contextLength, pricePrompt, priceCompletion, note (prices are the
 * raw per-token USD strings of OpenRouter's catalogue, "" = none).
 *
 * NEVER NULL, NEVER AN EXCEPTION. An unreadable catalogue is an empty list, a refused request a sentence.
 *
 * BLOCKING. providers() and models() may touch the network (health probe, catalogue refresh): the platform runs
 * them on a binder pool thread, the CALLER's thread waits. Never call on a main thread. chat() returns at once.
 *
 * REQUEST JSON of chat() (keys the engine reads, unknown ones ignored):
 *   id        caller-chosen request id, the handle of cancel() (required)
 *   provider  a providers() id (required)
 *   mode      the gateway agent mode (X-Agent-Mode) for a gateway / claude-cli provider; "" = none
 *   model     the model id as the backend takes it; "" = the backend's own
 *   messages  OpenAI-style [{role, content}], content a string or the usual content-part array (required)
 *   effort    reasoning effort (low|medium|high|max); "" = none
 *   headers   extra gateway request headers (the optimisation toggles); only the gateway takes them
 *
 * METHOD ORDER IS THE WIRE FORMAT. Transaction codes are assigned by declaration order, so a method may only
 * ever be APPENDED. v1 is these six; contract changes bump version().
 */
interface IAiEngine {

    /** The contract version this server speaks (1). A client newer than the server asks before using an appended method. */
    int version();

    /** Every engine provider SuperIntel can run, one row each, with its live health. */
    String[] providers();

    /** The models of provider [providerId]: OpenRouter's catalogue, the gateway's modes and models. Empty when unknown. */
    String[] models(in String providerId);

    /**
     * Start the chat described by [requestJson] and stream it through [callback]. Answers "" when the request was
     * accepted (the stream follows), else a readable reason (bad request, unknown provider) and NO callback is made.
     */
    String chat(in String requestJson, IAiEngineCallback callback);

    /** Stop the running chat [requestId]; its callback gets onError("cancelled"). A no-op for an unknown id. */
    void cancel(in String requestId);

    /** What the engines have cost so far, one JSON object (periods, totals per provider and model). "{}" when nothing was metered. */
    String usage();
}
