package com.diegonmarcos.superapp.notificationcenter

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.util.Locale

/**
 * #1068 — what one badge's One UI live card shows, read from the providers that already exist: the battery sticky
 * intent, [MarketsLastGood] (the Markets badge's persisted quotes), [AlertStore], and — for every other badge — the
 * Notification its own service has posted ([BadgeServices.live]: title, text, big-text lines, progress). Nothing here
 * invents a number: a badge that has posted nothing says why ([BadgeServices.status]) instead of a placeholder value.
 */
object BadgeCardData {

    /** [mono]: a whole preformatted line (the AI badge's weekly block), drawn monospaced; [alert]: drawn red. */
    data class Line(val label: String, val value: String, val mono: Boolean = false, val alert: Boolean = false)

    data class Data(
        val badge: BadgeDeclaration.Badge,
        /** The small header text: the badge's label. */
        val appName: String,
        /** The glanceable value, large: "78%", "18° · 💧20%", "€1 = R$5.14". */
        val headline: String,
        val sub: String,
        /** 0..1 when the badge has a level or a share to draw, else null. */
        val progress: Float?,
        val lines: List<Line>,
        val live: Boolean,
        /** The status sentence (why a badge is not in the shade); blank when live. */
        val note: String,
        val whenMs: Long,
        val open: PendingIntent?,
        /** EVERY action the badge's notification carries (icon, label, intent), read back through [BadgeModel.of]. */
        val actions: List<BadgeModel.Action> = emptyList(),
        /** The notification's sub-text ("3/8 plugins - 1 paired - ..."), the info line under the text. */
        val subText: String = "",
        /** #1603 Samsung-UI media card: the album art the media notification carries as its large icon, else null. */
        val albumArt: android.graphics.Bitmap? = null,
    )

    /** Test seam: a JVM/Robolectric render has no running services, so it supplies the data a phone would have. */
    @Volatile var override: ((Context, BadgeDeclaration.Badge) -> Data?)? = null

    fun read(ctx: Context, b: BadgeDeclaration.Badge): Data {
        // A fixture supplies the numbers; the buttons always come from what the badge's notification carries.
        override?.invoke(ctx, b)?.let { d ->
            return BadgeServices.live(ctx, b)?.let { n -> d.copy(actions = BadgeModel.of(n)) } ?: d
        }
        val st = runCatching { BadgeServices.status(ctx, b) }.getOrNull()
        val n = BadgeServices.live(ctx, b)
        val ex = n?.extras
        val title = ex?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = ex?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText: CharSequence = ex?.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ""
        val max = ex?.getInt(Notification.EXTRA_PROGRESS_MAX, 0) ?: 0
        val posted = ex?.getInt(Notification.EXTRA_PROGRESS, 0) ?: 0
        val postedProgress = if (max > 0) (posted.toFloat() / max).coerceIn(0f, 1f) else null
        val live = n != null && (st == null || st.state == BadgeServices.State.LIVE)
        val note = if (live) "" else st?.reason.orEmpty()
        val extraLines = styledLines(bigText).filter { it.first.isNotBlank() && it.first.trim() != title && it.first.trim() != text }
            .take(maxLines(b)).map { (raw, mono, alert) ->
                // #1543 a monospaced line keeps its own spacing (the weekly block's aligned columns): no trim, no label split.
                if (mono) return@map Line("", raw.trimEnd(), mono = true, alert = alert)
                val l = raw.trim()
                val i = l.indexOf(": ")
                if (i in 1..24 && l[i - 1] != ' ') Line(l.substring(0, i), l.substring(i + 2), alert = alert) else Line("", l, alert = alert)
            }
        fun data(headline: String, sub: String, progress: Float?, lines: List<Line>) = Data(
            b, b.label, headline, sub, progress, lines, live, note, n?.`when` ?: 0L, n?.contentIntent,
            n?.let { BadgeModel.of(it) }.orEmpty(), ex?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty())
        return when (b.card.kind) {
            "level" -> {
                val lv = batteryLevel(ctx)
                data(lv?.let { "${(it.first * 100).toInt()}%" } ?: title.ifBlank { b.label },
                    title.ifBlank { text }.ifBlank { lv?.let { if (it.second) "Charging" else "On battery" }.orEmpty() },
                    lv?.first ?: postedProgress, extraLines)
            }
            "quotes" -> {
                val q = runCatching { MarketsLastGood(ctx).load() }.getOrDefault(emptyMap())
                val lines = b.instruments.map { i ->
                    val p = q[i.symbol]?.price
                    Line(i.label, if (p == null) "—" else String.format(Locale.US, "%.${i.decimals}f", p))
                }
                val newest = b.instruments.mapNotNull { q[it.symbol] }.maxOfOrNull { it.savedAtMillis.takeIf { s -> s > 0 } ?: it.asOfMillis } ?: 0L
                data(lines.firstOrNull()?.let { "${it.label} ${it.value}" } ?: b.label,
                    if (newest > 0) "updated " + ago(System.currentTimeMillis() - newest) else text.ifBlank { note }, null, lines)
            }
            "alerts" -> {
                val a = runCatching { AlertStore.visible(ctx) }.getOrDefault(emptyList())
                data(if (a.isEmpty()) "All clear" else "${a.size} alert${if (a.size == 1) "" else "s"}",
                    a.firstOrNull()?.let { it.title } ?: "Nothing raised by the fleet",
                    null, a.drop(1).take(MAX_LINES).map { Line(it.app, it.title) })
            }
            // #1603 Samsung-UI media card: track as the headline, artist/album under it, the transport progress, and the
            // album art the notification posted as its large icon (the renderer draws it dimmed behind the card).
            "media" -> data(title.ifBlank { b.label }, text, postedProgress, extraLines).copy(albumArt = mediaArt(ctx, n))
            else -> data(title.ifBlank { b.label }, text, postedProgress, extraLines)
        }
    }

    /** The album art a media notification carries as its large icon, as a Bitmap; null if it has none or it cannot be
     *  decoded. Best-effort and total: any failure drops the art and the card renders plain. */
    private fun mediaArt(ctx: Context, n: Notification?): android.graphics.Bitmap? = runCatching {
        if (n == null) return null
        (n.extras?.get(Notification.EXTRA_LARGE_ICON))?.let { v ->
            if (v is android.graphics.Bitmap) return v
            if (v is android.graphics.drawable.Icon) iconToBitmap(ctx, v)?.let { return it }
        }
        n.getLargeIcon()?.let { return iconToBitmap(ctx, it) }
        null
    }.getOrNull()

    private fun iconToBitmap(ctx: Context, icon: android.graphics.drawable.Icon): android.graphics.Bitmap? = runCatching {
        val d = icon.loadDrawable(ctx) ?: return null
        if (d is android.graphics.drawable.BitmapDrawable) return d.bitmap
        val w = d.intrinsicWidth.coerceIn(1, 1024); val h = d.intrinsicHeight.coerceIn(1, 1024)
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        d.setBounds(0, 0, w, h); d.draw(canvas)
        bmp
    }.getOrNull()

    private const val MAX_LINES = 4

    /** The big text's lines with the styling its badge put on them: (text, monospaced, red). */
    internal fun styledLines(cs: CharSequence): List<Triple<String, Boolean, Boolean>> {
        val sp = cs as? android.text.Spanned
        val out = mutableListOf<Triple<String, Boolean, Boolean>>()
        var start = 0
        while (start <= cs.length) {
            var end = start
            while (end < cs.length && cs[end] != '\n') end++
            val mono = sp?.getSpans(start, end, android.text.style.TypefaceSpan::class.java)?.any { it.family == "monospace" && end > start } == true
            val red = sp?.getSpans(start, end, android.text.style.ForegroundColorSpan::class.java)?.isNotEmpty() == true && end > start
            out += Triple(cs.subSequence(start, end).toString(), mono, red)
            if (end >= cs.length) break
            start = end + 1
        }
        return out
    }

    /** #1151 The AI badge carries seven "label: value" rows (two Claude windows, the push age, four OpenRouter figures). */
    private fun maxLines(b: BadgeDeclaration.Badge): Int = if (b.id == "ai_usage") 12 else MAX_LINES

    /** (level 0..1, charging) from the battery sticky intent, or null when the platform gives none. */
    private fun batteryLevel(ctx: Context): Pair<Float, Boolean>? = runCatching {
        val i: Intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return null
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        (level.toFloat() / scale) to (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
    }.getOrNull()

    private fun ago(ms: Long): String {
        val m = ms / 60_000L
        return when { m < 1 -> "now"; m < 60 -> "${m}m ago"; m < 1_440 -> "${m / 60}h ago"; else -> "${m / 1_440}d ago" }
    }
}
