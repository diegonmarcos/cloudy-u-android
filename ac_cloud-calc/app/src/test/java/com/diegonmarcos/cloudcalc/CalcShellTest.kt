package com.diegonmarcos.cloudcalc

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.diegonmarcos.cloudcalc.engine.CalcApi
import com.diegonmarcos.cloudcalc.ui.CalcShell
import com.diegonmarcos.cloudcalc.ui.CalcState
import com.diegonmarcos.cloudcalc.ui.CalcTags
import com.diegonmarcos.cloudcalc.ui.CalcTheme
import com.diegonmarcos.superapp.bottomnav.BottomNavTags
import com.diegonmarcos.superapp.bottomnav.PageTabsTags
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The UI smoke test PER MODE: the real shell composed under Robolectric (the DriveShellTest
 * precedent) against a fake engine, reaching every declared mode through the bottom nav and its
 * chip strip. Every id comes from the declaration, none is restated here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CalcShellTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
                base.evaluate()
            }
        }
    }).around(compose)

    /** Answers like the engine does, and records what it was asked. */
    private class FakeEngine : CalcApi {
        val evals = mutableListOf<String>()
        override fun info() = """{"ok":true,"version":"fake"}"""
        override fun eval(expr: String, options: String): String {
            synchronized(evals) { evals += expr }
            // Like the engine: unless the mode asks for always-decimal (approx 2), a non-integer quotient prints as the exact fraction.
            val decimal = runCatching { JSONObject(options).optInt("approx", 1) }.getOrDefault(1) >= 2
            return JSONObject().put("ok", true).put("result", when (expr) {
                "500/3" -> if (decimal) "166.6666667" else "500/3"
                "(1) EUR to USD" -> "1.2 USD"; "(1) EUR to BRL" -> "6 BRL"; "(1) EUR to GBP" -> "0.85 GBP"; "(1) EUR to JPY" -> "170 JPY"; "(1) EUR to CNY" -> "8.5 CNY"
                "7.5/2" -> "3.75"; "-6/4" -> "-1.5"; "100/4/5" -> "5"; "500/(2+3)" -> "100" "2+2" -> "4"; "600/3" -> "200"; "600/4" -> "150"; "(2+2" -> "-3"; else -> "42" }).put("messages", org.json.JSONArray()).toString()
        }
        override fun plot(expr: String, xmin: Double, xmax: Double, steps: Int) = """{"ok":true,"x":[0,1,2],"y":[0,1,4]}"""
        override fun complete(prefix: String, max: Int) = """[{"name":"sqrt","title":"Square Root","kind":"function","category":"c"}]"""
        override fun items(kind: String, category: String, max: Int) =
            """[{"name":"m","title":"Meter","kind":"unit","category":"$category"},{"name":"ft","title":"Foot","kind":"unit","category":"$category"}]"""
        @Volatile var time = 1783296000L
        @Volatile var fetchedTime = 1783296000L
        @Volatile var fetchOk = true
        @Volatile var fetches = 0
        override fun ratesInfo() = """{"sources":[],"time":$time}"""
        override fun fetchRates(): String {
            fetches++
            if (fetchOk) time = fetchedTime
            return if (fetchOk) """{"ok":true,"fetched":[],"failed":[],"time":$time}""" else """{"ok":false,"error":"offline","fetched":[],"failed":[],"time":$time}"""
        }
    }

    private val engine = FakeEngine()
    private val state = CalcState(null)

    // #770 the Jev screens compose in the smoke test: no real network and no real Account binder.
    private lateinit var savedHttp: com.diegonmarcos.superapp.decisions.Http
    private lateinit var savedAccount: (android.content.Context, String) -> Pair<String?, String>

    @org.junit.Before fun offline() {
        com.diegonmarcos.cloudcalc.Fx.lastAttemptMs = 0L
        savedHttp = com.diegonmarcos.cloudcalc.decide.JevStore.http
        savedAccount = com.diegonmarcos.cloudcalc.decide.JevStore.account
        com.diegonmarcos.cloudcalc.decide.JevStore.http = object : com.diegonmarcos.superapp.decisions.Http {
            override fun send(url: String, token: String?, body: String?, timeoutMs: Int) = com.diegonmarcos.superapp.decisions.Http.Response(503, "{}")
        }
        com.diegonmarcos.cloudcalc.decide.JevStore.account = { _, _ -> null to "none" }
    }

    @org.junit.After fun online() {
        com.diegonmarcos.cloudcalc.decide.JevStore.http = savedHttp
        com.diegonmarcos.cloudcalc.decide.JevStore.account = savedAccount
    }

    private fun launch() = compose.setContent { CalcTheme { CalcShell(engine, state) } }

    @Test fun `every declared mode is reachable from the nav and composes`() {
        launch()
        Declarations.tabs.forEach { tab ->
            // #868 a page is in the top strip only while its section (a bottom-nav item) is selected;
            // the Configs page has no bar item: the top bar's Settings > Configs opens it.
            if (Declarations.sections.any { it.id == tab.section }) compose.onNodeWithTag(BottomNavTags.item(tab.section)).performClick()
            else compose.runOnIdle { state.tab = tab.id }
            compose.waitForIdle()
            // The pill is declared and composed (a section with one page draws no strip); a strip that scrolls
            // may park it off-screen, where a click has no pixel to land on, so the page is selected
            // through the state the pills write.
            if (Declarations.tabsOf(tab.section).size > 1) compose.onNodeWithTag(PageTabsTags.tab(tab.id)).assertExists()
            compose.runOnIdle { state.tab = tab.id }
            compose.waitForIdle()
            compose.onNodeWithTag(CalcTags.tab(tab.id)).assertExists()
            val modes = Declarations.modesOf(tab.id)
            if (modes.size > 1) {
                // The sub-strip scrolls: a pill scrolled out of view may not be composed, so the
                // first is clicked and the rest are selected through the state the chips write.
                compose.onNodeWithTag(PageTabsTags.tab(modes.first().id)).performClick()
            }
            modes.forEach { m ->
                compose.runOnIdle { state.modeByTab[tab.id] = m.id }
                compose.waitForIdle()
                compose.onNodeWithTag(CalcTags.mode(m.id)).assertExists()
            }
        }
    }

    @Test fun `each section shows only its own tabs and comes back to the last one`() {
        launch()
        Declarations.sections.forEach { sec ->
            compose.onNodeWithTag(BottomNavTags.item(sec.id)).performClick()
            compose.waitForIdle()
            assertEquals(sec.id, state.section)
            Declarations.tabs.forEach { t ->
                val shown = compose.onAllNodesWithTag(PageTabsTags.tab(t.id)).fetchSemanticsNodes().isNotEmpty()
                assertEquals("tab ${t.id} in section ${sec.id}", t.section == sec.id && Declarations.tabsOf(sec.id).size > 1, shown)
            }
        }
        val two = Declarations.sections.first { Declarations.tabsOf(it.id).size > 1 }
        val second = Declarations.tabsOf(two.id)[1]
        compose.runOnIdle { state.showSection(two.id); state.tab = second.id }
        val other = Declarations.sections.first { it.id != two.id }
        compose.runOnIdle { state.showSection(other.id) }
        compose.runOnIdle { state.showSection(two.id) }
        assertEquals(second.id, state.tab)
    }

    @Test fun `keys type, the engine answers, and = keeps the result in history`() {
        launch()
        val mode = Declarations.modes.first { it.kind == "expression" }
        compose.runOnIdle { state.tab = mode.tab; state.modeByTab[mode.tab] = mode.id }
        listOf("2", "+", "2").forEach { compose.onNodeWithTag(CalcTags.key(it)).performClick() }
        compose.onNodeWithTag(CalcTags.INPUT).assertTextContains("2+2")
        // The live result is debounced per keystroke, so the first answer on screen can belong
        // to an intermediate input ("2" is answered with 42 by the fake): wait for THE answer.
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(CalcTags.RESULT).fetchSemanticsNodes().isNotEmpty() &&
                runCatching { compose.onNodeWithTag(CalcTags.RESULT).assertTextContains("= 4") }.isSuccess
        }
        compose.onNodeWithTag(CalcTags.key("=")).performClick()
        compose.waitForIdle()
        val kept = state.history.first()
        assertEquals(Logic.Entry(mode.id, "2+2", "4").copy(ts = kept.ts), kept)
        assertTrue(kept.ts > 0)
        assertTrue(synchronized(engine.evals) { "2+2" in engine.evals })
    }

    @Test fun `the Alarms screen shows the stored alarm and its switch cancels the wakeup`() {
        val app = RuntimeEnvironment.getApplication()
        val id = com.diegonmarcos.clocktools.ClockEngine.saveAlarm(app, com.diegonmarcos.clocktools.Alarm(0, 6 * 60 + 45))
        assertTrue(com.diegonmarcos.clocktools.ClockEngine.isScheduled(app, "alarm:$id"))
        launch()
        val alarms = Declarations.modes.first { it.kind == "alarms" }
        compose.runOnIdle { state.tab = alarms.tab; state.modeByTab[alarms.tab] = alarms.id }
        compose.waitForIdle()
        compose.onNodeWithText("06:45").assertExists()
        // Switching it off is the screen's own write: store, then AlarmManager.
        compose.onNode(isToggleable()).performClick()
        compose.waitForIdle()
        assertFalse(com.diegonmarcos.clocktools.ClockEngine.load(app).alarms.single().enabled)
        assertFalse(com.diegonmarcos.clocktools.ClockEngine.isScheduled(app, "alarm:$id"))
    }

    private fun typeKeys(vararg keys: String) = keys.forEach { compose.onNodeWithTag(CalcTags.key(it)).performClick(); compose.waitForIdle() }
    private fun select(start: Int, end: Int = start) {
        compose.onNodeWithTag(CalcTags.INPUT).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag(CalcTags.INPUT).performSemanticsAction(SemanticsActions.SetSelection) { it(start, end, true) }
        compose.waitForIdle()
    }
    private fun resultIs(text: String) = compose.waitUntil(5_000) {
        compose.onAllNodesWithTag(CalcTags.RESULT).fetchSemanticsNodes().isNotEmpty() &&
            runCatching { compose.onNodeWithTag(CalcTags.RESULT).assertTextContains("= $text") }.isSuccess
    }
    private fun openStandard() {
        launch()
        val mode = Declarations.modes.first { it.kind == "expression" }
        compose.runOnIdle { state.tab = mode.tab; state.modeByTab[mode.tab] = mode.id }
        compose.waitForIdle()
    }

    @Test fun `a key inserts at the cursor, a selection is replaced, and an edit after a result is evaluated afresh`() {
        openStandard()
        typeKeys("6", "0", "0", "/", "3")
        resultIs("200")
        // The cursor to the very start: "(" goes there, not to the end.
        select(0)
        typeKeys("( )")
        compose.onNodeWithTag(CalcTags.INPUT).assertTextContains("(600/3")
        // DEL deletes before the cursor (here: nothing is left of the "("), then after the "(" it eats the "(".
        select(1)
        typeKeys("DEL")
        compose.onNodeWithTag(CalcTags.INPUT).assertTextContains("600/3")
        resultIs("200")
        // Select the 3 and type 4: the result is the value of 600/4, not an echo of the old text.
        select(4, 5)
        typeKeys("4")
        compose.onNodeWithTag(CalcTags.INPUT).assertTextContains("600/4")
        resultIs("150")
        // = keeps it, and the field holds the result; editing that again re-evaluates.
        typeKeys("=")
        compose.onNodeWithTag(CalcTags.INPUT).assertTextContains("150")
        assertEquals("150", state.history.first().result)
    }

    @Test fun `500 over 3 reads as a decimal in the calculators, never as the input echoed`() {
        listOf("standard", "scientific", "programmer").forEach { id ->
            assertEquals("$id must print always-decimal", 2, Logic.optionValue(Declarations.mode(id)!!.options, "approx", -1))
        }
        (Declarations.modes.filter { it.id.startsWith("unit_") }.map { it.id } + listOf("currency", "physics")).forEach { id ->
            assertEquals("$id must print always-decimal", 2, Logic.optionValue(Declarations.mode(id)!!.options, "approx", -1))
        }
        openStandard()
        typeKeys("5", "0", "0", "/", "3")
        resultIs("166.6666667")
        compose.onNodeWithTag(CalcTags.INPUT).assertTextContains("500/3")
        typeKeys("=")
        // The History entry holds the decimal too, and the field now holds the number.
        assertEquals("166.6666667", state.history.first().result)
        assertEquals("500/3", state.history.first().expr)
    }

    @Test fun `every division path reaches the engine as a slash and reads as the number`() {
        openStandard()
        val cases = listOf("500/3" to "166.6666667", "500÷3" to "166.6666667", "500∕3" to "166.6666667", "500／3" to "166.6666667",
            "7.5/2" to "3.75", "-6/4" to "-1.5", "100/4/5" to "5", "500/(2+3)" to "100", "500÷(2+3)" to "100")
        cases.forEach { (typed, shown) ->
            // The soft keyboard / a paste: the whole text replaced.
            compose.onNodeWithTag(CalcTags.INPUT).performTextReplacement(typed)
            compose.waitForIdle()
            resultIs(shown)
            val seen = synchronized(engine.evals) { engine.evals.toList() }
            assertTrue("$typed reached the engine as ${Logic.normalize(typed)}", Logic.normalize(typed) in seen)
            if (typed != Logic.normalize(typed)) assertFalse("$typed reached the engine unnormalised", typed in seen)
        }
        // The keypad's / key.
        typeKeys("AC", "5", "0", "0", "/", "3")
        resultIs("166.6666667")
        // A reused history entry carrying the glyph.
        val mode = Declarations.modes.first { it.kind == "expression" }
        typeKeys("AC")
        compose.runOnIdle { state.send(mode.id, "7.5÷2") }
        compose.waitForIdle()
        compose.onNodeWithTag(CalcTags.INPUT).assertTextContains("7.5÷2")
        resultIs("3.75")
    }

    @Test fun `a history reuse lands at the cursor and the result follows the edited text`() {
        openStandard()
        val mode = Declarations.modes.first { it.kind == "expression" }
        typeKeys("2", "+", "2")
        resultIs("4")
        select(0)
        compose.runOnIdle { state.send(mode.id, "600/3") }
        compose.waitForIdle()
        compose.onNodeWithTag(CalcTags.INPUT).assertTextContains("600/32+2")
    }

    @Test fun `the mode row is icons with short labels and switching a mode keeps the keypad where it was`() {
        openStandard()
        val calc = Declarations.modes.first { it.kind == "expression" }
        val rest = compose.onNodeWithTag(CalcTags.key("=")).getBoundsInRoot().top
        val sci = Declarations.modes.first { it.id == "scientific" }
        compose.onNodeWithTag(com.diegonmarcos.superapp.bottomnav.PageTabsTags.tab(sci.id)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(CalcTags.mode(sci.id)).assertExists()
        compose.onNodeWithText(sci.short).assertExists()
        compose.onNodeWithTag(com.diegonmarcos.superapp.bottomnav.PageTabsTags.tab(calc.id)).performClick()
        compose.waitForIdle()
        assertEquals(rest, compose.onNodeWithTag(CalcTags.key("=")).getBoundsInRoot().top)
    }

    @Test fun `the keypad does not move while a result appears, changes and goes`() {
        launch()
        val mode = Declarations.modes.first { it.kind == "expression" }
        compose.runOnIdle { state.tab = mode.tab; state.modeByTab[mode.tab] = mode.id }
        compose.waitForIdle()
        fun top() = compose.onNodeWithTag(CalcTags.key("=")).getBoundsInRoot().top
        val rest = top()
        val display = compose.onNodeWithTag(CalcTags.DISPLAY).getBoundsInRoot().let { it.bottom - it.top }
        listOf("2", "+", "2").forEach { compose.onNodeWithTag(CalcTags.key(it)).performClick(); compose.waitForIdle(); assertEquals(rest, top()) }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(CalcTags.RESULT).fetchSemanticsNodes().isNotEmpty() &&
                runCatching { compose.onNodeWithTag(CalcTags.RESULT).assertTextContains("= 4") }.isSuccess
        }
        assertEquals(rest, top())
        assertEquals(display, compose.onNodeWithTag(CalcTags.DISPLAY).getBoundsInRoot().let { it.bottom - it.top })
        assertEquals(0, compose.onAllNodesWithTag(CalcTags.ASK_TOGGLE).fetchSemanticsNodes().size)
    }

    @Test fun `history keeps each press with its time, reuses expression or result, deletes one, clears all`() {
        val mode = Declarations.modes.first { it.kind == "expression" }
        var t = 1_000L
        val s = CalcState(null) { t++ }
        s.remember(Logic.Entry(mode.id, "1+1", "2"), 10)
        s.remember(Logic.Entry(mode.id, "1+1", "2"), 10)
        s.remember(Logic.Entry(mode.id, "6*7", "42"), 10)
        assertEquals(listOf(1_002L, 1_001L, 1_000L), s.history.map { it.ts })
        s.deleteHistory(1)
        assertEquals(listOf("6*7", "1+1"), s.history.map { it.expr })
        s.deleteHistory(9)
        assertEquals(2, s.history.size)
        s.clearHistory()
        assertTrue(s.history.isEmpty())
        // The screen: a tap on the expression and a tap on the result each reuse theirs.
        state.remember(Logic.Entry(mode.id, "6*7", "42"), 10)
        launch()
        val history = Declarations.modes.first { it.kind == "history" }
        compose.runOnIdle { state.tab = history.tab; state.modeByTab[history.tab] = history.id }
        compose.waitForIdle()
        compose.onNodeWithTag(CalcTags.historyExpr(0)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(CalcTags.INPUT).assertTextContains("6*7")
        compose.runOnIdle { state.tab = history.tab }
        compose.waitForIdle()
        compose.onNodeWithTag(CalcTags.historyDelete(0)).performClick()
        compose.waitForIdle()
        assertTrue(state.history.isEmpty())
    }

    @Test fun `the Currency converter shows its rates date, swaps, and = keeps the conversion in history`() {
        launch()
        val cur = Declarations.modes.first { it.id == "currency" }
        compose.runOnIdle { state.tab = cur.tab; state.modeByTab[cur.tab] = cur.id }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(CalcTags.CONVERT_EQ).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(has("Rates as of"))
        compose.onNodeWithTag(CalcTags.CONVERT_SWAP).performClick()
        // The result belongs to the conversion it answered: after a swap there is none until the new one is answered.
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(CalcTags.CONVERT_EQ).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(CalcTags.CONVERT_EQ).performClick()
        compose.waitForIdle()
        assertEquals(cur.id, state.history.first().mode)
        assertTrue(state.history.first().ts > 0)
    }

    @Test fun `Network - Data opens on 100 Mbps in MB per s, swaps, times a transfer and keeps both in history`() {
        launch()
        val net = Declarations.modes.first { it.kind == "data_converter" }
        compose.runOnIdle { state.tab = net.tab; state.modeByTab[net.tab] = net.id }
        // Scoped to this mode: a pager may compose the neighbouring converter, which carries the same tags.
        fun at(tag: String) = compose.onNode(androidx.compose.ui.test.hasTestTag(tag) and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag(CalcTags.mode(net.id))))
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(CalcTags.CONVERT_EQ).fetchSemanticsNodes().isNotEmpty() }
        at(CalcTags.DATA_HINT).assertTextContains("lowercase b = bits", substring = true, ignoreCase = true)
        at(CalcTags.RESULT).assertTextContains("= 12.5 MB/s")
        at(CalcTags.CONVERT_SWAP).performClick()
        at(CalcTags.RESULT).assertTextContains("= 800 Mbit/s")
        at(CalcTags.INPUT).performTextReplacement("12,5")
        at(CalcTags.RESULT).assertTextContains("= 100 Mbit/s")
        at(CalcTags.CONVERT_EQ).performClick()
        compose.waitForIdle()
        assertEquals(Logic.Entry(net.id, "12,5 MB/s to Mbit/s", "100 Mbit/s"), state.history.first().copy(ts = 0L))
        // No engine call: these units are the app's own exact arithmetic.
        assertTrue(synchronized(engine.evals) { engine.evals.none { "bit" in it } })

        compose.onNodeWithText(net.transfer!!.category).performClick()
        compose.waitForIdle()
        at(CalcTags.RESULT).assertTextContains("= 5 min 20 s")
        assertTrue(has("320 s"))
        at(CalcTags.TRANSFER_RATE).performTextReplacement("0")
        at(CalcTags.RESULT).assertTextContains("above zero", substring = true)
        at(CalcTags.TRANSFER_RATE).performTextReplacement("1000")
        at(CalcTags.RESULT).assertTextContains("= 32 s")
        at(CalcTags.CONVERT_EQ).performClick()
        compose.waitForIdle()
        assertEquals(Logic.Entry(net.id, "4 GB at 1000 Mbit/s", "32 s"), state.history.first().copy(ts = 0L))
    }

    @Test fun `Nutrition - Food weighs a cup of flour, gives its oven readings, and the search box opens it on a unit`() {
        launch()
        val n = Declarations.mode("nutrition")!!
        compose.runOnIdle { state.tab = n.tab; state.modeByTab[n.tab] = n.id }
        compose.waitForIdle()
        // Opens on 1 cup of all-purpose flour in grams: the USDA 1 cup portion is 125 g.
        compose.onNodeWithTag(CalcTags.RESULT).assertTextContains("= 125 g")
        compose.onNodeWithTag(CalcTags.INPUT).performTextReplacement("2")
        compose.onNodeWithTag(CalcTags.RESULT).assertTextContains("= 250 g")
        // No engine call: the arithmetic is the app's own.
        assertTrue(synchronized(engine.evals) { engine.evals.none { "flour" in it } })
        compose.onNodeWithText("Oven").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(CalcTags.INPUT).performTextReplacement("180")
        compose.onNodeWithTag(CalcTags.RESULT).assertTextContains("= 356 °F")
        assertTrue(has("Nearest gas mark 4"))
        // The search box: "kcal" opens the Energy chapter on kcal (1 kcal = 4.184 kJ).
        val other = Declarations.mode("unit_length")!!
        compose.runOnIdle { state.tab = other.tab; state.modeByTab[other.tab] = other.id }
        compose.waitForIdle()
        compose.onNodeWithTag(CalcTags.CONVERT_SEARCH).performTextInput("kcal")
        compose.waitForIdle()
        compose.onNodeWithTag(CalcTags.searchHit(0)).performClick()
        compose.waitForIdle()
        assertEquals(n.id, state.modeByTab[n.tab])
        compose.onNodeWithTag(CalcTags.RESULT).assertTextContains("= 4.184 kJ")
    }

    private fun has(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun day(d: java.time.LocalDate) = d.atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond()
    private fun openCurrency() {
        launch()
        val cur = Declarations.modes.first { it.id == "currency" }
        compose.runOnIdle { state.tab = cur.tab; state.modeByTab[cur.tab] = cur.id }
    }

    @Test fun `opening Currency with stale rates fetches and shows the fetched rates' own date`() {
        val today = com.diegonmarcos.cloudcalc.Fx.expectedDate(System.currentTimeMillis())
        engine.time = day(today.minusDays(9)); engine.fetchedTime = day(today)
        openCurrency()
        try { compose.waitUntil(5_000) { has("Rates as of $today") } } catch (e: Throwable) {
            throw AssertionError("fetches=${engine.fetches} time=${engine.time} want=$today tree=" + compose.onAllNodesWithText("Rates as of", substring = true).fetchSemanticsNodes().map { it.config.toString() }, e)
        }
        assertTrue(engine.fetches >= 1)
        // The old date is gone: the line was re-read after the fetch, not kept.
        assertEquals(0, compose.onAllNodesWithText("Rates as of ${today.minusDays(9)}", substring = true).fetchSemanticsNodes().size)
    }

    @Test fun `fresh rates are not fetched on open`() {
        val today = com.diegonmarcos.cloudcalc.Fx.expectedDate(System.currentTimeMillis())
        engine.time = day(today)
        openCurrency()
        compose.waitUntil(5_000) { has("Rates as of $today") }
        compose.waitForIdle()
        assertEquals(0, engine.fetches)
    }

    @Test fun `a failed refresh on open leaves the cached date and notes the failure`() {
        val today = com.diegonmarcos.cloudcalc.Fx.expectedDate(System.currentTimeMillis())
        engine.time = day(today.minusDays(9)); engine.fetchOk = false
        openCurrency()
        compose.waitUntil(5_000) { has("update failed: offline") }
        assertTrue(has("Rates as of ${today.minusDays(9)}"))
    }

    @Test fun `the cross-rate matrix fills all 36 cells from one EUR table, consistent both ways, cut to 4 decimals`() {
        openCurrency()
        val codes = Declarations.modes.first { it.id == "currency" }.favourites
        assertEquals(6, codes.size)
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag(CalcTags.matrixCell("USD", "JPY")).assertTextContains("141.6666") }.isSuccess }
        // Every cell has a number; the diagonal is 1.
        codes.forEach { r -> codes.forEach { col ->
            val text = compose.onNodeWithTag(CalcTags.matrixCell(r, col)).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString { it.text }
            assertTrue("($r,$col) is empty or unresolved: '$text'", text.isNotBlank() && text != "?")
            if (r == col) assertEquals("1", text)
        } }
        // Cut, not rounded: 1.2 / 0.85 = 1.41176...
        compose.onNodeWithTag(CalcTags.matrixCell("GBP", "USD")).assertTextContains("1.4117")
        compose.onNodeWithTag(CalcTags.matrixCell("BRL", "USD")).assertTextContains("0.2")
    }

    @Test fun `a history tap sends the result back to its mode`() {
        val mode = Declarations.modes.first { it.kind == "expression" }
        state.remember(Logic.Entry(mode.id, "6*7", "42"), 10)
        launch()
        val history = Declarations.modes.first { it.kind == "history" }
        compose.runOnIdle { state.tab = history.tab }
        compose.waitForIdle()
        compose.runOnIdle { state.send(mode.id, "42") }
        compose.waitForIdle()
        assertEquals(mode.tab, state.tab)
        compose.onNodeWithTag(CalcTags.INPUT).assertTextContains("42")
    }

    @Test fun `focusing the expression field never opens the soft keyboard, yet the cursor and selection still move`() {
        openStandard()
        typeKeys("1", "2", "3")
        val imm = RuntimeEnvironment.getApplication().getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        compose.onNodeWithTag(CalcTags.INPUT).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(CalcTags.INPUT).assertIsFocused()
        select(1, 2)
        assertFalse("the soft keyboard must stay closed", shadowOf(imm).isSoftInputVisible)
        // The selection is live: the pad replaces it.
        typeKeys("9")
        compose.onNodeWithTag(CalcTags.INPUT).assertTextContains("193")
        assertFalse(shadowOf(imm).isSoftInputVisible)
    }

    @Test fun `the pad edits in the middle - inserts at the cursor, DEL deletes before it or the selection`() {
        openStandard()
        typeKeys("1", "2", "3", "4")
        select(2)
        typeKeys("+")
        compose.onNodeWithTag(CalcTags.INPUT).assertTextContains("12+34")
        typeKeys("DEL", "DEL")
        compose.onNodeWithTag(CalcTags.INPUT).assertTextContains("134")
        select(1, 2)
        typeKeys("DEL")
        compose.onNodeWithTag(CalcTags.INPUT).assertTextContains("14")
    }

    @Test fun `a pasted text is cut down to what a calc reads`() {
        openStandard()
        compose.onNodeWithTag(CalcTags.INPUT).performTextReplacement("1+2\n\t3\u0000x\uD83D\uDE00")
        compose.waitForIdle()
        compose.onNodeWithTag(CalcTags.INPUT).assertTextContains("1+23x")
    }

    @Test fun `pad keys carry their role and the roles are told apart by colour`() {
        openStandard()
        fun role(label: String) = compose.onNodeWithTag(CalcTags.key(label)).fetchSemanticsNode().config[com.diegonmarcos.cloudcalc.ui.PadRoleKey]
        assertEquals("DIGIT", role("7")); assertEquals("OPERATOR", role("+")); assertEquals("CLEAR", role("AC"))
        assertEquals("DELETE", role("DEL")); assertEquals("EVALUATE", role("=")); assertEquals("FUNCTION", role("√"))
        listOf(true, false).forEach { dark ->
            val p = com.diegonmarcos.cloudcalc.ui.PadPalette.of(RuntimeEnvironment.getApplication(), dark)
            val fills = com.diegonmarcos.cloudcalc.ui.PadRole.values().map { p.fill(it) }
            assertEquals("every role has its own fill (dark=$dark)", fills.size, fills.toSet().size)
            com.diegonmarcos.cloudcalc.ui.PadRole.values().forEach {
                assertTrue("$it ink readable on its fill (dark=$dark)", com.diegonmarcos.superapp.themebg.ThemeBackground.contrast(p.ink(it), p.fill(it)) >= 4.5)
            }
        }
    }
}
