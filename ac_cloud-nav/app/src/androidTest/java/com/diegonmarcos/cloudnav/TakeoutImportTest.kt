package com.diegonmarcos.cloudnav

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.diegonmarcos.cloudnav.maps.MapsDb
import com.diegonmarcos.cloudnav.maps.takeout.ImportController
import com.diegonmarcos.cloudnav.maps.takeout.ImportState
import com.diegonmarcos.cloudnav.tracker.AggregatesStore
import com.diegonmarcos.cloudnav.tracker.DayFragment
import com.diegonmarcos.cloudnav.tracker.DayTab
import com.diegonmarcos.cloudnav.tracker.DayTags
import com.diegonmarcos.cloudnav.tracker.ImportTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * #1853 Google Takeout import on the emulator. A synthetic Takeout zip (invented places and ids, dates relative to today) is written to the
 * app's cache, handed to the Explored > Day screen's import entry (the function the system picker's result calls: the picker is another
 * app's UI and cannot be driven in CI; the buttons that open it are asserted present), imported, and the test asserts the store (visits,
 * activities, raw positions, segments, rollups, aggregates), the Import summary, and the Day, Places, Cities and World pages over it. A second
 * import of the same zip must change nothing. Evidence: /data/local/tmp/takeout-import.png.
 */
@RunWith(AndroidJUnit4::class)
class TakeoutImportTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val zone = ZoneId.systemDefault()

    private fun iso(d: LocalDate, h: Int, m: Int = 0) = d.atTime(h, m).atZone(zone).toInstant().toString()
    private fun e7(v: Double) = Math.round(v * 1e7)

    private fun zip(file: File, yesterday: LocalDate, before: LocalDate) {
        val home = 52.5163 to 13.3777; val work = 52.5219 to 13.4132
        fun loc(p: Pair<Double, Double>, id: String, name: String, addr: String) =
            """{"latitudeE7":${e7(p.first)},"longitudeE7":${e7(p.second)},"placeId":"$id","address":"$addr","name":"$name","semanticType":"TYPE_UNKNOWN","locationConfidence":90}"""
        val semantic = """{"timelineObjects":[
          {"placeVisit":{"location":${loc(home, "ChIJ-ANDROIDTEST-HOME", "Walk Test Home", "Test Strasse 1, 10117 Berlin, Germany")},"duration":{"startTimestamp":"${iso(yesterday, 8)}","endTimestamp":"${iso(yesterday, 12)}"},"placeConfidence":"HIGH_CONFIDENCE","visitConfidence":80}},
          {"activitySegment":{"startLocation":{"latitudeE7":${e7(home.first)},"longitudeE7":${e7(home.second)}},"endLocation":{"latitudeE7":${e7(work.first)},"longitudeE7":${e7(work.second)}},"duration":{"startTimestamp":"${iso(yesterday, 12)}","endTimestamp":"${iso(yesterday, 12, 30)}"},"distance":3100,"activityType":"IN_TRAIN","confidence":"HIGH","activities":[{"activityType":"IN_TRAIN","probability":80.5}]}},
          {"placeVisit":{"location":${loc(work, "ChIJ-ANDROIDTEST-WORK", "Walk Test Office", "Test Platz 2, 10178 Berlin, Germany")},"duration":{"startTimestamp":"${iso(yesterday, 12, 30)}","endTimestamp":"${iso(yesterday, 18)}"},"placeConfidence":"HIGH_CONFIDENCE","visitConfidence":85}}]}"""
        val records = StringBuilder("""{"locations":[""")
        var first = true
        fun fix(sec: Int, p: Pair<Double, Double>) {   // [sec] = seconds after 08:00 of the day before yesterday
            if (!first) records.append(','); first = false
            val t = before.atTime(8, 0).plusSeconds(sec.toLong()).atZone(zone).toInstant().toString()
            records.append("""{"timestamp":"$t","latitudeE7":${e7(p.first)},"longitudeE7":${e7(p.second)},"accuracy":15,"source":"WIFI","deviceTag":-42}""")
        }
        for (k in 0..6) fix(k * 600, home)                                                    // 08:00-09:00 at home
        for (k in 1..9) fix(3600 + k * 30, home.first + (work.first - home.first) * k / 10 to home.second + (work.second - home.second) * k / 10)   // the way, 09:00:30-09:04:30
        for (k in 0..8) fix(3900 + k * 1200, work)                                            // 09:05-11:45 at the office
        records.append("]}")
        ZipOutputStream(file.outputStream()).use { z ->
            fun put(n: String, s: String) { z.putNextEntry(ZipEntry(n)); z.write(s.toByteArray()); z.closeEntry() }
            put("Takeout/Location History (Timeline)/Semantic Location History/${yesterday.year}/${yesterday.year}_${yesterday.month.name}.json", semantic)
            put("Takeout/Location History (Timeline)/Records.json", records.toString())
            put("Takeout/Location History (Timeline)/Settings.json", """{"deviceSettings":[]}""")
        }
    }

    /** compose.waitUntil that says WHICH wait timed out and what the import was doing. */
    private fun await(what: String, ms: Long, cond: () -> Boolean) {
        try { compose.waitUntil(ms, cond) } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            val st = ImportController.state
            runCatching {
                val tree = compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 12)
                tree.chunked(3000).forEachIndexed { i, c -> android.util.Log.e("TakeoutImportTest", "tree[$i] $c") }
                val segs = MapsDb.get(InstrumentationRegistry.getInstrumentation().targetContext).segmentsBetween(0L, Long.MAX_VALUE)
                android.util.Log.e("TakeoutImportTest", "segments: " + segs.joinToString { "${it.kind}:${it.place}:${java.time.Instant.ofEpochMilli(it.startMs)}:${it.source}" })
            }
            throw AssertionError("timed out at $what; import state=" + (if (st is ImportState.Failed) "failed: " + st.message else st::class.simpleName), e)
        }
    }

    private fun shot(name: String) {
        Thread.sleep(1_000)
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /data/local/tmp/$name.png")
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }

    private fun counts(db: MapsDb): List<Long> = listOf("imp_visits", "imp_activities", "imp_candidates", "imp_signals", "imp_profile", "fixes", "timeline_segments").map {
        db.sqlExec().rows("SELECT COUNT(*) FROM $it;")[0][0] as Long
    }

    @Test fun a_takeout_zip_imports_through_the_day_screen_fills_the_store_and_is_idempotent() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val db = MapsDb.get(ctx)
        db.clearAll(); ImportController.reset()
        val yesterday = LocalDate.now(zone).minusDays(1); val before = yesterday.minusDays(1)
        val file = File(ctx.cacheDir, "takeout-fixture.zip").also { zip(it, yesterday, before) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                await("wait@95+9", 30_000) { compose.onAllNodesWithTag("bottomnav_item_tracker").fetchSemanticsNodes().isNotEmpty() }
                var shellPage = false
                await("wait@97+9", 30_000) { scenario.onActivity { act -> act.supportFragmentManager.executePendingTransactions(); shellPage = act.supportFragmentManager.fragments.isNotEmpty() }; shellPage }
                val day = DayFragment()
                scenario.onActivity { act -> act.supportFragmentManager.beginTransaction().replace(R.id.nav_host_main, day).commitNow() }

                // the import tab with the buttons that open the system pickers (zip or file, folder)
                await("wait@102+9", 30_000) { compose.onAllNodesWithTag(DayTags.tab(DayTab.IMPORT)).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag(DayTags.tab(DayTab.IMPORT)).performScrollTo().performClick()
                await("wait@104+9", 30_000) { compose.onAllNodesWithTag(ImportTags.FILE).fetchSemanticsNodes().isNotEmpty() }
                assertTrue("the folder picker button is there", compose.onAllNodesWithTag(ImportTags.FOLDER).fetchSemanticsNodes().isNotEmpty())

                // the picker's result: the zip's Uri goes to the screen's import entry
                scenario.onActivity { day.importFrom(Uri.fromFile(file)) }
                await("wait@109+9", 120_000) { ImportController.state is ImportState.Done || ImportController.state is ImportState.Failed }
                val done = ImportController.state
                assertTrue("import ended well: " + ((done as? ImportState.Failed)?.message ?: ""), done is ImportState.Done)
                val sum = (done as ImportState.Done).summary
                assertEquals("visits", 2L, sum.visits); assertEquals("activities", 1L, sum.activities); assertEquals("places", 2, sum.places)
                assertEquals("raw positions", 7L + 9L + 9L, sum.fixes); assertEquals("days", 2, sum.days); assertTrue(sum.failedFiles.isEmpty())
                await("wait@115+9", 30_000) { compose.onAllNodesWithTag(ImportTags.SUMMARY).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag(ImportTags.SUMMARY).assertExists()
                shot("takeout-import")

                // the store
                val from = before.atStartOfDay(zone).toInstant().toEpochMilli(); val to = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
                val segs = db.segmentsBetween(from, to)
                assertTrue("Google's visits are segments with their names: " + segs.map { it.place }, segs.any { it.place == "Walk Test Office" } && segs.any { it.place == "Walk Test Home" })
                assertTrue("the train activity is a move with its distance", segs.any { it.kind == com.diegonmarcos.cloudnav.maps.TlKind.MOVE && it.distanceM == 3100.0 && it.locSource == "mode:TRAIN" })
                assertTrue("the raw positions of the day before became stays and moves", segs.count { it.startMs < to - 86_400_000L && it.source == "takeout-records" } >= 2)
                assertTrue("rollups exist for the imported days", db.rollups(before.toString(), yesterday.toString()).size == 2)
                assertTrue("the world aggregate holds Germany", (db.aggregate("world", AggregatesStore.KEY_MONTH)?.first ?: "").contains("Germany"))
                assertTrue("every Google field is kept: the activity probabilities", db.sqlExec().rows("SELECT probabilities_json FROM imp_activities")[0][0].toString().contains("80.5"))
                val before1 = counts(db)

                // the pages over it: Day (yesterday), Places, Cities, World
                compose.onNodeWithTag(DayTags.tab(DayTab.DAY)).performScrollTo().performClick()
                await("wait@132+9", 30_000) { compose.onAllNodesWithTag(DayTags.PREV).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag(DayTags.PREV).performClick()
                await("wait@134+9", 30_000) { compose.onAllNodesWithTag(DayTags.LIST).fetchSemanticsNodes().isNotEmpty() }
                await("wait@135+9", 30_000) { runCatching { compose.onNodeWithTag(DayTags.LIST).performScrollToNode(hasText("Walk Test Office", substring = true)) }.isSuccess }
                await("wait@136+9", 30_000) { compose.onAllNodesWithText("Walk Test Office", substring = true).fetchSemanticsNodes().isNotEmpty() }
                shot("takeout-day")
                compose.onNodeWithTag(DayTags.tab(DayTab.PLACES)).performScrollTo().performClick()
                await("wait@139+9", 30_000) { compose.onAllNodesWithText("Walk Test Office", substring = true).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag(DayTags.tab(DayTab.CITIES)).performScrollTo().performClick()
                await("wait@141+9", 30_000) { compose.onAllNodesWithText("Berlin", substring = true).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag(DayTags.tab(DayTab.WORLD)).performScrollTo().performClick()
                await("wait@143+9", 30_000) { compose.onAllNodesWithText("Germany", substring = true).fetchSemanticsNodes().isNotEmpty() }

                // the same zip again: nothing doubles
                scenario.onActivity { day.importFrom(Uri.fromFile(file)) }
                await("wait@147+9", 120_000) { ImportController.state is ImportState.Done || ImportController.state is ImportState.Failed }
                assertTrue(ImportController.state is ImportState.Done)
                assertEquals("a re-import changes no row count", before1, counts(db))
            }
        } finally {
            db.clearAll(); ImportController.reset(); file.delete()
        }
    }
}
