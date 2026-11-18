package com.diegonmarcos.cloudcalc.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import com.diegonmarcos.cloudcalc.decide.JevStore
import com.diegonmarcos.superapp.image.mlkit.DecisionModel
import com.diegonmarcos.superapp.image.mlkit.ImageScanEngine
import com.diegonmarcos.superapp.image.mlkit.OcrResult
import com.diegonmarcos.superapp.image.mlkit.Recognition
import com.diegonmarcos.superapp.image.mlkit.RecognitionConfig
import com.diegonmarcos.superapp.image.mlkit.RecognitionPrefs
import com.diegonmarcos.superapp.image.mlkit.RecognitionRoutes
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * The Camera tools' one door to the fleet's image engine (#772): every recognition, OCR and colour
 * name goes through libs:ml-l-image's client to Cloud-Lib-Ml-L-Image-Mlkit.apk — nothing is
 * recognised in this process. The route and model are the user's (RecognitionPrefs, Jev ▸ Configs
 * ▸ Image route); a manual OpenRouter token set in Jev ▸ Configs ▸ Token rides along, otherwise
 * the engine reads the fleet Account's itself. Blocks: call off the main thread.
 */
object Vision {
    @Volatile private var engine: ImageScanEngine? = null

    fun engine(ctx: Context): ImageScanEngine =
        engine ?: synchronized(this) { engine ?: ImageScanEngine(ctx.applicationContext).also { engine = it } }

    /** Null when the engine can recognise (contract 2 installed and bound), else what to do. */
    fun status(ctx: Context): String? = engine(ctx).check(com.diegonmarcos.superapp.image.BuildConfig.IMAGE_RECOGNIZE_CONTRACT)

    /** A recognize request on [route] (default: the user's choice) with the user's model. */
    fun request(ctx: Context, route: String? = null, context: String = ""): JSONObject =
        RecognitionConfig.request(route ?: RecognitionPrefs.route(ctx), RecognitionPrefs.model(ctx), null, context)

    /** #799 on the user's route (Model (Jev) by default) with the fallback rule; offline asks on device at once. */
    fun recognize(ctx: Context, photo: File, route: String? = null, context: String = ""): Recognition =
        RecognitionRoutes.image(ctx, request(ctx, route, context)) { engine(ctx).recognize(photo, it) }

    fun ocr(ctx: Context, photo: File): OcrResult = engine(ctx).recognizeText(photo)

    /** #798 the engine's detection (contract 3) of one photo: boxes named by the full-label classifier, labels, or text lines. */
    fun detect(ctx: Context, photo: File, mode: String): Recognition = engine(ctx).detect(photo, RecognitionConfig.detectRequest(mode, live = false))

    /** Null when the engine can detect, else what to do — the contract-3 handshake. */
    fun detectStatus(ctx: Context): String? = engine(ctx).check(com.diegonmarcos.superapp.image.BuildConfig.IMAGE_DETECT_CONTRACT)

    /** The live decision-model catalogue for the Image route picker (the engine fetches it). */
    fun models(ctx: Context): List<DecisionModel> = engine(ctx).decisionModels(RecognitionConfig.request(RecognitionConfig.OPENROUTER, ""))

    /**
     * The engine's name for the colour [argb], in recognition.json's vocabulary: a one-colour
     * swatch through the on-device route with every model turned off, so naming stays the
     * engine's one rule rather than a second copy here. Null when the engine cannot answer.
     */
    fun colourName(ctx: Context, argb: Int): String? {
        val swatch = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(argb) }
        val req = request(ctx, RecognitionConfig.ML)
        req.put("ml", JSONObject(req.getJSONObject("ml").toString()).put("max_labels", 0).put("max_objects", 0).put("ocr", false).put("barcode", false))
        return engine(ctx).recognize(swatch, req).colours.firstOrNull()?.name?.takeIf { it.isNotBlank() }
    }

    // ── photos ───────────────────────────────────────────────────────────────────────────────

    fun dir(ctx: Context): File = File(ctx.filesDir, "camera").apply { mkdirs() }

    /** The last photo taken: /api/image/recognize?path=last reads it. */
    fun last(ctx: Context): File = File(dir(ctx), "last.jpg")

    /**
     * The photo at [file] upright (EXIF applied) and at most [maxSide] on its longest side,
     * written back as JPEG at [quality]: what every Camera tool then reads.
     */
    fun normalise(file: File, maxSide: Int, quality: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val raw = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val rotation = when (runCatching { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val s = min(1f, maxSide.toFloat() / max(raw.width, raw.height))
        val m = Matrix().apply { postScale(s, s); postRotate(rotation) }
        val out = if (s < 1f || rotation != 0f) Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true) else raw
        if (out !== raw) raw.recycle()
        file.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        return out
    }

    // ── a tapped colour, read locally (formatting only; the name is the engine's) ────────────

    /** The mean colour of the [size]×[size] square centred on ([x], [y]), clipped to the image. */
    fun average(bmp: Bitmap, x: Int, y: Int, size: Int): Int {
        val h = size / 2
        val x0 = (x - h).coerceIn(0, bmp.width - 1); val x1 = (x + h).coerceIn(0, bmp.width - 1)
        val y0 = (y - h).coerceIn(0, bmp.height - 1); val y1 = (y + h).coerceIn(0, bmp.height - 1)
        var r = 0L; var g = 0L; var b = 0L; var n = 0L
        for (yy in y0..y1) for (xx in x0..x1) {
            val p = bmp.getPixel(xx, yy)
            r += (p shr 16) and 0xff; g += (p shr 8) and 0xff; b += p and 0xff; n++
        }
        return (0xff shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
    }

    fun hex(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

    /** Hue (degrees), saturation and lightness (percent), rounded. */
    fun hsl(argb: Int): Triple<Int, Int, Int> {
        val r = ((argb shr 16) and 0xff) / 255.0; val g = ((argb shr 8) and 0xff) / 255.0; val b = (argb and 0xff) / 255.0
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b); val l = (mx + mn) / 2
        val d = mx - mn
        val s = if (d == 0.0) 0.0 else d / (1 - Math.abs(2 * l - 1))
        val hue = when {
            d == 0.0 -> 0.0
            mx == r -> 60 * (((g - b) / d) % 6)
            mx == g -> 60 * ((b - r) / d + 2)
            else -> 60 * ((r - g) / d + 4)
        }.let { if (it < 0) it + 360 else it }
        return Triple(Math.round(hue).toInt() % 360, Math.round(s * 100).toInt(), Math.round(l * 100).toInt())
    }

    /** A [Recognition] as the debug route reports it (the uniform shape, field by field). */
    fun json(r: Recognition): JSONObject = JSONObject()
        .put("ok", r.ok).put("route", r.route).put("requested", r.requested).put("fell_back", r.fellBack).put("reason", r.reason)
        .put("labels", JSONArray().apply { r.labels.forEach { put(JSONObject().put("label", it.label).put("p", it.p)) } })
        .put("boxes", JSONArray().apply { r.boxes.forEach { put(JSONObject().put("label", it.label).put("p", it.p).put("x", it.x).put("y", it.y).put("w", it.w).put("h", it.h)) } })
        .put("count", r.boxes.size)
        .put("text", r.text)
        .put("colours", JSONArray().apply { r.colours.forEach { put(JSONObject().put("hex", it.hex).put("name", it.name).put("share", it.share)) } })
        .put("barcode", r.barcode?.let { JSONObject().put("format", it.format).put("raw", it.rawValue) } ?: JSONObject.NULL)
        .put("answers", JSONObject().apply { r.answers.forEach { (q, opts) -> put(q, JSONArray().apply { opts.forEach { put(JSONObject().put("label", it.label).put("p", it.p)) } }) } })
        .put("model", r.model).put("latency_ms", r.latencyMs).put("cost", r.cost ?: JSONObject.NULL)
        .put("width", r.width).put("height", r.height).put("error", r.error ?: JSONObject.NULL)
}
