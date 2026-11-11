package com.diegonmarcos.cloudcalc

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The parser the phone runs, over THIS repository's build.json::ui (the test runs from app/), and
 * the per-mode declaration rules every renderer relies on.
 */
@RunWith(RobolectricTestRunner::class)
class DeclarationsTest {
    private val ui = JSONObject(File("../build.json").readText()).getJSONObject("ui")
    // #868 the navigation: ui.bottom_nav + ui.sections (with `pages`) + ui.default_section.
    private val nav = Declarations.parseNav(
        ui.getJSONArray("sections").toString(),
        (0 until ui.getJSONArray("bottom_nav").length()).joinToString(",") { ui.getJSONArray("bottom_nav").getString(it) },
        ui.getString("default_section"),
    )
    private val tabs = Declarations.tabsIn(nav)
    private val modes = Declarations.parseModes(undoc(ui.getJSONArray("modes")).toString())

    /** #1409 app/build.gradle drops `_doc*` keys before baking; the on-disk declaration is compared the same way. */
    private fun undoc(o: Any?): Any? = when (o) {
        is JSONObject -> JSONObject().also { r -> o.keys().forEach { k -> if (!k.startsWith("_doc")) r.put(k, undoc(o.get(k))) } }
        is org.json.JSONArray -> org.json.JSONArray().also { r -> (0 until o.length()).forEach { r.put(undoc(o.get(it))) } }
        else -> o
    }

    @Test fun `the declaration parses and matches what gradle baked`() {
        assertEquals(tabs, Declarations.tabs)
        assertEquals(modes, Declarations.modes)
        assertTrue(tabs.any { it.id == Declarations.defaultTab })
    }

    @Test fun `sections parse, match what gradle baked, and every page sits in one`() {
        val sections = Declarations.sectionsOf(nav)
        assertEquals(sections, Declarations.sections)
        assertEquals("the island is the declared bottom_nav, at most five", listOf("calculator", "sheet", "ai", "measure", "toolkit"), sections.map { it.id })
        assertTrue(sections.size <= 5)
        assertEquals(sections.size, sections.map { it.id }.toSet().size)
        tabs.forEach { t -> assertTrue("tab ${t.id} names section ${t.section}", nav.sections.any { it.id == t.section }) }
        nav.sections.forEach { s -> assertTrue("section ${s.id} has no tab", tabs.any { it.section == s.id }) }
        assertEquals(sections.first { s -> s.id == Declarations.sectionOf(Declarations.defaultTab) }.id, Declarations.sectionOf(Declarations.defaultTab))
        assertEquals(sections.first().id, Declarations.sectionOf("no-such-tab"))
    }

    @Test fun `the bar reads Calc, Sheet, AI, Measure, Toolkit and Configs is the one page off it`() {
        assertEquals(listOf("Calc", "Sheet", "AI", "Measure", "Toolkit"), Declarations.sections.map { it.label })
        val configs = Declarations.configsTab
        assertEquals("configs", configs?.id)
        assertTrue("Configs is not on the bar", Declarations.sections.none { it.id == configs?.section })
        assertEquals(listOf("configs_general", "configs_permissions", "configs_about", "jev_token", "jev_routing", "jev_models", "jev_test", "image_route", "configs_agents", "configs_logcat", "configs_xray"), Declarations.modesOf("configs").map { it.id })
        // #1630 the strip ends with the fleet-default tail, in the exact owner order, the "|" before the first of the five
        val strip = com.diegonmarcos.cloudcalc.ui.ConfigsTail.order("configs", Declarations.modesOf("configs"))
        assertEquals(listOf("fleet_agents", "fleet_perms", "fleet_logcat", "fleet_xray", "fleet_about"), strip.takeLast(5).map { it.kind })
        assertEquals("configs_agents", com.diegonmarcos.cloudcalc.ui.ConfigsTail.separatorBefore("configs", strip))
        assertTrue("no tab was lost", Declarations.modesOf("configs").all { m -> strip.any { it.id == m.id } })
    }

    @Test fun `the jev block parses, validates, and is what gradle baked`() {
        val jev = JSONObject(File("../build.json").readText()).getJSONObject("jev")
        val cfg = com.diegonmarcos.cloudcalc.jev.JevConfig.parse(jev.toString())
        assertEquals(cfg, com.diegonmarcos.cloudcalc.decide.JevStore.defaults)
        assertEquals(emptyList<String>(), com.diegonmarcos.cloudcalc.decide.JevStore.appErrors(cfg))
    }

    @Test fun `the sound block parses, is what gradle baked, and its defaults pass its own guard`() {
        val c = com.diegonmarcos.cloudcalc.audio.SoundDecl.parse(JSONObject(File("../build.json").readText()).getJSONObject("sound").toString())
        assertEquals(c, com.diegonmarcos.cloudcalc.audio.SoundDecl.config)
        val a = c.analysis
        assertTrue("frame ${a.frame}", a.frame > 0 && (a.frame and (a.frame - 1)) == 0)
        assertTrue(a.hop in 1..a.frame && a.block in 1..a.frame)
        assertTrue(a.fmin > 0 && a.fmax > a.fmin && a.fmax < c.sampleRate / 2.0)
        assertTrue(a.silenceDb < 0 && a.gateDb > 0 && a.yinThreshold in 0.0..1.0)
        assertTrue(c.a4Hz > 0 && c.temperatureC > -273.15 && c.recordMs in 100..c.maxRecordMs)
        assertTrue(c.history.bands > 0 && c.history.rows > 0 && c.history.maxWavSeconds > 0)
        val g = com.diegonmarcos.cloudcalc.sound.Generator.guard(c.generatorDefaults, c.limits, c.sampleRate, 0.0)
        assertEquals("the declared defaults are already safe: ${g.notes}", emptyList<String>(), g.notes)
    }

    @Test fun `every tab has a mode and every mode has a tab`() {
        tabs.forEach { t -> assertTrue("tab ${t.id} has no mode", modes.any { it.tab == t.id }) }
        modes.forEach { m -> assertTrue("mode ${m.id} names tab ${m.tab}", tabs.any { it.id == m.tab }) }
        assertEquals(modes.size, modes.map { it.id }.toSet().size)
    }

    @Test fun `each kind carries what its renderer reads`() {
        modes.forEach { m ->
            when (m.kind) {
                "expression" -> assertTrue("${m.id}: no keys", m.keys.isNotEmpty() && m.keys.all { r -> r.all { it.label.isNotBlank() } })
                "catalog" -> assertTrue("${m.id}: no catalog", m.catalog.isNotEmpty())
                "converter" -> assertTrue("${m.id}: no categories", m.categories.isNotEmpty() && (m.defaults["category"] ?: m.categories.first()) in m.categories)
                // Network / Data: every chip is a declared unit set or the transfer chip, every pair is in its set.
                "data_converter" -> {
                    assertEquals("${m.id}: chips", m.unitSets.map { it.category } + listOfNotNull(m.transfer?.category), m.categories)
                    assertTrue("${m.id}: default category", (m.defaults["category"] ?: m.categories.first()) in m.categories)
                    m.unitSets.forEach { u ->
                        assertTrue("${m.id}/${u.category}: ${u.from} -> ${u.to}", u.unit(u.from) != null && u.unit(u.to) != null)
                        assertTrue("${m.id}/${u.category}: factors", u.units.all { it.factor.signum() > 0 })
                    }
                    m.unitSets.firstOrNull { it.category == m.defaults["category"] }?.let { u ->
                        assertTrue("${m.id}: default pair", u.unit(m.defaults["from"].orEmpty()) != null && u.unit(m.defaults["to"].orEmpty()) != null)
                    }
                }
                "nutrition" -> assertEquals("${m.id}: chapters", 4, m.categories.size)
                "form" -> assertTrue("${m.id}: no forms", m.forms.isNotEmpty())
                "plot" -> assertTrue("${m.id}: bad range", m.plot != null && m.plot!!.xmax > m.plot!!.xmin && m.plot!!.steps > 0)
                "meter" -> m.meter!!.let { assertTrue("${m.id}: fft ${it.fftSize}", it.fftSize > 0 && (it.fftSize and (it.fftSize - 1)) == 0) }
                "history" -> assertTrue(m.historyMax > 0)
                // #768 the Clock kinds: what ClockDecl reads must be there and make sense.
                "worldclock" -> JSONObject(m.clock).getJSONArray("zones").let { z ->
                    assertTrue("${m.id}: no zones", z.length() > 0)
                    (0 until z.length()).forEach { java.time.ZoneId.of(z.getString(it)) }
                }
                "alarms" -> JSONObject(m.clock).let { c ->
                    val choices = c.getJSONArray("snooze_choices").let { a -> (0 until a.length()).map { a.getInt(it) } }
                    assertTrue("${m.id}: snooze_default not a choice", c.getInt("snooze_default") in choices)
                    assertTrue(c.getInt("ring_minutes") > 0)
                }
                "timers" -> JSONObject(m.clock).getJSONArray("presets").let { a ->
                    assertTrue(a.length() > 0)
                    (0 until a.length()).forEach { assertTrue(a.getLong(it) > 0) }
                }
                "interval" -> JSONObject(m.clock).getJSONArray("presets").let { a ->
                    assertTrue(a.length() > 0)
                    (0 until a.length()).map { a.getJSONObject(it) }.forEach { p -> assertTrue(p.getLong("work") > 0 && p.getInt("rounds") > 0) }
                }
                "bedtime" -> JSONObject(m.clock).let { c ->
                    assertTrue(c.getJSONArray("sleep_choices").length() > 0)
                    assertTrue("${m.id}: bedtime_default", com.diegonmarcos.cloudcalc.clock.ClockLogic.parseTime(c.getString("bedtime_default")) != null)
                }
            }
        }
    }

    @Test fun `Clock moved from Measure to Toolkit and the new pages' modes carry what their renderers read`() {
        assertEquals(listOf("sensors", "phone", "camera", "wifi", "bluetooth", "nfc", "lfrfid"), Declarations.tabsOf("measure").map { it.id })
        assertEquals(listOf("light", "recorder", "tracker", "clock"), Declarations.tabsOf("toolkit").map { it.id })
        assertEquals(listOf("ai", "ask"), Declarations.tabsOf("ai").map { it.id })
        assertEquals("the clock modes did not move or duplicate", listOf("world", "alarms", "timers", "stopwatch", "interval", "bedtime"), Declarations.modesOf("clock").map { it.id })
        fun spec(id: String) = JSONObject(Declarations.mode(id)!!.spec)
        // the Sensors page: every sensor group has its mode, and every tool mode names real sensors
        val catalog = com.diegonmarcos.cloudcalc.sensors.SensorDecl.catalog
        val shown = modes.filter { it.kind == "sensor_group" }.map { JSONObject(it.spec).getString("group") }.toSet()
        assertEquals(catalog.groups().toSet(), shown)
        listOf("gravity", "accelerometer", "field").forEach { assertTrue("compass $it", catalog.sensor(spec("sx_compass").getString(it)) != null) }
        assertTrue(catalog.sensor(spec("sx_seismo").getString("sensor")) != null)
        assertTrue(catalog.sensor(spec("sx_metal").getString("sensor")) != null)
        listOf("sx_compass" to "band", "sx_seismo" to "band", "sx_metal" to "band").forEach { (id, k) -> assertTrue("$id band", spec(id).getString(k) in catalog.bands) }
        assertTrue(spec("sx_seismo").getInt("window") > 1)
        assertTrue(spec("sx_metal").getInt("baseline_samples") > 1)
        assertEquals(listOf("mm", "in"), spec("sx_ruler").getJSONArray("units").let { a -> (0 until a.length()).map { a.getString(it) } })
        // Toolkit
        spec("recorder").let { assertTrue(it.getInt("sample_rate") >= 8000 && it.getInt("max_minutes") > 0 && it.getString("dir").isNotBlank()) }
        spec("tracker").let { t ->
            assertEquals("nav", t.getString("fleet"))
            assertEquals("shortcut_action", t.getString("extra"))
            assertTrue((0 until t.getJSONArray("pages").length()).all { t.getJSONArray("pages").getJSONObject(it).getString("target").startsWith("page:tracker/") })
        }
        assertEquals("a mode without a spec parses to an empty object", "{}", Declarations.mode("standard")!!.spec)
    }

    @Test fun `every tracker deep link is one Cloud Navigation declares`() {
        val nav = File("../../ac_cloud-nav/build.json")
        if (!nav.exists()) return   // the repository's other apps are not part of this app's checkout
        val pages = JSONObject(nav.readText()).getJSONObject("ui").getJSONArray("sections").let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.first { it.getString("id") == "tracker" }.getJSONArray("pages")
        }.let { p -> (0 until p.length()).map { p.getJSONObject(it).getString("id") } }
        val links = JSONObject(Declarations.mode("tracker")!!.spec).getJSONArray("pages")
        (0 until links.length()).map { links.getJSONObject(it).getString("target").removePrefix("page:tracker/") }.forEach { assertTrue("Cloud Navigation has no Tracker page $it", it in pages) }
    }

    @Test fun `every expression keypad can evaluate and clear`() {
        modes.filter { it.kind == "expression" }.forEach { m ->
            val actions = m.keys.flatten().mapNotNull { it.action }.toSet()
            assertTrue("${m.id} has no = key", Declarations.Action.EVALUATE in actions)
            assertTrue("${m.id} has no AC key", Declarations.Action.CLEAR in actions)
        }
    }

    @Test fun `every form expression names only its own fields`() {
        modes.flatMap { it.forms }.forEach { f ->
            val ids = f.fields.map { it.id }.toSet()
            f.outputs.filter { it.calc.isEmpty() }.forEach { o ->
                val named = Logic.placeholders(o.expr)
                assertFalse("${f.id}/${o.label} names nothing", named.isEmpty())
                assertTrue("${f.id}/${o.label} names ${named - ids}", ids.containsAll(named))
            }
        }
    }

    @Test fun `choice rows set a real engine option`() {
        val keys = setOf("in_base", "out_base", "angle", "approx", "precision")
        modes.flatMap { it.angleChoices + it.baseChoices + it.showBases }.forEach { assertTrue(it.key, it.key in keys) }
    }

    @Test fun `a choice row starts on the option its mode really sets`() {
        modes.forEach { m ->
            (m.angleChoices + m.baseChoices).groupBy { it.key }.forEach { (key, row) ->
                val v = Logic.optionValue(m.options, key, Int.MIN_VALUE)
                assertTrue("${m.id}: options set $key=$v, which no chip shows", row.any { it.value == v })
            }
        }
    }
}
