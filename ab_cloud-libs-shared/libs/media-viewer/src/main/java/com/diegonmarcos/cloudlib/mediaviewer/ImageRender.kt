package com.diegonmarcos.cloudlib.mediaviewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.media.ExifInterface
import android.os.Build
import java.io.File

/** #1537 the Android side of the image viewer and editor: decode (BitmapFactory, ImageDecoder for what it cannot), EXIF orientation, turns, and the save. */
internal object ImageRender {
    const val FULL_MAX_SIDE = 8192

    fun decode(path: String, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(path, bounds) }
        var bmp: Bitmap? = null
        if (bounds.outWidth > 0) {
            val opts = BitmapFactory.Options().apply { inSampleSize = MediaMath.sampleSize(bounds.outWidth, bounds.outHeight, maxSide) }
            bmp = runCatching { BitmapFactory.decodeFile(path, opts) }.getOrNull()
        }
        // HEIC and AVIF where BitmapFactory has no decoder (API 28+ / 31+): the platform ImageDecoder.
        if (bmp == null && Build.VERSION.SDK_INT >= 28) {
            bmp = runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(File(path))) { dec, info, _ ->
                    val s = MediaMath.sampleSize(info.size.width, info.size.height, maxSide)
                    if (s > 1) dec.setTargetSampleSize(s)
                }
            }.getOrNull()
        }
        return bmp
    }

    fun orientationTag(path: String): Int = runCatching { ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)

    /** The picture upright (EXIF orientation applied) and then turned [turns] clockwise quarter turns. */
    fun transform(src: Bitmap, exifTag: Int, turns: Int): Bitmap {
        val (deg, mirror) = ImageEditMath.orientation(exifTag)
        val total = deg + Math.floorMod(turns, 4) * 90
        if (total % 360 == 0 && !mirror) return src
        val m = Matrix()
        m.postRotate((deg).toFloat())
        if (mirror) m.postScale(-1f, 1f)
        m.postRotate((Math.floorMod(turns, 4) * 90).toFloat())
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    fun base(path: String, turns: Int, maxSide: Int): Bitmap? = decode(path, maxSide)?.let { transform(it, orientationTag(path), turns) }

    /** What the Info panel shows: the file facts and the EXIF the file carries. */
    fun info(path: String): List<Pair<String, String>> {
        val f = File(path)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(path, bounds) }
        val values = runCatching {
            val e = ExifInterface(path)
            val ll = FloatArray(2)
            val hasGps = e.getLatLong(ll)
            ExifValues(
                make = e.getAttribute(ExifInterface.TAG_MAKE), model = e.getAttribute(ExifInterface.TAG_MODEL),
                dateTime = e.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: e.getAttribute(ExifInterface.TAG_DATETIME),
                exposureSeconds = e.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0).takeIf { it > 0.0 },
                fNumber = e.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0).takeIf { it > 0.0 },
                iso = e.getAttributeInt("ISOSpeedRatings", 0).takeIf { it > 0 },
                focalMm = e.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0).takeIf { it > 0.0 },
                latitude = if (hasGps) ll[0].toDouble() else null, longitude = if (hasGps) ll[1].toDouble() else null,
            )
        }.getOrDefault(ExifValues())
        return ExifFormat.rows(f.name, f.length(), bounds.outWidth, bounds.outHeight, values)
    }

    private val COPY_TAGS = listOf(
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
    )

    /**
     * The edit, at full size (up to [FULL_MAX_SIDE]): upright, turned, annotated, cropped, encoded, and written next to the original
     * through a temp file ([ImageEditMath.savePlan] decides the name and format). A JPEG keeps the camera's EXIF with the orientation reset.
     */
    @Suppress("DEPRECATION")
    fun save(path: String, turns: Int, crop: CropFractions, strokes: List<Stroke>): Result<File> = runCatching {
        // the camera tags are read BEFORE the original is replaced
        val keep: Map<String, String> = runCatching { val from = ExifInterface(path); COPY_TAGS.mapNotNull { tag -> from.getAttribute(tag)?.let { tag to it } }.toMap() }.getOrDefault(emptyMap())
        val src = decode(path, FULL_MAX_SIDE) ?: error("cannot decode $path")
        val upright = transform(src, orientationTag(path), turns)
        val canvasBmp = upright.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(canvasBmp)
        val w = canvasBmp.width.toFloat()
        val h = canvasBmp.height.toFloat()
        val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            strokeWidth = maxOf(2f, minOf(w, h) * ImageEditMath.PEN_FRACTION)
        }
        for (s in strokes) {
            if (s.points.isEmpty()) continue
            pen.color = s.color
            if (s.points.size == 1) {
                pen.style = Paint.Style.FILL; canvas.drawCircle(s.points[0].x * w, s.points[0].y * h, pen.strokeWidth / 2f, pen); pen.style = Paint.Style.STROKE
            } else {
                val path2 = Path()
                s.points.forEachIndexed { i, p -> if (i == 0) path2.moveTo(p.x * w, p.y * h) else path2.lineTo(p.x * w, p.y * h) }
                canvas.drawPath(path2, pen)
            }
        }
        val r = ImageEditMath.cropRect(canvasBmp.width, canvasBmp.height, crop)
        val out = Bitmap.createBitmap(canvasBmp, r[0], r[1], r[2], r[3])
        val original = File(path)
        val plan = ImageEditMath.savePlan(original.name)
        val target = File(original.absoluteFile.parentFile, plan.outName)
        val tmp = File(target.parentFile, ".${target.name}.tmp")
        val format = when (plan.format) {
            "jpeg" -> Bitmap.CompressFormat.JPEG
            "webp" -> if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP
            else -> Bitmap.CompressFormat.PNG
        }
        tmp.outputStream().use { check(out.compress(format, 92, it)) { "encoding failed" } }
        if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
        if (plan.format == "jpeg") runCatching {
            val to = ExifInterface(target.absolutePath)
            for ((tag, value) in keep) to.setAttribute(tag, value)
            to.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            to.saveAttributes()
        }
        target
    }
}
