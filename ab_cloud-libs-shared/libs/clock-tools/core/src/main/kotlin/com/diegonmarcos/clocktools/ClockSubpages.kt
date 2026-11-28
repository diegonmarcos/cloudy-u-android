package com.diegonmarcos.clocktools

/**
 * #1964 the Clock's subpages, declared ONCE, in order. The lib's FullClock draws exactly these (Cloud Tasks' Clock), Cloud Calc's
 * Tools > Clock modes (build.json ui.modes with tab "clock") are held equal to it by Calc's DeclarationsTest, and Cloud Tasks'
 * own test pins its Clock to Cloud Calc's, so the two apps cannot drift apart.
 */
object ClockSubpages {
    val IDS: List<String> = listOf("world", "alarms", "timers", "stopwatch", "interval", "bedtime", "pomodoro")

    /**
     * #1987 Cloud Calc's Tools > Clock keeps only the World clock (an owner-named removal); Cloud Tasks and the SuperApp keep
     * every page of [IDS]. Calc's build.json clock modes are held equal to this, and Calc hands a link to any other page to
     * Cloud Tasks ([MOVED_TO_TASKS], [TASKS_PACKAGE]), or opens the World clock when Cloud Tasks is not installed.
     */
    val CALC: List<String> = listOf("world")
    val MOVED_TO_TASKS: List<String> = IDS.filter { it !in CALC }
    const val TASKS_PACKAGE = "com.diegonmarcos.cloudagenda"

    /** The subpage a notification's mode kind or a link names: the World clock's kind is worldclock, the rest are their id. */
    fun idOf(kindOrId: String?): String? = when (kindOrId) {
        null -> null
        "worldclock" -> "world"
        else -> kindOrId.takeIf { it in IDS }
    }
}
