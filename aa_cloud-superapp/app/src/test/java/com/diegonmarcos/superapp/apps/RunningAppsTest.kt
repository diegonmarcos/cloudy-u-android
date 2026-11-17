package com.diegonmarcos.superapp.apps

import android.app.usage.UsageEvents.Event as E
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1769 Active Apps parsing, filtering and the usage-event fallback. Pure: no device. */
class RunningAppsTest {
    private val activities = """
        ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
        Display #0 (activities from top to bottom):
          Task{a1 #12 type=standard A=10150:com.spotify.music U=0 visible=true}
            * Hist  #0: ActivityRecord{1a2b3c4 u0 com.spotify.music/.MainActivity t12}
          Task{b2 #11 type=standard A=10101:com.google.android.gm U=0 visible=false}
            * Hist  #0: ActivityRecord{5d6e7f8 u0 com.google.android.gm/.ConversationListActivity t11}
            * Hist  #1: ActivityRecord{9a0b1c2 u0 com.google.android.gm/.ComposeActivity t11}
          Task{c3 #3 type=home U=0}
            * Hist  #0: ActivityRecord{3c4d5e6 u0 com.sec.android.app.launcher/.activities.LauncherActivity t3}
          Task{d4 #2 type=standard U=0}
            * Hist  #0: ActivityRecord{7f8a9b0 u0 com.android.systemui/.RecentsActivity t2}
          Task{e5 #9 type=standard U=10 visible=false}
            * Hist  #0: ActivityRecord{abcdef0 u10 com.work.app/.Main t9}
        ResumedActivity: ActivityRecord{1a2b3c4 u0 com.spotify.music/.MainActivity t12}
    """.trimIndent()

    private val recents = """
        Recent tasks:
        * Recent #0: Task{aa #12 type=standard A=10150:com.spotify.music U=0 visible=true mode=fullscreen sz=1}
        * Recent #1: Task{bb #3 type=home A=1000:com.sec.android.app.launcher U=0 visible=false sz=1}
        * Recent #2: Task{cc #11 type=standard A=10101:com.google.android.gm U=0 visible=false sz=2}
        * Recent #3: TaskRecord{dd #7 A=com.old.style U=0 StackId=1 sz=1}
        * Recent #4: Task{ee #8 type=standard A=10222:com.work.app U=10 visible=false sz=1}
        * Recent #5: TaskRecord{ff #6 U=0 StackId=1 sz=1}
            realActivity=com.noa.name/.Main
        * Recent #6: Task{gg #5 type=standard A=10150:com.spotify.music U=0 visible=false sz=1}
    """.trimIndent()

    @Test fun activitiesParseLiveRecordsOfUserZeroTopFirstOncePerPackage() {
        assertEquals(listOf("com.spotify.music", "com.google.android.gm", "com.sec.android.app.launcher", "com.android.systemui"),
            RunningApps.parseActivities(activities))
    }

    @Test fun recentsParseDropsHomeAndOtherUsersAndKeepsOrder() {
        assertEquals(listOf("com.spotify.music", "com.google.android.gm", "com.old.style", "com.noa.name"), RunningApps.parseRecents(recents))
    }

    @Test fun filterExcludesLauncherSystemUiHomesAndDuplicates() {
        val excl = RunningApps.excluded("com.diegonmarcos.superapp", setOf("com.sec.android.app.launcher"), setOf("com.diegonmarcos.cloudmail"))
        val out = RunningApps.filter(RunningApps.parseActivities(activities) + "com.diegonmarcos.superapp" + "com.diegonmarcos.cloudmail" + "com.spotify.music", excl)
        assertEquals(listOf("com.spotify.music", "com.google.android.gm"), out)
    }

    @Test fun emptyOrGarbageDumpsGiveNoApps() {
        assertTrue(RunningApps.parseActivities("").isEmpty())
        assertTrue(RunningApps.parseRecents("Permission Denial: can't dump").isEmpty())
    }

    private fun ev(t: Int, p: String, c: String, ts: Long) = RunningApps.Ev(t, p, c, ts)

    @Test fun replayKeepsOpenActivityAndServiceDropsStopped() {
        val evs = listOf(
            ev(E.ACTIVITY_RESUMED, "a.one", "A", 100), ev(E.ACTIVITY_STOPPED, "a.one", "A", 150),
            ev(E.ACTIVITY_RESUMED, "b.two", "A", 200),
            ev(E.FOREGROUND_SERVICE_START, "c.three", "Svc", 300),
        )
        assertEquals(listOf("c.three", "b.two"), RunningApps.replayActive(evs, 0))
    }

    @Test fun replayIsPerActivityNotPerPackage() {
        // the old per-package replay dropped the app when activity A stopped although B was still open
        val evs = listOf(ev(E.ACTIVITY_RESUMED, "x.app", "A", 10), ev(E.ACTIVITY_RESUMED, "x.app", "B", 20), ev(E.ACTIVITY_STOPPED, "x.app", "A", 30))
        assertEquals(listOf("x.app"), RunningApps.replayActive(evs, 0))
    }

    @Test fun replayForgetsEverythingBeforeBootAndOnShutdown() {
        val evs = listOf(ev(E.ACTIVITY_RESUMED, "old.app", "A", 10), ev(E.DEVICE_SHUTDOWN, "android", "", 50),
            ev(E.ACTIVITY_RESUMED, "new.app", "A", 60), ev(E.ACTIVITY_RESUMED, "pre.boot", "A", 5))
        assertEquals(listOf("new.app"), RunningApps.replayActive(evs, 8))
    }

    @Test fun lastOpenedIsOrderedByLastUseDeduplicatedAndFiltered() {
        val evs = listOf(ev(E.MOVE_TO_FOREGROUND, "a", "", 10), ev(E.MOVE_TO_FOREGROUND, "b", "", 20), ev(E.MOVE_TO_FOREGROUND, "a", "", 30),
            ev(E.MOVE_TO_FOREGROUND, "com.android.systemui", "", 40), ev(E.ACTIVITY_STOPPED, "c", "", 50), ev(E.MOVE_TO_FOREGROUND, "d", "", 5))
        assertEquals(listOf("a", "b", "d"), RunningApps.lastOpened(evs, RunningApps.excluded("self", emptySet())))
    }

    @Test fun lastOpenedHoldsTwentyOneAppsWhenThereAreMore() {
        val evs = (1..30).map { ev(E.MOVE_TO_FOREGROUND, "p$it", "", it.toLong()) }
        val out = RunningApps.lastOpened(evs, emptySet())
        assertEquals(30, out.size)
        assertEquals("p30", out.first())
        assertEquals(21, out.take(21).size)
    }
}
