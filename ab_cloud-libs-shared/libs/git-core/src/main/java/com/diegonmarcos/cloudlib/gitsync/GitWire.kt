package com.diegonmarcos.cloudlib.gitsync

import java.io.File
import kotlinx.serialization.json.Json
import org.json.JSONObject

/**
 * The names both halves of the git sync engine speak in (#871 move 11), in ONE place: the client
 * ([GitEngine]) builds requests with them, the engine (libs:git-core GitCalls, in-process) answers in them.
 *
 *   request  {"dir": "<work tree>", "verb": "<one of VERBS>", "args": {...}, "auth": {"kind": ...}}
 *   answer   {"ok": true, "result": "<the model as JSON text>", "more"?: true, "id"?: ..., "known_hosts"?: "<text>"}
 *            {"ok": false, "error": "<a sentence a person can read>"}
 *   chunk    {"id": ..., "offset": N} -> {"ok": true, "result": "<next piece>", "more": bool}
 *
 * A PUBLISHED CONTRACT: the verbs and fields may only grow. The models travel as kotlinx-serialization
 * JSON text (the same classes on both sides), so a model never has a second hand-written codec.
 */
object GitWire {
    val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** The verbs the "git" method answers; the engine refuses anything else. */
    /** #1408 the compound ops Cloud Drive's sheet sends raw (RepoCompound); answered in-process and by the Termux wire. */
    val COMPOUND: Set<String> = setOf("merge_upstream", "force_push_lease", "stash", "checkout_main", "rebase_continue", "shrink", "keep_depth")

    val VERBS: Set<String> = setOf(
        "status", "stage", "stage_all", "unstage", "unstage_all", "discard", "commit",
        "push", "force_push", "fetch", "pull", "force_pull", "sync",
        "branches", "log", "diff", "commit_diff",
        "stash_push", "stash_pop", "stash_list", "set_upstream", "upstream",
        "remotes", "add_remote", "remove_remote", "set_remote_url",
        "resolve", "mark_resolved", "abort_merge", "init", "clone",
    )

    const val KIND_NONE = "none"
    const val KIND_HTTPS = "https"
    const val KIND_SSH = "ssh"
    const val KIND_SESSION = "session"

    /** One request. [args] and [auth] are optional; the auth is [authToJson]'s. */
    fun request(dir: File, verb: String, args: JSONObject = JSONObject(), auth: JSONObject? = null): JSONObject =
        JSONObject().put("dir", dir.absolutePath).put("verb", verb).put("args", args).put("auth", auth ?: JSONObject().put("kind", KIND_NONE))

    /**
     * The credentials as the engine receives them. An ssh key and its known_hosts are FILES in the caller's
     * private storage, which another uid cannot read, so their text travels; [readText] returns a file's text,
     * or null when it does not exist (known_hosts on a first contact).
     */
    fun authToJson(auth: GitAuth, readText: (String) -> String? = { p -> File(p).takeIf { it.isFile }?.readText() }): JSONObject = when (auth) {
        is GitAuth.None -> JSONObject().put("kind", KIND_NONE)
        is GitAuth.Https -> JSONObject().put("kind", KIND_HTTPS).put("username", auth.username).put("secret", auth.secret)
        is GitAuth.Ssh -> JSONObject().put("kind", KIND_SSH)
            .put("key", readText(auth.privateKeyPath) ?: throw java.io.IOException("ssh key not found: ${auth.privateKeyPath}"))
            .put("passphrase", auth.passphrase ?: "")
            .put("known_hosts", readText(auth.knownHostsPath) ?: "")
        is GitAuth.Session -> JSONObject().put("kind", KIND_SESSION).put("header", auth.header).put("value", auth.value)
    }
}
