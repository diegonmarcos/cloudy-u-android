package com.diegonmarcos.superapp.ai

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The forum/topics chat's PURE model (no Compose, no Android): it turns a session's flat message list into the
 * rows a Telegram-style thread draws — centered date-separator pills, bubbles grouped by consecutive sender —
 * and derives a user message's sent/delivered/read receipt. Tested on the JVM (AiForumTest); the Compose
 * surface (AiForumChat) only lays these rows out. Incoming = the assistant (the "Admin" / agent); outgoing = the user.
 */
object AiForum {

    /** An outgoing (user) message's delivery state, Telegram-style: one tick, two grey ticks, two accent ticks. */
    enum class Receipt { SENT, DELIVERED, READ }

    /** One rendered row of a thread. */
    sealed interface Row {
        /** A centered day pill ("Today" / "Yesterday" / "September 19"). */
        data class Separator(val label: String, val epochDay: Long) : Row
        /**
         * One message bubble. [firstInGroup]/[lastInGroup] mark the ends of a run of consecutive messages from the
         * same sender on the same day, so the surface shows the avatar + name once (at the top) and the timestamp once
         * (at the bottom), and rounds the run's outer corners.
         */
        data class Message(
            val msg: AiMsg,
            val index: Int,
            val outgoing: Boolean,
            val firstInGroup: Boolean,
            val lastInGroup: Boolean,
        ) : Row
    }

    /** A message's instant: its own [AiMsg.at], else the session's [fallback] (old messages carry no per-message time). */
    fun instant(msg: AiMsg, fallback: Long): Long = if (msg.at > 0) msg.at else fallback

    private fun day(at: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()

    private val monthDay: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM d", Locale.ROOT)

    /** The day pill's words: Today, Yesterday, or "Month day" (no year). [today]/[yesterday] are the app's localised words. */
    fun label(onDay: LocalDate, now: LocalDate, today: String, yesterday: String): String = when (onDay) {
        now -> today
        now.minusDays(1) -> yesterday
        else -> onDay.format(monthDay)
    }

    /**
     * The thread's rows: a [Row.Separator] before the first message of each day, then each message as a
     * [Row.Message] that knows whether it opens or closes a same-sender run. [fallback] (the session's updated time)
     * stands in for any message with no [AiMsg.at]; [now] drives the Today/Yesterday pills.
     */
    fun rows(
        messages: List<AiMsg>,
        zone: ZoneId,
        now: Long,
        fallback: Long,
        today: String = "Today",
        yesterday: String = "Yesterday",
    ): List<Row> {
        if (messages.isEmpty()) return emptyList()
        val nowDay = day(now, zone)
        val out = ArrayList<Row>(messages.size + 4)
        messages.forEachIndexed { i, m ->
            val d = day(instant(m, fallback), zone)
            val prev = messages.getOrNull(i - 1)
            val next = messages.getOrNull(i + 1)
            val prevDay = prev?.let { day(instant(it, fallback), zone) }
            val nextDay = next?.let { day(instant(it, fallback), zone) }
            if (prevDay == null || prevDay != d) out.add(Row.Separator(label(d, nowDay, today, yesterday), d.toEpochDay()))
            val firstInGroup = prev == null || prev.role != m.role || prevDay != d
            val lastInGroup = next == null || next.role != m.role || nextDay != d
            out.add(Row.Message(m, i, m.role == "user", firstInGroup, lastInGroup))
        }
        return out
    }

    /**
     * An outgoing message's receipt: READ once any later message exists (the assistant answered or the user sent
     * again), SENT while a send is still in flight for the last message, DELIVERED otherwise. A non-user message has none.
     */
    fun receipt(messages: List<AiMsg>, index: Int, sending: Boolean): Receipt? {
        val m = messages.getOrNull(index) ?: return null
        if (m.role != "user") return null
        val hasLater = index < messages.lastIndex
        if (hasLater) return Receipt.READ
        return if (sending) Receipt.SENT else Receipt.DELIVERED
    }

    /** A message's clock time ("14:05"), in the given zone. */
    private val hhmm: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    fun time(at: Long, zone: ZoneId): String = Instant.ofEpochMilli(at).atZone(zone).format(hhmm)
}
