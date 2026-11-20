package com.diegonmarcos.cloudwebserver

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/** #1834 Home asks the API health route of the DIAGNOSTICS listener (the `diag` row of serving), never the admin port. */
class ApiHealthPortTest {
    private val serving = JSONObject("""{"endpoints":[
        {"id":"drive","port":8002,"role":"drive"},
        {"id":"cloud","port":8003,"role":"diag"},
        {"id":"termux","port":8000,"role":"external"}]}""")

    @Test fun theDiagRowGivesThePort() {
        assertEquals(8003, Api.diagPort(serving))
    }

    @Test fun noDiagRowMeansNoPortYet() {
        assertNull(Api.diagPort(null))
        assertNull(Api.diagPort(JSONObject("""{"endpoints":[{"port":8002,"role":"drive"}]}""")))
    }

    @Test fun homeReadsHealthThroughTheDiagPort() {
        val home = File("src/main/java/com/diegonmarcos/cloudwebserver/ui/HomePage.kt").readText()
        assertTrue("Home must ask health through the diag port", "Api.getDiag(Api.HEALTH" in home)
        assertFalse("Home must not ask the admin port for the health route", "rememberObj(Api.HEALTH" in home)
    }
}
