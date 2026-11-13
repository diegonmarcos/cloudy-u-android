package com.diegonmarcos.superapp.ops

import android.content.Context

/**
 * #1693 THE HOST HOOKS for the Dagu sign-in: the Authelia bearer token is typed once in Cloud Account (App credentials) and the
 * host (the SuperApp, Cloud C3) wires [read] and [open] to its AccountClient, so this lib links nothing of the fleet's IPC. A read
 * BLOCKS (a provider call): the fragment calls it off the main thread. No log carries the value; an unwired host answers with
 * the sentence naming where to enter it, never a crash.
 */
object OpsCredentials {
    /** The Cloud Account id of the Dagu bearer token. */
    const val ID_DAGU_TOKEN = "dagu.token"

    data class Cred(val value: String, val line: String) { val present: Boolean get() = value.isNotBlank()
        override fun toString() = "Cred(value=${if (present) "<redacted>" else "none"}, line=$line)" }

    const val UNWIRED = "Enter the Dagu bearer token in Cloud Account > App credentials, then try again"

    @Volatile var read: (Context, String) -> Cred = { _, _ -> Cred("", UNWIRED) }
    /** Opens Cloud Account at App credentials; null when it opened, else the reason. */
    @Volatile var open: (Context) -> String? = { UNWIRED }

    /** The pure rule the fragment applies: the sentence naming the fix, or null when the token is usable. */
    fun usable(token: Cred): String? = if (token.present) null else token.line.ifBlank { UNWIRED }
}
