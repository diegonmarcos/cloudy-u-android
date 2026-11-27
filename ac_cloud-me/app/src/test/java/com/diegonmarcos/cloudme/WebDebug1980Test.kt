package com.diegonmarcos.cloudme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1980 DevTools opens only for a debuggable build with the adb-written marker; never for a phone. */
class WebDebug1980Test {
    @Test fun needsBothTheDebuggableBuildAndTheMarker() {
        assertTrue(WebDebug1980.requested(true, true))
        assertFalse(WebDebug1980.requested(true, false))
        assertFalse(WebDebug1980.requested(false, true))
        assertFalse(WebDebug1980.requested(false, false))
    }

    @Test fun markerIsAShellOnlyPath() = assertEquals("/data/local/tmp/cloudme-webview-debug", WebDebug1980.MARKER)
}
