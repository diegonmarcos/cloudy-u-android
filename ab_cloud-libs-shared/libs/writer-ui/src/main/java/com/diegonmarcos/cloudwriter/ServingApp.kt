package com.diegonmarcos.cloudwriter


import com.diegonmarcos.cloudwriter.writerui.BuildConfig

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import com.diegonmarcos.superapp.texttools.TextTools
import org.json.JSONObject

/**
 * WHO serves the text tools on this phone, and which of four states it is in — named, so the
 * status card can say which application to fix and offer the one tap that fixes it.
 *
 * #800 THE BUG THIS EXISTS FOR. The card used to read "said nothing about its AI routing, so it is
 * older than that part of the contract" on a phone whose Cloud Keyboard has answered
 * aiRoutingSnapshot() since 895463367 (2026-09-10). The probe ran in onCreate, a few milliseconds
 * after TextToolsClient's constructor had ASKED for the bind, and bindService answers through a
 * ServiceConnection callback later. So the very first aiRoutingSnapshot() found no binder, got
 * null back, and null was read as "bound and too old". [probe] now waits for the bind (the same
 * wait libs:ml-l-image-mlkit's ImageScanBackendService does before reading the Account token) and
 * keeps "installed but never answered" apart from "answered, but without the AI-routing part".
 */
class ServingApp(
    val packageName: String?,
    val label: String?,
    val version: String?,
    val state: State,
) {
    enum class State {
        /** No application in TextTools.SERVICE_PACKAGES publishes the action: an install. */
        NONE,

        /** Published, but the bind did not complete within [bindWaitMs]: force-stopped or broken. */
        SILENT,

        /** Bound, and aiRoutingSnapshot() came back null: a build older than that method. */
        TOO_OLD,

        /** Bound and named itself. Nothing to repair. */
        OK,
    }

    /** True for every state the Store can repair. */
    val needsStore: Boolean get() = state != State.OK

    companion object {
        private val config: JSONObject by lazy {
            runCatching { JSONObject(String(Base64.decode(BuildConfig.WRITER_UPDATE_B64, Base64.DEFAULT), Charsets.UTF_8)) }
                .getOrDefault(JSONObject())
        }

        /** build.json::update.bind_wait_ms — how long the first probe waits for the bind. */
        val bindWaitMs: Long get() = config.optLong("bind_wait_ms", 3000L)

        /**
         * The package that answers, by the same rule TextToolsClient binds by: the first entry of
         * [TextTools.SERVICE_PACKAGES] that publishes [TextTools.ACTION]. Asked of the package
         * manager by intent, never getPackageInfo, for the visibility reason the client documents.
         */
        fun servingPackage(context: Context): String? {
            val publishing = runCatching {
                context.packageManager.queryIntentServices(Intent(TextTools.ACTION), 0)
                    .mapNotNull { it.serviceInfo?.packageName }
                    .toSet()
            }.getOrDefault(emptySet<String>())
            return TextTools.SERVICE_PACKAGES.firstOrNull { it in publishing }
        }

        /** BLOCKS for up to [bindWaitMs]. Callers run it on a worker thread. */
        fun probe(context: Context, runner: WriterToolRunner): ServingApp {
            val pkg = servingPackage(context) ?: return ServingApp(null, null, null, State.NONE)
            val pm = context.packageManager
            val label = runCatching { pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString() }.getOrDefault(pkg)
            val version = runCatching { pm.getPackageInfo(pkg, 0).versionName }.getOrNull()
            if (!runner.awaitBound(bindWaitMs)) return ServingApp(pkg, label, version, State.SILENT)
            val named = runner.aiRoutingApp() ?: return ServingApp(pkg, label, version, State.TOO_OLD)
            return ServingApp(named, label, version, State.OK)
        }

        /**
         * One tap to the fleet Store's Cloud Constellation page, where the serving app's row has its
         * Update button — build.json::update, the same block and the same target cloud-keyboard's
         * own Config ▸ Update uses. The release page when the SuperApp is not installed.
         */
        fun openStore(context: Context) {
            val intent = Intent()
                .setClassName(config.optString("store_package"), config.optString("store_activity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            config.optJSONObject("store_extras")?.let { e -> e.keys().forEach { intent.putExtra(it, e.getString(it)) } }
            try {
                context.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(config.optString("fallback_url")))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            } catch (_: SecurityException) {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(config.optString("fallback_url")))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }
    }
}
