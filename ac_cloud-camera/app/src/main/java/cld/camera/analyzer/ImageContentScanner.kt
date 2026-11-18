/*
 * Task #461 — the camera's entry point onto the ONE shared image-scan engine.
 *
 * A camera's natural "scan" is the thing it just captured: the user holds the
 * phone up to a code or a page, so a scan meant for content must read the
 * photograph, not only decode the live preview. This class runs the shared
 * engine (libs:ml-l-image — ImageScanEngine: ZXing barcode decode + ML
 * Kit OCR) over a captured image's content Uri, off whatever thread the
 * caller chooses. It is deliberately a thin shell: the engine, the typed
 * payload parser and the failure vocabulary all live in the one shared
 * module that cloud-drive and cloud-gallery already compile in — a
 * private copy of the decode path is the exact defect #170/#261 this fleet
 * fixed, so there is none here.
 *
 * Both halves are always asked, exactly as the shared engine's callers do:
 * decodeBarcode returns null for "no barcode" AND for "could not load", and
 * the OCR result's error string is the tiebreaker that tells them apart.
 *
 * #772 identify: the same engine also RECOGNISES the photo (labels with
 * probabilities, objects) on the route the user picked in More settings ▸
 * Image recognition route — on-device ML Kit by default, an OpenRouter
 * decision model only when picked (the engine falls back to on-device on
 * error, timeout or no token). Barcode and OCR stay on their contract-1 calls,
 * so they keep working against an engine too old to recognise.
 *
 * #798 live identification (IdentifyActivity) also comes through this door: detect asks the engine
 * for objects with a full-label classifier and tracking ids, labels, or text lines, always on
 * device; a saved snapshot is then identified on the user's route through recognize.
 *
 * #1805 Objects' scene (contract 4) too: objects from the chosen detector, text regions as the
 * class "Text" and the five-script OCR layout, always on device.
 */
package cld.camera.analyzer

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.diegonmarcos.superapp.image.mlkit.BarcodeScan
import com.diegonmarcos.superapp.image.mlkit.ImageScanEngine
import com.diegonmarcos.superapp.image.mlkit.OcrResult
import com.diegonmarcos.superapp.image.mlkit.Recognition
import com.diegonmarcos.superapp.image.mlkit.RecognitionConfig
import com.diegonmarcos.superapp.image.mlkit.RecognitionPrefs
import com.diegonmarcos.superapp.image.mlkit.RecognitionRoutes
import org.json.JSONObject
import java.io.File

/**
 * The camera's surface over the shared [ImageScanEngine]. Construct once and
 * reuse: the engine holds an ML Kit text-recognition client that must be a
 * singleton per options, so a fresh instance per scan would be needless churn.
 */
class ImageContentScanner(context: Context) {

    private val ctx = context.applicationContext
    private val engine = ImageScanEngine(ctx)

    /** What one captured image produced: the decoded barcode (or null) and the OCR text. */
    data class Content(
        val barcode: BarcodeScan?,
        val ocr: OcrResult,
        val recognition: Recognition
    ) {
        /** Whether the scan found anything worth showing. */
        val hasAnyContent: Boolean get() = barcode != null || ocr.text.isNotBlank() || recognition.labels.isNotEmpty()
    }

    /**
     * Runs the shared engine over [uri] and returns the result. Blocking; safe
     * from any thread the shared engine documents (the ML Kit and ZXing calls
     * are safe for sequential background use). Never throws — the engine's
     * OCR contract returns an OcrResult with an error string instead.
     */
    fun scan(uri: Uri): Content {
        val barcode = engine.decodeBarcode(uri)
        val ocr = engine.recognizeText(uri)
        return Content(barcode, ocr, RecognitionRoutes.image(ctx, identifyRequest(ctx)) { engine.recognize(uri, it) })
    }

    /**
     * /api/image/recognize and a snapshot: the uniform recognition of a file, on [route] or the
     * user's (#799 Model (Jev) by default; the engine falls back on device and says so).
     */
    fun recognize(file: File, route: String?): Recognition = RecognitionRoutes.image(ctx, request(ctx, route)) { engine.recognize(file, it) }

    /** #799 one request as given, no route rule of its own: the Sound route's Model half (SoundIdentifier) asks through here. */
    fun recognizeAsGiven(bitmap: android.graphics.Bitmap, request: JSONObject): Recognition = engine.recognize(bitmap, request)

    /** The engine's live decision-model catalogue (slugs), for the model picker; empty when it cannot answer. */
    fun models(): List<String> = engine.decisionModels(RecognitionConfig.request(RecognitionConfig.OPENROUTER, "")).map { it.slug }

    /** #798 one viewfinder frame, in stream mode (tracking ids across the frames that follow); always on device. */
    fun detectLive(frame: Bitmap, mode: String): Recognition = engine.detect(frame, RecognitionConfig.detectRequest(mode, live = true))

    /** #798 /api/image/detect: one file this app can read, as a single photo. */
    fun detect(file: File, mode: String): Recognition = engine.detect(file, RecognitionConfig.detectRequest(mode, live = false))

    /** #1805 one frame's scene (Scene.request): objects, text regions, the OCR layout when asked; always on device. */
    fun scene(frame: Bitmap, request: JSONObject): com.diegonmarcos.superapp.image.mlkit.Scene = engine.scene(frame, request)

    /** #1805 an image file this app can read, as one scene. */
    fun scene(file: File, request: JSONObject): com.diegonmarcos.superapp.image.mlkit.Scene = engine.scene(file, request)

    /** #1805 the engine's benchmark of both detectors and the OCR over one image. */
    fun sceneBench(file: File, request: JSONObject): JSONObject = engine.sceneBench(file, request)

    /** Null when the engine answers scene, else what to do — the contract-4 handshake. */
    fun sceneStatus(): String? = engine.check(com.diegonmarcos.superapp.image.BuildConfig.IMAGE_SCENE_CONTRACT)

    /** Null when the engine can detect, else what to do — the contract-3 handshake. */
    fun detectStatus(): String? = engine.check(com.diegonmarcos.superapp.image.BuildConfig.IMAGE_DETECT_CONTRACT)

    /** Null when the engine can recognise, else what to do — the contract-2 handshake. */
    fun status(): String? = engine.check(com.diegonmarcos.superapp.image.BuildConfig.IMAGE_RECOGNIZE_CONTRACT)

    companion object {
        /** A recognize request on [route] (default: the user's choice) with the user's model; the engine reads the Account token. */
        fun request(ctx: Context, route: String? = null): JSONObject =
            RecognitionConfig.request(route ?: RecognitionPrefs.route(ctx), RecognitionPrefs.model(ctx))

        /** Identify only: the barcode and OCR halves already ran on their own calls. */
        fun identifyRequest(ctx: Context): JSONObject = request(ctx).let { r ->
            r.put("ml", JSONObject(r.getJSONObject("ml").toString()).put("ocr", false).put("barcode", false))
        }

        /** "Fruit 92%, Food 81%" — every label with its probability, as the gallery shows them. */
        fun labels(r: Recognition): String = r.labels.joinToString(", ") { "${it.label} ${Math.round(it.p * 100)}%" }
    }
}