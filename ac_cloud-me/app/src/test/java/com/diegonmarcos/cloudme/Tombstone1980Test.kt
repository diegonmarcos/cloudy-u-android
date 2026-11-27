package com.diegonmarcos.cloudme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1980 the page cache stores a tombstone only for a definite not-there answer; a CDN refusal (403) or throttle must not blank images for the ttl. */
class Tombstone1980Test {
    @Test fun notFoundAndGoneAreDefinite() {
        assertTrue(Tombstone1980.definite(404))
        assertTrue(Tombstone1980.definite(410))
    }

    @Test fun cdnRefusalsAreNeverTombstones() {
        for (c in listOf(400, 401, 403, 408, 429, 451)) assertFalse("HTTP $c must not become a stored 404", Tombstone1980.definite(c))
    }
}
