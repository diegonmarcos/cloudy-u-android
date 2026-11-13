package com.diegonmarcos.clouddrive.manage

import org.junit.Assert.assertEquals
import org.junit.Test

/** Local Apps groups (Cloud Apps = fleet, Phone Apps = rest), their Total rows and the cached-aggregate API Overview > Apps reads. */
class LocalAppsApiTest {
    private val rows = listOf(
        AppRow("fleet.a", "A", false, 10, 20, 30, null, null, "1", "☁", "u"),
        AppRow("fleet.b", "B", false, 1, 2, 3, null, null, "1", "☁", "u"),
        AppRow("p.x", "X", false, 100, 200, 300, null, null),
        AppRow("p.unread", "U", true, null, null, null, null, null))

    @Test fun groupsAndTotals() {
        val a = LocalAppsApi.aggregate(rows)
        assertEquals(AppTotals(2, 0, 66, 11, 22, 33), a.getValue(AppGroup.CLOUD))
        assertEquals(AppTotals(2, 1, 600, 100, 200, 300), a.getValue(AppGroup.PHONE))
        assertEquals(rows.size, a.values.sumOf { it.count })
    }

    @Test fun aCacheWriteUpdatesTheApiState() {
        val f = java.io.File.createTempFile("localapps", ".json").also { it.delete() }
        LocalAppsCache(f).write(AppSnapshot.Snapshot(rows, true, null, 1))
        assertEquals(2, LocalAppsApi.state.value!!.getValue(AppGroup.CLOUD).count)
        LocalAppsCache(f).write(AppSnapshot.Snapshot(rows.take(1), true, null, 2))   // a refresh replaces it
        assertEquals(1, LocalAppsApi.state.value!!.getValue(AppGroup.CLOUD).count)
        assertEquals(0, LocalAppsApi.state.value!!.getValue(AppGroup.PHONE).count)
        f.delete()
    }
}
