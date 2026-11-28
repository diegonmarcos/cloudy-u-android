package com.diegonmarcos.clocktools

/**
 * #1986 the Clock's look, taken from the owner's three reference screenshots (Timer, Stopwatch, Alarm editor): black with an
 * indigo-to-purple radial glow, dark sheets and pills, a lilac accent, big round buttons and an icon island. Plain JVM so the
 * suite pins every token (ClockLookTest) and PIT mutates it; the screens (ui/ClockLookUi.kt) only turn these into Compose.
 * Colours are 0xAARRGGBB, sampled from the references; sizes are dp measured on them (1080 px wide, 2.75 px per dp).
 */
object ClockLook {
    // ── colours ───────────────────────────────────────────────────────────────────────────────
    const val BACKGROUND = 0xFF000000L
    /** The glow: indigo high in the screen, purple low, both fading to black. */
    const val GLOW_INDIGO = 0xFF0B1336L
    const val GLOW_PURPLE = 0xFF230349L
    /** Where each glow sits, as fractions of the screen (x, y) and of its height (radius). */
    val GLOW_INDIGO_AT = Triple(0.5f, 0.36f, 0.42f)
    val GLOW_PURPLE_AT = Triple(0.56f, 0.68f, 0.40f)
    /** The alarm sheet, the stopwatch disc and the round Lap button. */
    const val SHEET = 0xFF1B1B1BL
    const val LAP = 0xFF1B1B1BL
    /** The timer's preset pills and their text. */
    const val PILL = 0xFF464646L
    const val PILL_TEXT = 0xFFE7E7E7L
    /** The floating Cancel | Save pill. */
    const val BAR = 0xFF212123L
    const val BAR_TEXT = 0xFFE6E6E8L
    const val BAR_DIVIDER = 0xFF5C5C5EL
    /** The lilac accent: Start, switches on, the day circles. */
    const val ACCENT = 0xFFB5A5C2L
    const val ON_ACCENT = 0xFFFAF4FFL
    /** A Start that cannot start yet (the timer at 00:00:00). */
    const val ACCENT_DISABLED = 0xFF4E4259L
    const val ON_ACCENT_DISABLED = 0xFF6F6479L
    const val DAY = 0xFFCEC2D8L
    const val ON_DAY = 0xFF212022L
    /** Digits: the timer's and the stopwatch's lilac white, the alarm wheel's white. */
    const val DIGITS = 0xFFCDC2D7L
    const val DIGITS_WHITE = 0xFFF7F7F9L
    /** The stopwatch hand and its dot. */
    const val HAND = 0xFF8587FEL
    const val TICK_MAJOR = 0xFFD2D2D6L
    const val TICK_MINOR = 0xFF5E5E63L
    const val DISC_EDGE = 0xFF333335L
    /** Text on the dark: titles, the lilac-grey detail lines, labels, hints, lines. */
    const val TITLE = 0xFFF2F2F4L
    const val DETAIL = 0xFFBAB4C0L
    const val LABEL = 0xFFA4A2A7L
    const val LAP_TEXT = 0xFF9F9FA0L
    const val HINT = 0xFF9E9EA1L
    const val UNDERLINE = 0xFF999999L
    const val DIVIDER = 0xFF3A3A3AL
    const val SWITCH_DIVIDER = 0xFF646368L
    const val SWITCH_OFF = 0xFF5C5C60L
    const val THUMB = 0xFFF7F5F9L
    /** The icon island: its pill, the selected item's pill, the icons. */
    const val ISLAND = 0xFF252527L
    const val ISLAND_SELECTED = 0xFF363638L
    const val ISLAND_ICON = 0xFFD3D0D7L
    const val ISLAND_ICON_SELECTED = 0xFFCBC0D4L
    const val DOT_ON = 0xFF848387L
    const val DOT_OFF = 0xFF4B4A4FL
    /** The Alarm background preview's (and the ring screen's) default purple. */
    const val PREVIEW_TOP = 0xFF1D0B3AL
    const val PREVIEW_BOTTOM = 0xFF5A3F80L

    // ── sizes (dp, sp) ──────────────────────────────────────────────────────────────────────────
    const val TIMER_ROW = 89
    const val TIMER_COLUMN = 93
    const val TIMER_DIGITS_SP = 34
    const val TIMER_LABEL_SP = 10
    const val ALARM_ROW = 88
    const val ALARM_COLUMN = 163
    const val ALARM_DIGITS_SP = 40
    /** How bright a wheel's neighbours are against its middle row. */
    const val WHEEL_DIM = 0.2f
    const val PRESET_W = 72
    const val PRESET_H = 40
    const val PRESET_GAP = 14
    const val ROUND = 77
    const val ISLAND_H = 44
    const val ISLAND_ITEM_W = 49
    const val ISLAND_ITEM_H = 38
    const val SHEET_MARGIN = 8
    const val SHEET_RADIUS = 26
    const val SHEET_INSET = 15
    const val DAY_CIRCLE = 24
    const val ROW_H = 49
    const val BAR_W = 152
    const val BAR_H = 40
    /** The stopwatch dial: the tick ring's outer radius, the disc's, the tick lengths. */
    const val DIAL_RING = 149
    const val DIAL_DISC = 121
    const val TICK_LEN = 9
    const val DIAL_TEXT_SP = 28
    /** Text sizes (sp) measured on the references: preset pills, round buttons, the sheet's header / day letters / name / row titles / detail lines, Cancel | Save. */
    const val PRESET_SP = 12f
    const val BUTTON_SP = 13f
    const val HEADER_SP = 10f
    const val DAY_SP = 9f
    const val NAME_SP = 12f
    const val ROW_TITLE_SP = 12f
    const val ROW_DETAIL_SP = 9f
    const val BAR_SP = 12.5f

    /** The island's order, as the reference's (alarm, world, stopwatch, timer), then the fleet's extra pages. */
    val ISLAND_ORDER: List<String> = listOf("alarms", "world", "stopwatch", "timers", "interval", "bedtime", "pomodoro")

    /** The order the island draws [ids] in: [ISLAND_ORDER] first, anything else after, none lost or doubled. */
    fun islandOrder(ids: List<String>): List<String> = ISLAND_ORDER.filter { it in ids } + ids.filter { it !in ISLAND_ORDER }

    /** The stopwatch dial's reading, as the reference: mm:ss.cc, and h:mm:ss.cc from the hour on. */
    fun dialText(ms: Long): String {
        val v = ms.coerceAtLeast(0)
        val s = v / ClockLogic.SECOND_MS
        val cs = (v % ClockLogic.SECOND_MS) / 10
        return if (s >= 3600) "%d:%02d:%02d.%02d".format(s / 3600, (s / 60) % 60, s % 60, cs) else "%02d:%02d.%02d".format(s / 60, s % 60, cs)
    }

    /** The stopwatch hand's angle in degrees clockwise from 12: one turn a minute. */
    fun handDegrees(ms: Long): Float = (ms.coerceAtLeast(0) % 60_000L) / 60_000f * 360f

    /** Whether tick [i] of the 60 is a long, bright one (every fifth). */
    fun majorTick(i: Int): Boolean = i % 5 == 0
}

/**
 * #1986 the notification-centre card of a ring: which buttons it carries and that it opens the ring screen full screen.
 * ClockNotifications builds the Android notification from [of]; the suite pins the actions (Stop / Snooze / Dismiss for an
 * alarm, Stop / +1 min for a timer) and the mutation legs prove each one is held.
 */
object RingCard {
    /** A button: the ClockEngine.control op it sends, for which ring key, and its label (an id the Android side resolves). */
    data class Action(val op: String, val key: String, val label: String)

    data class Card(val alarm: Boolean, val fullScreen: Boolean, val ongoing: Boolean, val actions: List<Action>)

    const val STOP = "stop"
    const val SNOOZE = "snooze"
    const val DISMISS = "dismiss"
    const val PLUS_MINUTE = "plus_minute"
    const val START = "start"

    /**
     * The card of [key] (the ring on top). [snoozable] is ClockLogic.canSnooze of its alarm. Stop ends this ring, Dismiss
     * every ring at once; Snooze's label carries the time it rings again ([snoozeAt], "07:15").
     */
    fun of(key: String, snoozable: Boolean, snoozeAt: String): Card {
        val alarm = key.startsWith("alarm:")
        val acts = mutableListOf(Action("dismiss", key, STOP))
        when {
            alarm -> {
                if (snoozable) acts += Action("snooze", key, "$SNOOZE:$snoozeAt")
                acts += Action("dismiss_all", "", DISMISS)
            }
            key.startsWith("timer:") -> acts += Action("timer_add", key, PLUS_MINUTE)
            key == ClockLogic.POMODORO -> acts += Action("pomodoro_start", "", START)
            else -> acts += Action("dismiss_all", "", DISMISS)
        }
        return Card(alarm, fullScreen = true, ongoing = true, actions = acts)
    }

    /** "07:15": when a snooze of [minutes] from [nowMinuteOfDay] rings again. */
    fun snoozeAt(nowMinuteOfDay: Int, minutes: Int): String = ClockLogic.timeText(Math.floorMod(nowMinuteOfDay + minutes, 1440))
}
