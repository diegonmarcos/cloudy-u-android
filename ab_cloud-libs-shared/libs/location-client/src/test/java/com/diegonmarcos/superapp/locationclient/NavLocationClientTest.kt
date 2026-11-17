package com.diegonmarcos.superapp.locationclient

import com.diegonmarcos.superapp.locationclient.NavLocationClient.Answer
import com.diegonmarcos.superapp.locationclient.NavLocationClient.Why
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1770 the client returns Cloud Nav's cached fix with age and accuracy, and says exactly why when it cannot. */
class NavLocationClientTest {
    private val now = 1_800_000_000_000L
    private val body = """{"v":1,"owner":"cloud-nav","fix":true,"lat":40.4168,"lon":-3.7038,"accuracy_m":12.5,"ts":${now - 60_000},"age_ms":60000,"source":"gps","predicted":false}"""

    @Test fun `the cached fix keeps its age accuracy and source`() {
        val f = (NavLocationClient.parse(body) as Answer.Ok).fix
        assertEquals(40.4168, f.lat, 0.0); assertEquals(-3.7038, f.lon, 0.0)
        assertEquals(12.5f, f.accuracyM); assertEquals(60_000L, f.ageMs); assertEquals("gps", f.source); assertFalse(f.predicted)
    }

    @Test fun `no fix, junk and out of range bodies are unavailable with a reason`() {
        assertEquals(Why.NO_FIX, (NavLocationClient.parse("""{"v":1,"fix":false,"reason":"Tracker off"}""") as Answer.Unavailable).why)
        assertEquals(Why.OUTDATED, (NavLocationClient.parse("not json") as Answer.Unavailable).why)
        assertEquals(Why.NO_FIX, (NavLocationClient.parse("""{"fix":true,"lat":0,"lon":200,"ts":5}""") as Answer.Unavailable).why)
    }

    @Test fun `every way Cloud Nav can be missing names its reason`() {
        assertEquals(Why.NOT_INSTALLED, (NavLocationClient.classify(false, false, 0, "", "") as Answer.Unavailable).why)
        assertEquals(Why.DENIED, (NavLocationClient.classify(true, false, 403, "", "") as Answer.Unavailable).why)
        assertEquals(Why.OUTDATED, (NavLocationClient.classify(true, false, 404, "", "") as Answer.Unavailable).why)
        assertEquals(Why.NOT_RUNNING, (NavLocationClient.classify(true, false, 0, "", "") as Answer.Unavailable).why)
        assertTrue(NavLocationClient.classify(true, true, 200, body, "") is Answer.Ok)
    }

    @Test fun `age grows with the time since the answer and accuracy can be bounded`() {
        val f = (NavLocationClient.parse(body) as Answer.Ok).fix
        assertTrue(NavLocationClient.freshEnough(f, now, now, 120_000))
        assertFalse(NavLocationClient.freshEnough(f, now, now + 90_000, 120_000))
        assertTrue(NavLocationClient.freshEnough(f, now, now, 120_000, maxAccuracyM = 20f))
        assertFalse(NavLocationClient.freshEnough(f, now, now, 120_000, maxAccuracyM = 10f))
    }

    @Test fun `the wire constants are the fleet IPC ones`() {
        assertEquals("fleet.ipc.v1", NavLocationClient.METHOD); assertEquals("tracker/location", NavLocationClient.OP)
    }
}
