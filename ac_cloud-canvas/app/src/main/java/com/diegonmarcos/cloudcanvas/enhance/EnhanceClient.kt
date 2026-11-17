package com.diegonmarcos.cloudcanvas.enhance

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.diegonmarcos.superapp.ai.AiConfig
import com.diegonmarcos.superapp.ai.AiUrlHttp
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.UnknownHostException

/** Prepares the picture and sends it with the prompt through the fleet's OpenRouter route (libs:ai's contract and HTTP). No key is embedded: it is handed in per run. */
object EnhanceClient {
    private const val TIMEOUT_MS = 180_000

    /** The picture scaled so its long side is at most [maxPx], as PNG bytes (JPEG when the PNG is over [maxBytes]). */
    fun prepare(src: Bitmap, maxPx: Int, maxBytes: Int): ByteArray {
        val long = maxOf(src.width, src.height)
        val bmp = if (long > maxPx) Bitmap.createScaledBitmap(src, src.width * maxPx / long, src.height * maxPx / long, true) else src
        val png = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        if (png.size <= maxBytes) return png
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
    }

    fun mimeOf(bytes: ByteArray): String = if (bytes.size > 3 && bytes[0] == 0x89.toByte()) "image/png" else "image/jpeg"

    /** One run. Blocking: call off the main thread. */
    fun run(ctx: Context, key: Secret, model: String, prompt: String, image: ByteArray): EnhanceRequest.Result {
        val cfg = AiConfig.load(ctx)
        val headers = mapOf(
            "Authorization" to "Bearer " + key.reveal(),
            "HTTP-Referer" to cfg.referer,
            "X-Title" to "Cloud Canvas",
            "Content-Type" to "application/json",
        )
        return try {
            val r = AiUrlHttp.post(cfg.chatUrl, headers, EnhanceRequest.body(model, prompt, EnhanceRequest.dataUrl(image, mimeOf(image))), TIMEOUT_MS, null)
            EnhanceRequest.parse(r.code, r.body)
        } catch (e: UnknownHostException) {
            EnhanceRequest.Result.Failure(EnhanceRequest.Kind.OFFLINE, "No network. Connect and try again.")
        } catch (e: IOException) {
            EnhanceRequest.Result.Failure(EnhanceRequest.Kind.OFFLINE, "The request did not complete (" + e.javaClass.simpleName + ").")
        }
    }

    fun decode(bytes: ByteArray): Bitmap? = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}
