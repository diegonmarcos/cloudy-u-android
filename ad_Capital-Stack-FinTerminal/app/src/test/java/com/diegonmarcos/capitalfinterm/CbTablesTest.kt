package com.diegonmarcos.capitalfinterm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1539 the GDP-weight aggregation math, the heat, the Taylor model, n/a instead of invented values, and source + vintage on every value of the baked snapshot. */
class CbTablesTest {
    private val decl = CbDecl.parse(DataFiles.cbCountriesJson, DataFiles.cbIndicatorsJson)

    private fun s(vararg fc: Pair<Int, Double>, hist: Map<Int, Double> = emptyMap()) = CbSeries("src", "rel", "2026-11-06", "u", "", hist, fc.toMap(), null, emptyList())
    private fun snap(series: Map<String, Map<String, CbSeries>>, ppp: Map<String, Map<Int, Double>>, fx: Map<String, Map<Int, Double>> = ppp) =
        CbSnapshot("2026-11-06", 2026, 2011, 2028, mapOf("ppp" to ppp, "fx" to fx), series)

    @Test fun weightedMeanIsTheGdpWeightedMeanOfTheCountriesThatHaveAValue() {
        val a = CbAggregate.weightedMean(listOf(2.0 to 3.0, 4.0 to 1.0))
        assertEquals((2.0 * 3 + 4.0 * 1) / 4, a.value!!, 1e-12); assertEquals(2, a.countries); assertEquals(1.0, a.coverage, 1e-12)
    }

    @Test fun aCountryWithNoValueOrNoWeightDropsOutOfBothSums() {
        val a = CbAggregate.weightedMean(listOf(2.0 to 3.0, null to 5.0, 9.0 to null, 9.0 to 0.0, 4.0 to 1.0))
        assertEquals(2.5, a.value!!, 1e-12); assertEquals(2, a.countries)
        assertEquals("coverage is the weight share that has a number", 4.0 / 9.0, a.coverage, 1e-12)
        assertNull("no country with a value: no number, never 0", CbAggregate.weightedMean(listOf(null to 1.0)).value)
    }

    @Test fun theAggregateOfADeclaredGroupUsesThatYearsWeightsAndNeverCountsEuroAreaMembersTwice() {
        val ids = decl.countries.map { it.id }
        val series = ids.associateWith { id -> mapOf("real_gdp" to s(2026 to when (id) { "US" -> 2.0; "EA" -> 1.0; "DE" -> 100.0; "BR" -> 3.0; else -> 0.0 })) }
        val w = ids.associateWith { id -> mapOf(2026 to when (id) { "US" -> 3.0; "EA" -> 1.0; "BR" -> 2.0; else -> 1.0 }) }
        val snap = snap(series, w)
        val dm = CbAggregate.of(decl, snap, "DM", "real_gdp", 2026, "ppp")
        val dmMembers = CbAggregate.members(decl, "DM").map { it.id }
        assertFalse("DE is in the euro area", "DE" in dmMembers); assertTrue("EA" in dmMembers)
        val want = dmMembers.sumOf { (series.getValue(it).getValue("real_gdp").at(2026)!!) * w.getValue(it).getValue(2026) } / dmMembers.sumOf { w.getValue(it).getValue(2026) }
        assertEquals(want, dm.value!!, 1e-12)
        val world = CbAggregate.of(decl, snap, CbAggregate.WORLD, "real_gdp", 2026, "ppp")
        assertEquals(CbAggregate.members(decl, "DM").size + CbAggregate.members(decl, "EM").size, CbAggregate.members(decl, CbAggregate.WORLD).size)
        assertTrue(world.value!! > 0.0)
    }

    @Test fun theWeightOptionChangesTheAggregate() {
        val series = mapOf("US" to mapOf("real_gdp" to s(2026 to 1.0)), "BR" to mapOf("real_gdp" to s(2026 to 5.0)))
        val snap = snap(series, ppp = mapOf("US" to mapOf(2026 to 1.0), "BR" to mapOf(2026 to 1.0)), fx = mapOf("US" to mapOf(2026 to 9.0), "BR" to mapOf(2026 to 1.0)))
        assertEquals(3.0, CbAggregate.of(decl, snap, CbAggregate.WORLD, "real_gdp", 2026, "ppp").value!!, 1e-12)
        assertEquals(1.4, CbAggregate.of(decl, snap, CbAggregate.WORLD, "real_gdp", 2026, "fx").value!!, 1e-12)
    }

    @Test fun heatComparesAValueWithTheCountrysOwnHistory() {
        val h = listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        assertEquals(0f, CbHeat.of(h, 3.0)!!, 1e-6f)
        assertEquals(1f, CbHeat.of(h, 9.0)!!, 1e-6f); assertEquals(-1f, CbHeat.of(h, -9.0)!!, 1e-6f)
        assertNull("under five actuals there is no heat", CbHeat.of(listOf(1.0, 2.0), 3.0)); assertNull(CbHeat.of(listOf(2.0, 2.0, 2.0, 2.0, 2.0), 3.0))
    }

    @Test fun taylorRuleIsNeutralPlusInflationPlusWeightedGaps() {
        val m = decl.model
        assertEquals(1.5 + 3.0 + 1.5 * (3.0 - 2.0) + 0.5 * -1.0, CbModel.taylor(m, 1.5, 3.0, 2.0, -1.0)!!, 1e-12)
        assertNull("a missing input is never guessed", CbModel.taylor(m, null, 3.0, 2.0, 0.0)); assertNull(CbModel.taylor(m, 1.0, 3.0, null, 0.0)); assertNull(CbModel.taylor(m, 1.0, 3.0, 2.0, null))
        assertEquals(1, CbModel.bias(m, 5.0, 4.0)); assertEquals(-1, CbModel.bias(m, 3.0, 4.0)); assertEquals(0, CbModel.bias(m, 4.2, 4.0)); assertNull(CbModel.bias(m, null, 4.0))
    }

    @Test fun aMissingValueIsAnNaCellAndALatentVariableIsModelOnly() {
        val snap = snap(mapOf("US" to mapOf("real_gdp" to s(2026 to 2.0))), mapOf("US" to mapOf(2026 to 1.0)))
        val t = CbTables.countries(decl, snap, true, "income_fiscal", 2026, "ppp")
        assertTrue(t.rows.flatMap { it.drop(1) }.all { it == decl.word("na") })
        val d = CbTables.countries(decl, snap, true, "dsge", 2026, "ppp")
        assertTrue(d.rows.flatMap { it.drop(1) }.all { it == decl.word("model_only") })
        assertEquals("n/a", decl.word("na")); assertEquals("n/a (no free source)", decl.word("na_long")); assertEquals("model-only", decl.word("model_only"))
    }

    @Test fun pinnedRowsAreTheWorldDmAndEmAggregatesAndAllCountriesFollow() {
        val snap = CbSnapshot.parse(DataFiles.cbSnapshotJson)
        val t = CbTables.countries(decl, snap, false, decl.simple.first().id, snap.currentYear, "ppp")
        assertEquals(3, t.pinned); assertEquals(listOf(CbAggregate.WORLD, "DM", "EM"), t.ids.take(3))
        assertEquals(decl.countries.map { it.id }, t.ids.drop(3)); assertEquals(t.header.size, t.rows.first().size)
        val inv = CbTables.transpose(t)
        assertEquals(t.header.size - 1, inv.rows.size); assertEquals(t.rows.size + 1, inv.header.size)
    }

    @Test fun theSimpleAndFullListsKeepTheOwnersGroupCounts() {
        assertEquals(listOf(5, 3, 4, 5, 4), decl.simple.map { it.items.size })
        assertEquals(listOf(25, 16, 16, 22, 15, 11, 9), decl.full.map { it.items.size })
        assertEquals(114, decl.indicators.size)
    }

    @Test fun everyValueOfTheBakedSnapshotCarriesItsSourceAndVintage() {
        val snap = CbSnapshot.parse(DataFiles.cbSnapshotJson)
        var n = 0
        for ((c, ind) in snap.series) for ((id, ser) in ind) {
            assertNotNull("$c/$id is a declared indicator", decl.indicators[id])
            assertTrue("$c/$id source", ser.source.isNotBlank()); assertTrue("$c/$id release", ser.release.isNotBlank())
            assertTrue("$c/$id vintage", Regex("""\d{4}-\d{2}-\d{2}""").matches(ser.vintage))
            assertTrue("$c/$id has a value", ser.hist.isNotEmpty() || ser.fc.isNotEmpty() || ser.ytd != null)
            for (p in ser.projections) { assertTrue(p.label.isNotBlank()); assertTrue(Regex("""\d{4}-\d{2}-\d{2}""").matches(p.vintage)) }
            n++
        }
        assertTrue("the snapshot holds series", n > 500)
    }
}
