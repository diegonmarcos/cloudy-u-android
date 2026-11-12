package com.diegonmarcos.superapp.appstore

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1681 Cloud lists only fleet apps, Phone only the others: one classifier, disjoint and complete, against the real fleet manifest. */
class StoreMembershipTest {
    private fun fleetFile(): File = listOf("aa_cloud-superapp/data/constellation-fleet.json", "../aa_cloud-superapp/data/constellation-fleet.json",
        "../../../aa_cloud-superapp/data/constellation-fleet.json").map(::File).firstOrNull { it.exists() }
        ?: error("constellation-fleet.json not found from ${File(".").absolutePath}")

    private fun realFleet(): Set<String> {
        val apps = JSONObject(fleetFile().readText()).getJSONArray("apps")
        return StoreMembership.fleetPackagesOf((0 until apps.length()).map { apps.getJSONObject(it) }
            .map { it.getString("package") to it.optString("alt_id").ifEmpty { null } })
    }

    @Test fun altIdsAreFleetAndBlanksAreIgnored() {
        val fleet = StoreMembership.fleetPackagesOf(listOf("com.diegonmarcos.chat" to "com.mattermost.rnbeta", " " to null, "cld.camera" to ""))
        assertEquals(setOf("com.diegonmarcos.chat", "com.mattermost.rnbeta", "cld.camera"), fleet)
        assertTrue(StoreMembership.isCloud("com.mattermost.rnbeta", fleet))
        assertTrue(StoreMembership.isPhone("org.mozilla.firefox", fleet))
        // a package-less fleet reference row is the Cloud page's, never a phone app
        assertEquals(StoreMembership.Tab.CLOUD, StoreMembership.tabOf("", fleet))
    }

    @Test fun theTwoTabsAreDisjointAndCompleteOverTheRealFleet() {
        val fleet = realFleet()
        assertTrue("the real fleet manifest names packages", fleet.size >= 20)
        val phoneApps = listOf("org.mozilla.firefox", "com.whatsapp", "org.telegram.messenger", "com.android.settings", "com.google.android.youtube")
        val everyShown = fleet + phoneApps
        val split = StoreMembership.split(everyShown, fleet)
        assertTrue("disjoint: ${split.cloud intersect split.phone}", (split.cloud intersect split.phone).isEmpty())
        assertEquals("complete", everyShown, split.cloud + split.phone)
        assertEquals("Cloud is exactly the fleet", fleet, split.cloud)
        assertEquals("Phone is exactly the rest", phoneApps.toSet(), split.phone)
    }

    @Test fun phoneOnlyDropsEveryFleetPackageAndKeepsTheRest() {
        val fleet = realFleet()
        val launchable = (fleet.associateWith { "fleet $it" }) + mapOf("org.videolan.vlc" to "VLC", "com.spotify.music" to "Spotify")
        val phone = StoreMembership.phoneOnly(launchable, fleet)
        assertEquals(setOf("org.videolan.vlc", "com.spotify.music"), phone.keys)
        assertFalse(phone.keys.any { it in fleet })
    }
}
