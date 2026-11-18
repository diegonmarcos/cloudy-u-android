package com.diegonmarcos.superapp.image.mlkit

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.diegonmarcos.superapp.decisions.EngineKeyedHttp
import com.diegonmarcos.superapp.decisions.Http
import com.diegonmarcos.superapp.decisions.UrlHttp
import com.diegonmarcos.superapp.decisions.link.DecisionsLink
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * [ImageScanner] behind libs:ml-l-image's IImageScanEngine, shipped in
 * Cloud-Lib-Ml-L-Image-Mlkit.apk (engine-apk-split move 6). Drive, Mail, Camera,
 * Media Center and Office bind this through the contract module's client and compile
 * nothing of this module, so an edit here ships this APK alone.
 *
 * THE IMAGE ARRIVES AS A DESCRIPTOR the caller opened (this process cannot open the
 * caller's files or its granted Uris); it is read to the end — the caller may hand a
 * file, a provider's stream or a pipe — capped at [MAX_IMAGE_BYTES].
 *
 * A PUBLISHED CONTRACT: [methodNames] may only grow; an answer that changes shape ships
 * under a new method name and a higher CONTRACT in the manifest. Not a DataBackendService
 * because core's IDataBackend carries strings only, and the image is a descriptor.
 *
 * #772 CONTRACT 2: recognize (the [Recognizer]: on-device ML or an OpenRouter decision model,
 * one result shape) and models (the live decision-model catalogue for the route's picker),
 * both through scanWith, which carries a JSON request. INTERNET is this APK's since: only the
 * OpenRouter route and the catalogue use it, through libs:decisions over Android's resolver.
 * #1801 the OpenRouter route holds no key: its Decisions request goes to the fleet's AI-key owner
 * engine ([relayHttp], DecisionsLink.relay, use image_scan_recognize), which adds the key.
 *
 * #798 CONTRACT 3: detect (the [Detector]: objects with a full-label classifier and tracking ids,
 * image labels, or text lines with boxes; fully on device), through scanWith.
 *
 * #1805 CONTRACT 4: scene (the [SceneDetector]: objects from EfficientDet-Lite0 on MediaPipe or
 * YOLO11n on LiteRT, text regions as the class "Text", and the five-script OCR layout of blocks,
 * lines and words with boxes; fully on device) and scene_bench (the same backends timed over one
 * image, for the emulator benchmark), through scanWith.
 */
class ImageScanBackendService : Service() {

    private val scanner by lazy { ImageScanner() }
    private val onDevice by lazy { MlKitOnDevice() }
    private val recognizer by lazy { Recognizer(scanner, onDevice, relayHttp, { null }) }
    private val detector by lazy { Detector(scanner, onDevice) }
    private val scene by lazy { SceneDetector(scanner, SceneOnDevice(applicationContext)) }

    fun methodNames(): Array<String> = arrayOf(BARCODE, OCR, RECOGNIZE, MODELS, DETECT, SCENE, SCENE_BENCH)

    fun dispatch(method: String, image: ByteArray, request: String = "{}"): String = when (method) {
        BARCODE -> scanner.barcodeJson(image)
        OCR -> scanner.ocrJson(image)
        RECOGNIZE -> recognizer.json(image, request)
        MODELS -> models(JSONObject(request))
        DETECT -> detector.json(image, request)
        SCENE -> scene.json(image, request)
        SCENE_BENCH -> scene.benchJson(image, request)
        else -> throw IllegalArgumentException("unknown method: $method")
    }

    private val binder = object : IImageScanEngine.Stub() {
        override fun scan(method: String?, image: ParcelFileDescriptor?): String =
            runCatching { dispatch(method.orEmpty(), readAll(requireNotNull(image) { "no image descriptor" })) }
                // Never let an exception cross the binder: on the far side it is a bare
                // RemoteException with nothing a user could be shown.
                .getOrElse { t -> JSONObject().put("error", t.message ?: t.toString()).toString() }

        override fun methods(): Array<String> = methodNames()

        override fun scanWith(method: String?, request: String?, image: ParcelFileDescriptor?): String =
            runCatching { dispatch(method.orEmpty(), image?.let { readAll(it) } ?: ByteArray(0), request ?: "{}") }
                .getOrElse { t -> JSONObject().put("error", t.message ?: t.toString()).toString() }
    }

    /** The decision models the catalogue lists (request.openrouter.models_url): slug, image input, price. */
    private fun models(req: JSONObject): String {
        val url = req.optJSONObject("openrouter")?.optString("models_url").orEmpty()
        require(url.startsWith("https://")) { "the request carries no https models_url" }
        val r = UrlHttp.send(url, null, null, req.optJSONObject("openrouter")?.optInt("timeout_ms", 15000) ?: 15000)
        require(r.code in 200..299) { "HTTP ${r.code} from the model catalogue" }
        return JSONObject().put("models", Recognizer.catalogue(r.body)).toString()
    }

    /**
     * #1801 the OpenRouter route's transport: a POST (the Decisions request) goes to the AI-key owner engine, which adds
     * the key; a GET goes straight out. An engine refusal (no engine, no key) is a transport failure with its reason, so the
     * route falls back on device and the result says why, exactly as a missing token did.
     */
    private val relayHttp = object : EngineKeyedHttp {
        override fun send(url: String, token: String?, body: String?, timeoutMs: Int): Http.Response {
            if (body == null) return UrlHttp.send(url, null, null, timeoutMs)
            val r = DecisionsLink.relay(applicationContext, RELAY_USE, "decisions", body)
            if (!r.ok) throw java.io.IOException(r.why)
            return Http.Response(r.code, r.body)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun readAll(image: ParcelFileDescriptor): ByteArray =
        ParcelFileDescriptor.AutoCloseInputStream(image).use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                require(out.size() <= MAX_IMAGE_BYTES) { "the image is larger than ${MAX_IMAGE_BYTES / (1024 * 1024)} MB" }
            }
            out.toByteArray()
        }

    private companion object {
        const val BARCODE = "barcode"
        const val OCR = "ocr"
        const val RECOGNIZE = "recognize"
        const val MODELS = "models"
        const val DETECT = "detect"
        const val SCENE = "scene"
        const val SCENE_BENCH = "scene_bench"

        /** The declared relay use (decisions.json::relay.uses). */
        const val RELAY_USE = "image_scan_recognize"

        /** ponytail: one whole image in memory; a phone photo is a few MB, 64 MB refuses a runaway stream. */
        const val MAX_IMAGE_BYTES = 64 * 1024 * 1024
    }
}
