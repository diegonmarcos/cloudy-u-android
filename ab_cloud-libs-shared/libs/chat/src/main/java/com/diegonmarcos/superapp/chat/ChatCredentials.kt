package com.diegonmarcos.superapp.chat

import android.content.Context

/**
 * #1693 THE HOST HOOKS for the Mattermost login: the email and password are typed once in Cloud Account (App credentials) and the
 * host (the SuperApp) wires [read] and [open] to its AccountClient, so this lib links nothing of the fleet's IPC. A read BLOCKS (a
 * provider call): the fragment calls it off the main thread. Nothing here logs a value; an unwired host answers with the sentence
 * naming where to enter it, never a crash.
 */
object ChatCredentials {
    /** `mattermost.login` (email or username) and `mattermost.password`: the Cloud Account ids. */
    const val ID_LOGIN = "mattermost.login"
    const val ID_PASSWORD = "mattermost.password"

    data class Cred(val value: String, val line: String) { val present: Boolean get() = value.isNotBlank()
        override fun toString() = "Cred(value=${if (present) "<redacted>" else "none"}, line=$line)" }

    const val UNWIRED = "Enter the Mattermost login and password in Cloud Account > App credentials, then sign in again"

    @Volatile var read: (Context, String) -> Cred = { _, _ -> Cred("", UNWIRED) }
    /** Opens Cloud Account at App credentials; null when it opened, else the reason. */
    @Volatile var open: (Context) -> String? = { UNWIRED }

    /** The pure rule the fragment applies to what it read: both present, else the first sentence naming the fix. */
    fun usable(login: Cred, password: Cred): String? = when {
        !login.present -> login.line.ifBlank { UNWIRED }
        !password.present -> password.line.ifBlank { UNWIRED }
        else -> null
    }
}
