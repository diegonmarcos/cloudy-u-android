package com.diegonmarcos.superapp.decisions.engine

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.diegonmarcos.superapp.decisions.core.ImageAnswer
import com.diegonmarcos.superapp.decisions.link.IDecisionsEngine
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * [com.diegonmarcos.superapp.decisions.core.Engine] behind libs:decisions-link's IDecisionsEngine,
 * shipped in Cloud-Lib-Decisions-Engine.apk (#881). Every app that wants a decision binds this through
 * the contract module's client and compiles nothing of this module, so an edit here ships this APK alone.
 *
 * WHO IS ASKING comes from the binder (the calling uid's package), never from the request: an app cannot
 * name another app's quota, consent or use. The OpenRouter token is read in THIS process by
 * [EngineHolder] and never appears in a request, a result or a log line.
 *
 * A PUBLISHED CONTRACT: [methodNames] may only grow; an answer that changes shape ships under a new method
 * name and a higher CONTRACT in the manifest.
 */
class DecisionsEngineService : Service() {

    fun methodNames(): Array<String> = arrayOf(DECIDE, STATUS, CONSENT, OUTCOME, IMAGE)

    fun dispatch(method: String, request: String, app: String): String = when (method) {
        DECIDE -> dispatcher.decide(app, request)
        STATUS -> dispatcher.status(app)
        CONSENT -> dispatcher.consent(app, request)
        OUTCOME -> dispatcher.outcome(app, request)
        IMAGE -> dispatcher.imageWithoutFiles()
        else -> throw IllegalArgumentException("unknown method: $method")
    }

    private val dispatcher by lazy { Dispatcher({ EngineHolder.get(applicationContext) }, { EngineHolder.images(applicationContext) }) }

    private val binder = object : IDecisionsEngine.Stub() {
        override fun call(method: String?, request: String?): String =
            runCatching { dispatch(method.orEmpty(), request?.ifEmpty { null } ?: "{}", callerPackage()) }
                // Never let an exception cross the binder: on the far side it is a bare
                // RemoteException with nothing a user could be shown. The message is NOT passed on: it
                // could quote what was asked.
                .getOrElse { JSONObject().put("ok", false).put("reason", "exception").toString() }

        override fun methods(): Array<String> = methodNames()

        override fun image(request: String?, input: ParcelFileDescriptor?, output: ParcelFileDescriptor?): String =
            runCatching { serveImage(request?.ifEmpty { null } ?: "{}", input, output, callerPackage()) }
                .getOrElse {
                    runCatching { output?.close() }
                    JSONObject().put("ok", false).put("reason", "exception").toString()
                }
    }

    /**
     * #1799 one image call: the picture is read from [input] (at most the declared limit plus one byte, so an oversized one
     * is refused, never truncated), the answer's picture written to [output], and one fleet log line names the caller,
     * the use and the outcome (never the prompt, the picture or the token). Both descriptors are closed here.
     */
    fun serveImage(request: String, input: ParcelFileDescriptor?, output: ParcelFileDescriptor?, app: String): String {
        val limit = EngineHolder.images(applicationContext)?.maxInputBytes ?: 0
        val bytes = input?.let { ParcelFileDescriptor.AutoCloseInputStream(it).use { s -> bounded(s, limit + 1) } } ?: ByteArray(0)
        val answer: ImageAnswer = dispatcher.image(app, request, bytes)
        try {
            val pic = answer.image
            if (pic != null && output != null) {
                ParcelFileDescriptor.AutoCloseOutputStream(output).use { it.write(pic) }
            } else {
                runCatching { output?.close() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "image $app ${answer.answer.optString("use")} ✗ write ${e.javaClass.simpleName}")
            return JSONObject().put("ok", false).put("use", answer.answer.optString("use")).put("reason", "write_failed").toString()
        }
        val a = answer.answer
        Log.i(TAG, "image $app ${a.optString("use")} " + if (a.optBoolean("ok")) "✓ bytes=${a.optInt("bytes")}" else "✗ ${a.optString("reason")}")
        return a.toString()
    }

    /** At most [max] bytes of [s]. */
    private fun bounded(s: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (out.size() < max) {
            val n = s.read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** The first package of the calling uid; empty when the system cannot say (the engine then refuses). */
    private fun callerPackage(): String =
        packageManager.getPackagesForUid(Binder.getCallingUid())?.firstOrNull().orEmpty()

    override fun onBind(intent: Intent?): IBinder = binder

    private companion object {
        const val DECIDE = "decide"
        const val STATUS = "status"
        const val CONSENT = "consent"
        const val OUTCOME = "outcome"
        const val IMAGE = "image"
        const val TAG = "DecisionsEngine"
    }
}
