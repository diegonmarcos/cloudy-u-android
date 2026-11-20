package com.diegonmarcos.cloudlib.mailcore

/**
 * How the Rust sync engine reaches the JMAP server (#1837): ONE request body POSTed to the account's apiUrl, the response body
 * answered. Called by libmail_core.so on the thread that called [MailCore.sync], synchronously, once per JMAP request.
 *
 * The app implements it on its own HTTP client, so the core uses the app's routing (mesh first, then public with the fleet
 * bearer), its gateway cookie, its limit retry and the phone's trust store: the core adds no second network stack. A failure
 * is answered as [error] (never thrown across JNI).
 */
interface MailCoreTransport {
    fun post(body: String): String

    companion object {
        /** The answer of a request that failed; the reason becomes the sync's error. */
        fun error(reason: String): String = "\u0001ERR\t" + reason.replace('\n', ' ')
    }
}
