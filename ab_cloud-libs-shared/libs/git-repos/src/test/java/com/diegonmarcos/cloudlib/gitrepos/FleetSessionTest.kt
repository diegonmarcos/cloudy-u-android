package com.diegonmarcos.cloudlib.gitrepos

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** #1693 the fleet session is Cloud Account's lease: expiry reads as no session, sync reads the source. */
class FleetSessionTest {
    private var now = 10_000L
    @Before fun setUp() { FleetSession.clock = { now }; FleetSession.forget(); FleetSession.source = null }
    @After fun tearDown() { FleetSession.forget(); FleetSession.source = null; FleetSession.clock = { System.currentTimeMillis() } }

    @Test fun `no source and nothing held is no session`() {
        assertFalse(FleetSession.present); assertFalse(FleetSession.sync()); assertEquals("", FleetSession.fresh())
    }

    @Test fun `sync takes the lease from the source and it expires`() {
        var calls = 0
        FleetSession.source = { calls++; FleetSession.Lease("c=1", now + 1000) }
        assertTrue(FleetSession.sync()); assertEquals("c=1", FleetSession.cookie)
        assertTrue(FleetSession.sync()); assertEquals(1, calls)          // the lease in hand is not re-read
        now += 1000
        assertEquals("", FleetSession.cookie); assertFalse(FleetSession.present)
        assertTrue(FleetSession.sync()); assertEquals(2, calls)
    }

    @Test fun `a source that throws, answers null or an expired lease is no session`() {
        FleetSession.source = { error("boom") }
        assertFalse(FleetSession.sync())
        FleetSession.source = { null }
        assertFalse(FleetSession.sync())
        FleetSession.source = { FleetSession.Lease("c=1", now - 1) }
        assertFalse(FleetSession.sync()); assertEquals("", FleetSession.cookie)
    }
}
