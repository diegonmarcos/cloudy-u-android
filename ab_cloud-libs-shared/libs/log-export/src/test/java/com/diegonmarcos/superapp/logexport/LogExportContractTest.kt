package com.diegonmarcos.superapp.logexport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1490 the log-export IPC contract: authority, arguments, caller check, and the JSON a reader (Cloud-Webserver) parses. */
class LogExportContractTest {
    @Test fun authorityIsPerPackage() {
        assertEquals("com.x.y.logexport", LogExportContract.authority("com.x.y"))
    }

    @Test fun defaultsAndClamps() {
        assertEquals(LogExportContract.Query(300, false, 5), LogExportContract.parse(null))
        assertEquals(LogExportContract.Query(300, false, 5), LogExportContract.parse("n=abc&crashes=&junk"))
        assertEquals(LogExportContract.Query(2000, true, 20), LogExportContract.parse("n=999999&errors=1&crashes=99"))
        assertEquals(LogExportContract.Query(1, false, 0), LogExportContract.parse("n=-4&crashes=-1"))
    }

    @Test fun refusesCallerWithoutThePermissionAndUnknownMethods() {
        assertNotNull(LogExportContract.refusal(false, LogExportContract.EXPORT))
        assertTrue(LogExportContract.refusal(false, "logs")!!.contains(LogExportContract.PERMISSION))
        assertNotNull(LogExportContract.refusal(true, "shell"))
        for (m in LogExportContract.METHODS) assertNull(m, LogExportContract.refusal(true, m))
    }

    @Test fun exportJsonEscapesAndCarriesBothRings() {
        val j = LogExportContract.export("App", "com.a", "1.0", 42L, listOf("I/x: \"q\"\t\\", "line2"), listOf(LogExportContract.Crash("crash-1.txt", "boom\nat y")))
        assertTrue(j.contains("\"time_ms\":42"))
        assertTrue(j.contains("\"logcat\":[\"I/x: \\\"q\\\"\\t\\\\\",\"line2\"]"))
        assertTrue(j.contains("\"crashes\":[{\"name\":\"crash-1.txt\",\"text\":\"boom\\nat y\"}]"))
        assertTrue(j.contains("\"package\":\"com.a\""))
    }

    @Test fun controlCharactersAreEscaped() {
        assertEquals("\"a\\u0001b\"", LogExportContract.jsonString("a\u0001b"))
    }

    @Test fun oversizedCrashIsCut() {
        val j = LogExportContract.crashesJson(listOf(LogExportContract.Crash("c", "x".repeat(LogExportContract.MAX_CRASH_CHARS * 2))))
        assertTrue(j.length < LogExportContract.MAX_CRASH_CHARS + 100)
    }

    @Test fun metaListsTheMethods() {
        val j = LogExportContract.meta("A", "com.a", "2")
        for (m in LogExportContract.METHODS) assertTrue(j.contains("\"$m\""))
        assertTrue(j.contains("\"authority\":\"com.a.logexport\""))
    }
}
