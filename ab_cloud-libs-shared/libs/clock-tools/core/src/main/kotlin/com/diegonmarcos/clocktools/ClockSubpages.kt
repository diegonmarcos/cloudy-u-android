package com.diegonmarcos.clocktools

/**
 * #1964 the Clock's subpages, declared ONCE, in order. The lib's FullClock draws exactly these (Cloud Tasks' Clock), Cloud Calc's
 * Tools > Clock modes (build.json ui.modes with tab "clock") are held equal to it by Calc's DeclarationsTest, and Cloud Tasks'
 * own test pins its Clock to Cloud Calc's, so the two apps cannot drift apart.
 */
object ClockSubpages {
    val IDS: List<String> = listOf("world", "alarms", "timers", "stopwatch", "interval", "bedtime")
}
