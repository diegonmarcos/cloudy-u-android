package com.diegonmarcos.superapp.decisions.link;

import android.os.ParcelFileDescriptor;

/**
 * The decisions engine's wire (Cloud-Lib-Decisions-Engine.apk). One call = one JSON request and one JSON
 * answer. NOTHING IN HERE CARRIES A CREDENTIAL: the engine holds the OpenRouter token in its own process
 * and learns WHO is asking from the binder, not from the request.
 */
interface IDecisionsEngine {
    /**
     * Run [method] ("decide", "outcome", "status" or "consent"; "image" only through [image]) with the JSON [request]. Answers {"ok":true,...} or
     * {"ok":false,"reason":...}; a reason is "no opinion" and the caller keeps today's behaviour.
     * Never throws across the binder.
     */
    String call(String method, String request);

    /** Method names this engine answers, so a caller can degrade knowingly. */
    String[] methods();

    /**
     * #1799 CONTRACT 2: one image call of the declared image use named in [request] ({use, model, prompt}). The
     * picture is read from [input] and the answer's picture written to [output] (files the caller opened in its own
     * cache, so a picture of megabytes never rides the binder's transaction buffer). Answers {"ok":true,mime,bytes,...}
     * or {"ok":false,"reason":..., "kind"?, "message"?}. Never throws across the binder. An engine below contract 2
     * does not have it: the client reads the contract before it calls.
     */
    String image(String request, in ParcelFileDescriptor input, in ParcelFileDescriptor output);
}
