package com.diegonmarcos.superapp.fleetendpoints

import org.json.JSONObject

/**
 * #1693 THE ONE PLACE THAT SIGNS IN is Cloud Account. Every other fleet app keeps its sign-in row, but the row
 * opens Cloud Account at that provider's page ([openIntentExtras]) and shows the state Cloud Account reports
 * over the fleet IPC ([METHOD]); the credential itself is read through the granted routes that already exist
 * (`secret`, `drive_credentials`, …), never typed or started anywhere else. 1_cicd's sign-in-owner-guard holds
 * the fleet to it.
 *
 * Wire: `content://<account package>.accountdata`, method [METHOD], `arg` = provider id (`github`, `gitea`,
 * `authelia`, `google`, `vault`, …). Answer: [KEY_OK] and [KEY_JSON] = {"v":1,"provider","state","line","at"}.
 * State only: no token, no identity secret ever rides this method, so it needs no grant beyond the fleet's
 * signature permission. Pure over org.json, so both ends and the JVM tests share the rules.
 */
object SignInContract {
    const val VERSION = 1
    const val ACCOUNT_PACKAGE = FleetEndpointsContract.ACCOUNT_PACKAGE
    const val AUTHORITY = FleetEndpointsContract.AUTHORITY
    const val METHOD = "sign_in_status"
    const val KEY_OK = "ok"
    const val KEY_ERROR = "error"
    const val KEY_JSON = "sign_in_json"

    /**
     * #1693 THE SESSION METHOD: a short-lived Authelia session Cloud Account holds, for the rung of an app that must present
     * it (Drive's Cloud git). Unlike [METHOD] this carries a credential, so it answers ONLY a caller holding the fleet's
     * signature permission, the cookie rides the answer and nothing else (never a log, never a store), and it is
     * refreshed by Cloud Account. `arg` = provider id ([PROVIDER_AUTHELIA]). Answer: [KEY_OK] and [KEY_SESSION_JSON] =
     * {"v":1,"provider","state","header","cookie","expires_at"(epoch ms),"line"}.
     */
    const val METHOD_SESSION = "session"
    const val KEY_SESSION_JSON = "session_json"

    /** Cloud Account's OPEN intent and the place its sign-in pages live (MainActivity: connect > signin, connect > hub). */
    const val ACTION_OPEN = "$ACCOUNT_PACKAGE.OPEN"
    const val EXTRA_SECTION = "section"
    const val EXTRA_PAGE = "page"
    const val EXTRA_PROVIDER = "provider"
    const val SECTION = "connect"
    const val PAGE_SIGN_IN = "signin"
    const val PAGE_PROVIDERS = "hub"
    /** The provider names Cloud Account's pages and state answer to; an app names a provider through these, never a literal. */
    const val PROVIDER_GITHUB = "github"
    const val PROVIDER_AUTHELIA = "authelia"

    enum class State(val wire: String) {
        SIGNED_IN("signed_in"), SIGNED_OUT("signed_out"),
        /** Cloud Account is not installed, refused, or answered something unreadable: [Status.line] says which. */
        UNKNOWN("unknown");
        companion object { fun of(s: String?) = values().firstOrNull { it.wire == s } ?: UNKNOWN }
    }

    data class Status(val provider: String, val state: State, val line: String, val at: String = "") {
        val signedIn: Boolean get() = state == State.SIGNED_IN
        fun json(): JSONObject = JSONObject().put("v", VERSION).put("provider", provider).put("state", state.wire)
            .put("line", line).put("at", at)
    }

    /** The extras of the intent that opens Cloud Account at [provider]'s sign-in page. */
    fun openIntentExtras(provider: String): Map<String, String> = mapOf(
        EXTRA_SECTION to SECTION,
        EXTRA_PAGE to if (provider == "github" || provider == "gitea" || provider == "vault" || provider == "credentials") PAGE_SIGN_IN else PAGE_PROVIDERS,
        EXTRA_PROVIDER to provider,
    )

    /** Reads an answer; anything unreadable is [State.UNKNOWN] with the reason, never an exception. */
    fun parse(provider: String, ok: Boolean, json: String?, error: String?): Status {
        if (!ok) return Status(provider, State.UNKNOWN, "Cloud Account refused: ${error.orEmpty().ifBlank { "no reason given" }}")
        val o = runCatching { JSONObject(json ?: "") }.getOrNull()
            ?: return Status(provider, State.UNKNOWN, "Cloud Account answered something that is not a sign-in status")
        if (o.optInt("v", 0) > VERSION) return Status(provider, State.UNKNOWN, "Cloud Account speaks a newer sign-in status (v${o.optInt("v")}): update this app")
        return Status(provider, State.of(o.optString("state")), o.optString("line"), o.optString("at"))
    }

    /** What an app shows when Cloud Account is not there to ask. */
    fun missing(provider: String) = Status(provider, State.UNKNOWN, "Cloud Account is not installed: install it to sign in")

    /** What the `session` method answers. [cookie] is a credential: use it, never print or keep it past [expiresAtMs]. */
    data class Session(val provider: String, val state: State, val header: String, val cookie: String, val expiresAtMs: Long, val line: String) {
        fun usable(nowMs: Long): Boolean = state == State.SIGNED_IN && cookie.isNotBlank() && expiresAtMs > nowMs
        /** Never carries the cookie: this is the only form a log or a screen may show. */
        override fun toString(): String = "Session(provider=$provider, state=${state.wire}, expiresAtMs=$expiresAtMs, line=$line)"
        fun json(): JSONObject = JSONObject().put("v", VERSION).put("provider", provider).put("state", state.wire)
            .put("header", header).put("cookie", cookie).put("expires_at", expiresAtMs).put("line", line)
    }

    fun noSession(provider: String, state: State, line: String) = Session(provider, state, "Cookie", "", 0L, line)

    /** Reads a `session` answer; anything unreadable or expired is no session with the reason, never an exception. */
    fun parseSession(provider: String, ok: Boolean, json: String?, error: String?, nowMs: Long): Session {
        if (!ok) return noSession(provider, State.UNKNOWN, "Cloud Account refused: ${error.orEmpty().ifBlank { "no reason given" }}")
        val o = runCatching { JSONObject(json ?: "") }.getOrNull()
            ?: return noSession(provider, State.UNKNOWN, "Cloud Account answered something that is not a session")
        if (o.optInt("v", 0) > VERSION) return noSession(provider, State.UNKNOWN, "Cloud Account speaks a newer session answer (v${o.optInt("v")}): update this app")
        val s = Session(provider, State.of(o.optString("state")), o.optString("header").ifBlank { "Cookie" }, o.optString("cookie"), o.optLong("expires_at", 0L), o.optString("line"))
        return if (s.state == State.SIGNED_IN && !s.usable(nowMs)) noSession(provider, State.SIGNED_OUT, "the session Cloud Account held has expired: sign in again in Cloud Account") else s
    }
}
