package com.diegonmarcos.cloudlib.gitsync

import org.json.JSONObject

/**
 * How [GitEngine] reaches the git sync engine: JSON in, JSON out. The default implementation runs libs:git-core
 * in the host's own process ([InProcessGitTransport], #1408); the JVM suite hands in a fake, so everything the client
 * does with an answer (the models, a long result in chunks, a known_hosts the engine extended, a
 * refusal) is tested.
 */
interface GitTransport {
    /** Null when the engine can be called, else the sentence that says what to install or update. Costs no bind. */
    fun unavailable(): String?

    /** One bind held for several calls (a long result's chunks). Throws [java.io.IOException] with the same sentence. */
    fun session(): Session

    /** The engine's methods. The answer's "ok" is NOT checked here. */
    interface Session : AutoCloseable {
        /** Throws [java.io.IOException] when the engine cannot be reached. */
        fun git(request: JSONObject): JSONObject
        fun chunk(request: JSONObject): JSONObject
        /** The engine's `info` ({version, files_access}): whether it holds All files access. A transport without it answers ok:false. */
        fun info(): JSONObject = JSONObject().put("ok", false).put("error", "this transport has no info")
        override fun close()
    }
}
