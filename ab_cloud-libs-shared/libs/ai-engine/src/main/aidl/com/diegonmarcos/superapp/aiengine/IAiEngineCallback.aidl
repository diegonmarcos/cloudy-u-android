package com.diegonmarcos.superapp.aiengine;

/**
 * The stream of one IAiEngine.chat() call, delivered by the engine. ONEWAY: the engine never waits for the
 * client. Per request: zero or more onChunk, then exactly one of onDone / onError.
 * METHOD ORDER IS THE WIRE FORMAT: only ever append.
 */
oneway interface IAiEngineCallback {

    /** A piece of the answer, in order. A non-streaming backend delivers its whole answer as one chunk. */
    void onChunk(in String text);

    /** The answer is complete. [usageJson]: {"prompt":n,"completion":n,"cost":x?} as the backend reported it ("" = none). */
    void onDone(in String usageJson);

    /** The request failed or was cancelled; [reason] is a readable sentence ("cancelled" after cancel()). */
    void onError(in String reason);
}
