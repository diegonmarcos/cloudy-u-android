package com.diegonmarcos.superapp.launcher

import com.diegonmarcos.superapp.notificationcenter.BadgeCardData
import com.diegonmarcos.superapp.notificationcenter.BadgeDeclaration
import com.diegonmarcos.superapp.notificationcenter.BadgeServices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1894 parity: the playing Media badge carries every field it did before, and no idle block. */
class MediaBadgePlayingParityTest {
    @Test fun `Data keeps every earlier field and the new idle block is off by default`() {
        val names = BadgeCardData.Data::class.java.declaredFields.map { it.name }.toSet()
        for (kept in listOf("badge", "appName", "headline", "sub", "progress", "lines", "live", "note", "whenMs", "open", "actions", "subText", "albumArt"))
            assertTrue("$kept was removed from the badge data", kept in names)
        assertTrue("idle" in names)
    }

    @Test fun `a badge built the playing way has no idle block`() {
        val b = BadgeDeclaration.badges(BadgeServices.declared).first { it.id == "media_now_playing" }
        val d = NotifyFixtures.badge(b)
        assertNull(d.idle)
        assertEquals(true, d.live)
    }
}
