// SPDX-License-Identifier: Apache-2.0
package com.diegonmarcos.superapp.netwg

import java.io.File

/**
 * #1881 one Go engine for wg-go + firestack: the mesh tunnel engine switch.
 *
 * Two engines run the SAME wireguard-go: the JNI `libwg-go.so` (the default, the
 * path every phone runs today) and the firestack gomobile engine (`libgojni.so`,
 * class `com.celzero.firestack.mesh.Mesh`). The switch is a RUNTIME flag, read once
 * when GoBackend is built, and it can only ever move a phone OFF the default when
 * the operator asked for it AND the firestack engine is actually present in this
 * APK. Anything else (no flag, an unknown value, an empty file, an aar without the
 * mesh package, a load error) resolves to [WG_GO], so VPN never breaks on a typo.
 *
 * Flag: the one-line file `<filesDir>/mesh-engine` holding `firestack` or `wg-go`
 * (`adb shell run-as com.diegonmarcos.cloudlib.netwg sh -c 'echo firestack > files/mesh-engine'`).
 */
object MeshEngineSelector {
    const val WG_GO = "wg-go"
    const val FIRESTACK = "firestack"
    const val FLAG_FILE = "mesh-engine"

    /** [requested] is the raw flag text (null = none). Pure; the whole selection policy. */
    @JvmStatic
    fun select(requested: String?, firestackAvailable: Boolean): String {
        val want = requested?.trim()?.lowercase()
        return if (want == FIRESTACK && firestackAvailable) FIRESTACK else WG_GO
    }

    /** The raw flag text, or null when absent/unreadable. */
    @JvmStatic
    fun readFlag(filesDir: File?): String? = try {
        val f = File(filesDir, FLAG_FILE)
        if (filesDir != null && f.isFile && f.length() < 64) f.readText() else null
    } catch (e: Exception) { null }

    /** What GoBackend runs: the default unless the flag asks for firestack and it loads. */
    @JvmStatic
    fun choose(filesDir: File?, firestackAvailable: () -> Boolean): String {
        val requested = readFlag(filesDir)
        // Only probe the second engine when it was asked for: the default path loads nothing new.
        val want = requested?.trim()?.lowercase() == FIRESTACK
        return select(requested, want && firestackAvailable())
    }
}

/** The libwg-go function set, engine-neutral. */
interface MeshEngine {
    val id: String
    fun turnOn(ifName: String, tunFd: Int, settings: String): Int
    fun turnOff(handle: Int)
    fun socketV4(handle: Int): Int
    fun socketV6(handle: Int): Int
    fun config(handle: Int): String?
    fun version(): String
}

/**
 * The firestack engine, reached by reflection so this module compiles and runs
 * against a firestack aar that predates the `mesh` package (then [load] is null
 * and the selector falls back to wg-go). gomobile maps Go `int32` to Java `int`.
 */
class FirestackMeshEngine private constructor(private val cls: Class<*>) : MeshEngine {
    override val id = MeshEngineSelector.FIRESTACK
    private fun call(name: String, vararg a: Any?): Any? {
        val m = cls.methods.first { it.name == name && it.parameterCount == a.size }
        return m.invoke(null, *a)
    }
    override fun turnOn(ifName: String, tunFd: Int, settings: String) = call("turnOn", ifName, tunFd, settings) as Int
    override fun turnOff(handle: Int) { call("turnOff", handle) }
    override fun socketV4(handle: Int) = call("getSocketV4", handle) as Int
    override fun socketV6(handle: Int) = call("getSocketV6", handle) as Int
    override fun config(handle: Int) = call("getConfig", handle) as String?
    override fun version() = call("version") as String

    companion object {
        const val CLASS = "com.celzero.firestack.mesh.Mesh"

        /** Loads libgojni via gomobile's Seq static init; null when the engine is not in this APK. */
        @JvmStatic
        fun load(): FirestackMeshEngine? = try {
            val c = Class.forName(CLASS)
            FirestackMeshEngine(c).also { it.version() } // proves the native side answers
        } catch (e: Throwable) { null }
    }
}
