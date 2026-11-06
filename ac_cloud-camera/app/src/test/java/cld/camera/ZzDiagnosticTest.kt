package cld.camera

import org.junit.Assert.fail
import org.junit.Test

/** TEMPORARY (#1533): fails on purpose so the run lists every unverified artifact (lenient mode) and publishes nothing. Removed in the next commit. */
class ZzDiagnosticTest {
    @Test fun diagnostic() { fail("diagnostic run: list unverified artifacts, publish nothing") }
}
