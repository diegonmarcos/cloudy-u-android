package com.diegonmarcos.superapp.image.mlkit

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.diegonmarcos.superapp.image.BuildConfig
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The fleet's ONE image scan, REACHED, NOT CARRIED (engine-apk-split move 6). Same public
 * surface the five consumers (Drive, Mail, Camera, Media Center, Office) always called, but
 * ZXing, ML Kit and the native OCR pipeline now run only in the engine APK
 * (Cloud-Lib-Ml-L-Image-Mlkit.apk), so an engine change republishes that APK and no app.
 *
 * THE IMAGE CROSSES AS A FILE DESCRIPTOR. The engine runs in its own uid: it cannot open a
 * File in the caller's storage, nor a content Uri granted to the caller. So the CALLER opens
 * it (File → ParcelFileDescriptor.open, Uri → ContentResolver.openFileDescriptor, Bitmap →
 * a PNG in this app's cache, unlinked once open) and the binder carries the descriptor.
 *
 * THE HANDSHAKE COMES FIRST AND COSTS NO BIND: [check] resolves the declared action in the
 * declared package (engine-client.json, resolved from the fleet manifest at build time and
 * queried in this module's manifest) and reads the CONTRACT it declares. Missing and too-old
 * are different sentences naming the Store.
 *
 * SAME RESULT CONTRACT AS BEFORE: [decodeBarcode] answers null for "no barcode" and for
 * "could not scan"; [recognizeText] never throws and carries the reason in [OcrResult.error]
 * — which is the tiebreaker consumers already use, and now also says "install the engine".
 *
 * BLOCKING: binding waits on the main thread's delivery, so call off the main thread (every
 * consumer already did: the scan itself always blocked).
 */
class ImageScanEngine(context: Context) {

    private val ctx = context.applicationContext

    @Volatile private var remote: IImageScanEngine? = null
    @Volatile private var latch: CountDownLatch? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            remote = binder?.takeIf { it.pingBinder() }?.let { IImageScanEngine.Stub.asInterface(it) }
            latch?.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName?) { remote = null }
    }

    /** Null when the engine is ready (and bound), else the sentence that says what to do. */
    fun check(): String? = check(BuildConfig.IMAGE_ENGINE_MIN_CONTRACT)

    /**
     * The same handshake for a method that needs a newer engine than the floor every consumer
     * needs: #772 recognize asks for engine-client.json::method_contracts.recognize, so an app
     * that only scans barcodes keeps working against a contract-1 engine.
     */
    fun check(needed: Int): String? {
        val pm = ctx.packageManager
        val pkg = BuildConfig.IMAGE_ENGINE_PACKAGE
        val service = pm.resolveService(Intent(BuildConfig.IMAGE_ENGINE_ACTION).setPackage(pkg), PackageManager.GET_META_DATA)
            ?.serviceInfo
        if (service == null) {
            val installed = runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess
            return if (installed) "$pkg has no image-scan engine service — update it from Store ▸ Cloud Constellation ▸ Libs"
                   else "the image-scan engine $pkg is not installed — install it from Store ▸ Cloud Constellation ▸ Libs"
        }
        val found = service.metaData?.getInt(CONTRACT_KEY, 0) ?: 0
        if (found < needed) return "$pkg serves contract $found, this build needs $needed — update it from Store ▸ Cloud Constellation ▸ Libs"
        return if (connect(service.packageName, service.name)) null else "$pkg did not accept the bind"
    }

    @Synchronized
    private fun connect(pkg: String, cls: String): Boolean {
        if (remote != null) return true
        val l = CountDownLatch(1)
        latch = l
        val started = runCatching {
            ctx.bindService(Intent().setClassName(pkg, cls), connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!started) return false
        runCatching { l.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        return remote != null
    }

    fun decodeBarcode(file: File): BarcodeScan? = barcode { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) }
    fun decodeBarcode(uri: Uri): BarcodeScan? = barcode { ctx.contentResolver.openFileDescriptor(uri, "r") }
    fun decodeBarcode(bitmap: Bitmap): BarcodeScan? = barcode { descriptorOf(bitmap) }

    fun recognizeText(file: File): OcrResult = ocr { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) }
    fun recognizeText(uri: Uri): OcrResult = ocr { ctx.contentResolver.openFileDescriptor(uri, "r") }
    fun recognizeText(bitmap: Bitmap): OcrResult = ocr { descriptorOf(bitmap) }

    /**
     * #772 recognise what is in the image, on the [request]'s route (RecognitionConfig.request):
     * the uniform [Recognition], whichever route answered. Never throws.
     */
    fun recognize(file: File, request: org.json.JSONObject): Recognition = recognition(request) { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) }
    fun recognize(uri: Uri, request: org.json.JSONObject): Recognition = recognition(request) { ctx.contentResolver.openFileDescriptor(uri, "r") }
    fun recognize(bitmap: Bitmap, request: org.json.JSONObject): Recognition = recognition(request) { descriptorOf(bitmap) }

    /**
     * #798 live identification (RecognitionConfig.detectRequest): objects with boxes, labels and
     * tracking ids, image labels, or text lines, always on device. A viewfinder frame crosses as a
     * JPEG (a PNG of every frame costs more than the detection). Never throws.
     */
    fun detect(bitmap: Bitmap, request: org.json.JSONObject): Recognition = detection(request) { descriptorOf(bitmap, Bitmap.CompressFormat.JPEG) }
    fun detect(file: File, request: org.json.JSONObject): Recognition = detection(request) { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) }

    /**
     * #1805 Cloud Camera > Objects (RecognitionConfig.sceneRequest): objects from the request's
     * detector, text regions as the class "Text", and, when asked, the five-script OCR layout
     * (blocks, lines, words with boxes), all on device, in one coordinate space. Never throws.
     */
    fun scene(bitmap: Bitmap, request: org.json.JSONObject): Scene = sceneOf(request) { descriptorOf(bitmap, Bitmap.CompressFormat.JPEG) }
    fun scene(file: File, request: org.json.JSONObject): Scene = sceneOf(request) { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) }

    /** #1805 the engine-side benchmark of both detectors and the OCR over one image: its JSON, or {"ok": false, "error"}. */
    fun sceneBench(file: File, request: org.json.JSONObject): JSONObject =
        runCatching { JSONObject(ask(SCENE_BENCH, { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) }, request.toString(), BuildConfig.IMAGE_SCENE_CONTRACT)) }
            .getOrElse { JSONObject().put("ok", false).put("error", it.message ?: it.toString()) }

    private fun sceneOf(request: org.json.JSONObject, open: () -> ParcelFileDescriptor?): Scene =
        runCatching { Scene.parse(ask(SCENE, open, request.toString(), BuildConfig.IMAGE_SCENE_CONTRACT)) }
            .getOrElse { Scene.failed(it.message ?: it.toString()) }

    private fun detection(request: org.json.JSONObject, open: () -> ParcelFileDescriptor?): Recognition =
        runCatching { Recognition.parse(ask(DETECT, open, request.toString(), BuildConfig.IMAGE_DETECT_CONTRACT), RecognitionConfig.ML) }
            .getOrElse { Recognition.failed(RecognitionConfig.ML, it.message ?: it.toString()) }

    private fun recognition(request: org.json.JSONObject, open: () -> ParcelFileDescriptor?): Recognition {
        val route = request.optString("route", RecognitionConfig.ML)
        return runCatching { Recognition.parse(ask(RECOGNIZE, open, request.toString(), BuildConfig.IMAGE_RECOGNIZE_CONTRACT), route) }
            .getOrElse { Recognition.failed(route, it.message ?: it.toString()) }
    }

    /**
     * #772 the live decision-model catalogue for the OpenRouter route's picker, fetched by the
     * engine (it holds the network): slug, whether it takes images, USD per prompt token.
     * Empty when the engine or the network cannot answer — the declared default still works.
     */
    fun decisionModels(request: org.json.JSONObject): List<DecisionModel> = runCatching {
        val a = JSONObject(ask(MODELS, { null }, request.toString(), BuildConfig.IMAGE_RECOGNIZE_CONTRACT)).getJSONArray("models")
        (0 until a.length()).map { a.getJSONObject(it) }.map { DecisionModel(it.getString("slug"), it.optBoolean("images"), if (it.isNull("prompt_price")) null else it.optDouble("prompt_price")) }
    }.getOrDefault(emptyList())

    private fun barcode(open: () -> ParcelFileDescriptor?): BarcodeScan? {
        val o = runCatching { JSONObject(ask(BARCODE, open)) }.getOrNull() ?: return null
        if (!o.optBoolean("found")) return null
        val raw = o.optString("rawValue")
        return BarcodeScan(format = o.optString("format", "UNKNOWN"), rawValue = raw, payload = BarcodePayloadParser.parse(raw))
    }

    private fun ocr(open: () -> ParcelFileDescriptor?): OcrResult {
        val text = runCatching { ask(OCR, open) }
            .getOrElse { return OcrResult(text = "", segments = emptyList(), error = it.message ?: it.toString()) }
        val o = runCatching { JSONObject(text) }
            .getOrElse { return OcrResult(text = "", segments = emptyList(), error = "the image-scan engine answered $OCR with something that is not JSON") }
        val segments = o.optJSONArray("segments")?.let { a ->
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i) }.map { s ->
                OcrSegment(text = s.optString("text"), confidence = if (s.isNull("confidence")) null else s.optDouble("confidence").toFloat())
            }
        }.orEmpty()
        return OcrResult(
            text = o.optString("text"),
            segments = segments,
            error = o.optString("error").takeIf { !o.isNull("error") && it.isNotBlank() },
            language = o.optString("language").takeIf { !o.isNull("language") && it.isNotBlank() },
        )
    }

    /**
     * Every engine call: the handshake (at [needed]), then the caller-opened descriptor over the
     * binder — with a JSON [request] on the contract-2 entry point when there is one.
     */
    private fun ask(method: String, open: () -> ParcelFileDescriptor?, request: String? = null, needed: Int = BuildConfig.IMAGE_ENGINE_MIN_CONTRACT): String {
        val why = check(needed)
        val r = remote
        if (why != null || r == null) throw IllegalStateException(why ?: "the image-scan engine is not ready")
        val fd = open()
        if (fd == null && request == null) throw IllegalStateException("cannot open the image")
        return try {
            (if (request == null) r.scan(method, fd) else r.scanWith(method, request, fd))
                ?: throw IllegalStateException("the image-scan engine did not answer $method")
        } finally {
            fd?.close()
        }
    }

    /** A Bitmap has no descriptor: write it once (PNG unless told otherwise) into this app's cache, open it, unlink it. */
    private fun descriptorOf(bitmap: Bitmap, format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG): ParcelFileDescriptor {
        val tmp = File.createTempFile("scan", if (format == Bitmap.CompressFormat.PNG) ".png" else ".jpg", ctx.cacheDir)
        try {
            tmp.outputStream().use { bitmap.compress(format, JPEG_QUALITY, it) }
            return ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
        } finally {
            tmp.delete()
        }
    }

    private companion object {
        /** The engine CONTRACT meta-data key (libs/ml-l-image-mlkit's manifest). */
        const val CONTRACT_KEY = "com.diegonmarcos.cloud.engine.CONTRACT"

        // The engine's method names (ImageScanBackendService). Strings, not an import: no
        // consumer compiles the engine, and that is the point.
        const val BARCODE = "barcode"
        const val OCR = "ocr"
        const val RECOGNIZE = "recognize"
        const val MODELS = "models"
        const val DETECT = "detect"
        const val SCENE = "scene"
        const val SCENE_BENCH = "scene_bench"

        const val BIND_TIMEOUT_MS = 4000L

        /** A live frame's JPEG quality (PNG ignores it): high enough for text mode to read small print. */
        const val JPEG_QUALITY = 90
    }
}

/** #772 one decision model the OpenRouter route may run on. */
data class DecisionModel(val slug: String, val images: Boolean, val promptPrice: Double?)

/** One successful barcode decode: the format name, the raw value, and its typed meaning. */
data class BarcodeScan(
    val format: String,
    val rawValue: String,
    val payload: BarcodePayload
)

/** One line of recognised text with ML Kit's confidence, when it reports one. */
data class OcrSegment(
    val text: String,
    val confidence: Float?
)

/**
 * The OCR outcome. [text] is the whole recognised text; [segments] are the line-level
 * pieces; [error] is null on success and a short reason on failure (recognizeText never
 * throws); [language] is the BCP-47 tag the recogniser committed to, or null.
 */
data class OcrResult(
    val text: String,
    val segments: List<OcrSegment>,
    val error: String? = null,
    val language: String? = null
)
