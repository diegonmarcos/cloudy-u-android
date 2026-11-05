package com.diegonmarcos.cloudlib.mediaviewer

/** The three kinds of media this lib shows. [of] reads editor-core's route format names ("image", "audio", "video"). */
enum class MediaKind {
    Image, Audio, Video;

    companion object {
        fun of(format: String): MediaKind? = entries.firstOrNull { it.name.equals(format, ignoreCase = true) }
    }
}

/** The pure arithmetic of the viewers. */
object MediaMath {
    /** The power-of-two BitmapFactory sample size that shrinks a [width] x [height] image while its larger side stays at least [maxSide]. */
    fun sampleSize(width: Int, height: Int, maxSide: Int): Int {
        if (width <= 0 || height <= 0 || maxSide <= 0) return 1
        var size = 1
        var larger = maxOf(width, height)
        while (larger / 2 >= maxSide) { larger /= 2; size *= 2 }
        return size
    }

    /** 3725000 ms as "1:02:05", 65000 as "1:05", 5000 as "0:05". */
    fun clock(ms: Long): String {
        val total = (ms.coerceAtLeast(0) / 1000)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** The zoom after a pinch, held between 1x and [max]. */
    fun zoom(current: Float, gesture: Float, max: Float = 8f): Float = (current * gesture).coerceIn(1f, max)
}
