package com.diegonmarcos.cloudaccount

import com.diegonmarcos.cloudaccount.hub.SampleRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1482 Devices > Peer Control is a declared page with sample data: its rows come from build.json, none from Kotlin. */
class SamplePageTest {
    @Test fun rowsComeFromTheDeclaration() {
        val json = """[{"id":"devices","pages":[{"id":"peer-control","sample":{"rows":[
            {"title":"Sample peer A","caption":"reachable","state":"ok"},{"title":"Sample peer B","caption":"down","state":"bad"}]}}]}]"""
        val rows = SampleRows.of(json, "devices", "peer-control")
        assertEquals(listOf("Sample peer A", "Sample peer B"), rows.map { it.title })
        assertEquals(listOf("ok", "bad"), rows.map { it.state })
        assertTrue(SampleRows.of(json, "devices", "nope").isEmpty())
        assertTrue(SampleRows.of("not json", "devices", "peer-control").isEmpty())
    }
}
