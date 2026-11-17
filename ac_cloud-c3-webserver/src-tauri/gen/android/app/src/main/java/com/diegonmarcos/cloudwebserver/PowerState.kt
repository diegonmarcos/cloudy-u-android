package com.diegonmarcos.cloudwebserver

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * #1502 the two master switches, read from the file the Rust crate writes (build.json::power.store, next to repos.json).
 * [serviceAllowed] is the one gate for the foreground service, its sticky restart and the boot autostart: Server ON and
 * Keep alive ON, nothing else (matches build.json::power.matrix, held by server/src/power.rs). Default Keep alive OFF.
 */
object PowerState {
    fun serviceAllowed(server: Boolean, keepAlive: Boolean): Boolean = server && keepAlive

    private fun flag(ctx: Context, key: String, default: Boolean): Boolean = try {
        JSONObject(File(ctx.getExternalFilesDir(null), "power.json").readText()).optBoolean(key, default)
    } catch (e: Exception) {
        default
    }

    fun serverOn(ctx: Context) = flag(ctx, "server", true)

    fun keepAliveOn(ctx: Context) = flag(ctx, "keep_alive", false)

    fun serviceAllowed(ctx: Context) = serviceAllowed(serverOn(ctx), keepAliveOn(ctx))
}
