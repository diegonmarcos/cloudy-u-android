package com.diegonmarcos.superapp.batterystats

import com.diegonmarcos.superapp.battery.BatteryExtras
import com.diegonmarcos.superapp.battery.BatteryMath
import com.diegonmarcos.superapp.battery.BatteryMath.STATUS_CHARGING
import com.diegonmarcos.superapp.battery.BatteryMath.STATUS_DISCHARGING
import com.diegonmarcos.superapp.battery.BatteryReading
import com.diegonmarcos.superapp.battery.BatteryReport
import com.diegonmarcos.superapp.battery.BatteryRows
import com.diegonmarcos.superapp.battery.BatterySample
import com.diegonmarcos.superapp.battery.BatteryTruth
import com.diegonmarcos.superapp.battery.FullSource
import com.diegonmarcos.superapp.battery.RateSource
import com.diegonmarcos.superapp.notificationcenter.BatteryBadgeModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE battery SoT: one computation, and the badge, the popup and the stats page
 * all print its values — the old per-surface paths (BatteryEstimator, the
 * badge's own EMA store, BatterySessionStats, PowerFlow) are gone.
 */
class BatteryTruthTest {

    private val now = 1_700_000_000_000L
    private val min = 60_000L
    private val hour = 3_600_000L

    private fun onBattery(raw: Int? = -400_000, level: Int = 80) = BatteryReading(
        nowMs = now, levelPct = level, status = STATUS_DISCHARGING, plugged = 0, health = 2, technology = "Li-ion",
        tempDc = 312, voltageMv = 4000, rawCurrentNow = raw, counterUah = 3_200_000L, screenOn = true)

    /** A real charge to 100% ending 2 h ago, then 2 h on battery down to 80%. */
    private val history = listOf(
        BatterySample(now - 4 * hour, 60, STATUS_CHARGING, 2),
        BatterySample(now - 2 * hour, 100, STATUS_DISCHARGING, 0, currentMa = -400, voltageMv = 4000),
        BatterySample(now - hour, 90, STATUS_DISCHARGING, 0, currentMa = -400, voltageMv = 4000),
    )

    private fun report(r: BatteryReading = onBattery(), samples: List<BatterySample> = history,
                       scale: BatteryMath.CurrentScale = BatteryMath.CurrentScale.UNKNOWN): BatteryReport {
        val all = samples + BatteryTruth.sampleOf(r, scale)
        val ema = BatteryMath.signedMa(r.rawCurrentNow, r.status, r.plugged, scale)?.let { BatteryMath.Ema(it.toDouble(), r.nowMs) }
        return BatteryTruth.compute(r, all, ema, BatteryExtras(ratedMah = 4500, cycleEstimate = 12.3, scale = scale))
    }

    // ── the "now" numbers ────────────────────────────────────────────────

    @Test fun `on battery - current, power, rate in both units, time to empty`() {
        val r = report()
        assertEquals(-400, r.currentNowMa)
        assertEquals(4000, r.capacityMah)                   // 3.2 Ah at 80%
        assertTrue(r.capacityFromCounter)
        assertEquals(-1.6, r.powerW!!, 1e-9)
        assertEquals(-10.0, r.ratePctH!!, 1e-9)
        assertEquals(-1.6, r.rateW!!, 1e-9)
        assertEquals(RateSource.CURRENT, r.rateSource)
        assertEquals(8 * hour, r.toEmptyMs)
        assertNull(r.toFullMs)
        assertEquals(88, r.healthPct)                       // 4000 of 4500 design
    }

    @Test fun `a milliamp vendor with the opposite sign reads the same`() {
        val samsung = report(onBattery(raw = 400))
        val pixel = report(onBattery(raw = -400_000))
        assertEquals(pixel.ratePctH!!, samsung.ratePctH!!, 1e-9)
        assertEquals(pixel.powerW!!, samsung.powerW!!, 1e-9)
        assertEquals(pixel.toEmptyMs, samsung.toEmptyMs)
    }

    @Test fun `without a current the rate is the level slope, and still in both units`() {
        val slope = listOf(BatterySample(now - 30 * min, 84, STATUS_DISCHARGING, 0))
        val r = report(onBattery(raw = null), slope)
        assertEquals(RateSource.LEVEL, r.rateSource)
        assertEquals(-8.0, r.ratePctH!!, 1e-9)
        assertEquals(-0.08 * 4.0 * 4.0, r.rateW!!, 1e-9)
    }

    @Test fun `charging prefers the system estimate, else the current rate`() {
        val c = onBattery(raw = 1_000_000, level = 50).copy(status = STATUS_CHARGING, plugged = 2, counterUah = 2_000_000L)
        val sys = report(c.copy(systemChargeRemainingMs = 65 * min))
        assertEquals(FullSource.SYSTEM, sys.toFullSource)
        assertEquals(65 * min, sys.toFullMs)
        val rate = report(c)
        assertEquals(25.0, rate.ratePctH!!, 1e-9)          // +1000 mA into 4000 mAh
        assertEquals(FullSource.RATE, rate.toFullSource)
        assertEquals(2 * hour, rate.toFullMs)
        assertNull(rate.toEmptyMs)
        assertNull("on power, the charge in progress is the story", rate.since)
    }

    @Test fun `since last charge and the estimate at its average`() {
        val r = report()
        val s = r.since!!
        assertEquals(now - 2 * hour, s.fromTs)
        assertEquals(20, s.usedPct)
        assertEquals(-10.0, s.avgPctPerHour!!, 1e-9)
        assertEquals(-1.6, r.sinceAvgW!!, 1e-9)
        assertEquals(8 * hour, r.toEmptyAtAvgMs)
    }

    // ── power in / out ───────────────────────────────────────────────────

    @Test fun `on battery the phone's consumption is the battery's own draw, measured`() {
        val f = BatteryTruth.powerFlow(report())
        assertEquals(-1.6, f.netW!!, 1e-9)
        assertEquals(1.6, f.consumptionW!!, 1e-9)
        assertEquals("measured", f.consumptionSource)
        assertEquals(0.0, f.inW!!, 1e-9)
        assertEquals("0 W (on battery)", BatteryRows.powerFlow(f).rows.last().value)
    }

    @Test fun `on power the charger's live input splits into battery and phone`() {
        val c = onBattery(raw = 1_000_000, level = 50).copy(status = STATUS_CHARGING, plugged = 2, counterUah = 2_000_000L)
        val all = history + BatteryTruth.sampleOf(c, BatteryMath.CurrentScale.UNKNOWN)
        val r = BatteryTruth.compute(c, all, BatteryMath.Ema(1000.0, now),
            BatteryExtras(chargerLiveW = 9.0, chargerSource = "USB-PD"))
        val f = BatteryTruth.powerFlow(r)
        assertEquals(4.0, f.netW!!, 1e-9)                    // +1 A at 4 V into the battery
        assertEquals(5.0, f.consumptionW!!, 1e-9)
        assertEquals("charger", f.consumptionSource)
        assertEquals(9.0, f.inW!!, 1e-9)
        assertFalse(f.inEstimated)
        assertEquals("9.0 W (USB-PD) live", BatteryRows.charger(r))
        val modeled = BatteryTruth.powerFlow(BatteryTruth.compute(c, all, BatteryMath.Ema(1000.0, now)), modeledConsumptionW = 1.5)
        assertEquals("modeled", modeled.consumptionSource)
        assertEquals(5.5, modeled.inW!!, 1e-9)
        assertTrue(modeled.inEstimated)
    }

    @Test fun `the charger is not claimed off power`() {
        val r = BatteryTruth.compute(onBattery(), history, null, BatteryExtras(chargerLiveW = 9.0))
        assertNull(r.chargerLiveW)
        assertEquals(BatteryRows.DASH, BatteryRows.charger(r))
    }

    // ── one SoT, every surface ───────────────────────────────────────────

    @Test fun `every rate the popup prints carries both units`() {
        val rows = BatteryRows.popup(report()).flatMap { it.rows }
        val rates = rows.filter { it.label == "Rate" || it.label == "Average" }
        assertEquals(2, rates.size)
        for (row in rates) assertTrue(row.value, row.value.contains("%/h") && row.value.contains(" W"))
        assertEquals("−10.0 %/h · −1.60 W", rates.first { it.label == "Rate" }.value)
    }

    @Test fun `the popup has the Now and Since sections the owner listed`() {
        val (nowSec, since) = BatteryRows.popup(report())
        assertEquals(listOf("Level", "State", "Current", "Power", "Rate", "To empty", "Voltage", "Temperature", "Health"),
            nowSec.rows.map { it.label })
        assertEquals("Since last charge", since.title)
        assertEquals(listOf("Unplugged", "Used", "Average", "To empty at avg", "Screen on / off"), since.rows.map { it.label })
    }

    @Test fun `the badge prints exactly the popup's rows from the same report`() {
        val r = report()
        val card = BatteryBadgeModel.card(r)
        for (sec in BatteryRows.popup(r)) for (row in sec.rows) {
            val line = "${row.label}: ${row.value}"
            assertTrue("badge is missing the popup row '$line'", card.expanded.lines().contains(line))
        }
        assertEquals("Battery 80% · ~${BatteryRows.fmtHm(r.toEmptyAtAvgMs!!)} left (est)", card.title)
        assertTrue(card.text, card.text.contains(BatteryRows.rate(r.ratePctH, r.rateW)))
    }

    @Test fun `the old duplicate battery paths are gone`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "ab_cloud-libs-shared").isDirectory }
        val app = File(root, "aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp")
        val lib = File(root, "ab_cloud-libs-shared/libs/battery/src/main/java/com/diegonmarcos/superapp/battery")
        assertFalse("BatteryEstimator was the badge's second calculation", File(app, "notificationcenter/BatteryEstimator.kt").exists())
        assertFalse("BatteryHistoryStore was a second session ledger", File(lib, "BatteryHistoryStore.kt").exists())
        assertFalse("BatterySessionStats was the old rate/ETA/power calculation", File(lib, "BatterySessionStats.kt").exists())
        assertFalse("PowerFlow read BatterySessionStats", File(lib, "PowerFlow.kt").exists())
        val api = File(app, "devcontrol/DevControlServer.kt").readText()
        assertTrue("the debug API's battery/state is the SoT report", api.contains("BatteryRepository.report("))
        val usage = File(lib, "EnergyUsageDialog.kt").readText()
        assertTrue("the power in/out card is the SoT's", usage.contains("BatteryTruth.powerFlow("))
        fun code(f: File) = f.readText().lines().filterNot { it.trim().startsWith("*") || it.trim().startsWith("//") }.joinToString("\n")
        val badge = code(File(app, "notificationcenter/BatteryBadgeService.kt"))
        assertTrue(badge.contains("BatteryRepository.report("))
        for (gone in listOf("getIntProperty", "CURRENT_NOW", "BatteryEstimator", "getSharedPreferences", "BatterySessionStats"))
            assertFalse("the badge service still has $gone", badge.contains(gone))
        val model = code(File(app, "notificationcenter/BatteryBadgeModel.kt"))
        assertFalse("the badge model computes", Regex("""\b(msToEmpty|msToFull|pctPerHour\(|ema\()""").containsMatchIn(model))
        val popup = code(File(lib, "BatteryEstimatePopup.kt"))
        assertTrue(popup.contains("BatteryRepository.report(") && popup.contains("BatteryRows.popup("))
        for (gone in listOf("BatterySessionStats", "PowerFlow", "BatteryCapacity"))
            assertFalse("the popup still reads $gone", popup.contains(gone))
        assertFalse("battery measurement no longer asks for Shizuku", File(lib, "ShizukuEnergy.kt").exists())
        for (f in lib.listFiles().orEmpty().filter { it.extension == "kt" })
            assertFalse("${f.name} prompts for Shizuku", Regex("""\bShizuku(Energy|UserService|\.)""").containsMatchIn(code(f)))
        assertTrue("the battery reads skip the Shizuku rung",
            code(File(lib, "BatteryRepository.kt")).contains("takeUnless { it === ShizukuShellChannel }"))
        assertTrue("unpaired opens the ADB Shell page", code(File(lib, "EnergyUsageDialog.kt")).contains("AdbShellLink.open("))
        val page = code(File(app, "batterystats/BatteryStatsFragment.kt"))
        assertTrue(page.contains("BatteryRepository.report(") && page.contains("BatteryRows.now(") && page.contains("BatteryRows.since("))
    }
}
