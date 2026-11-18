package com.diegonmarcos.superapp.decisions.link;

import android.os.ParcelFileDescriptor;

/**
 * The decisions engine's wire (Cloud-Lib-Decisions-Engine.apk). One call = one JSON request and one JSON
 * answer. NOTHING IN HERE CARRIES A CREDENTIAL: the engine holds the OpenRouter key in its own process
 * (#1801: the fleet's one key book, set on the engine's own AI keys screen) and learns WHO is asking from
 * the binder, not from the request.
 */
interface IDecisionsEngine {
    /**
     * Run [method] ("decide", "outcome", "status", "consent" or, #1801, "keys"; "image" only through [image], "relay" only through [relay]) with the JSON [request]. Answers {"ok":true,...} or
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

    /**
     * #1801 CONTRACT 3: one relayed provider call of the declared relay use named in [request] ({use, endpoint}). [input]
     * holds the request body the caller used to POST itself (an OpenAI-shape chat or a Decisions API request), [output]
     * receives the provider's answer body (files the caller opened in its own cache, so a body of megabytes never rides the
     * binder's transaction buffer). The ENGINE adds the key; the body carries none and the answer returns none. [endpoint]
     * is a NAME the use's provider declares, never a URL. Answers {"ok":true,code,bytes,...} (a provider's HTTP error is an
     * answer, with its code) or {"ok":false,"reason":..., "message"?}. Never throws across the binder. APPENDED LAST: an
     * AIDL method's transaction code is its position.
     */
    String relay(String request, in ParcelFileDescriptor input, in ParcelFileDescriptor output);
}
