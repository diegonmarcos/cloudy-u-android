package com.diegonmarcos.superapp.launcher

import com.diegonmarcos.superapp.apptabs.AppTabPrefs.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Side-split swipe-down and the "last Cloud app" pick, without an activity. */
class HomeSwipeRouterTest {
    @Test fun `down on the left half is Notify and on the right half is Quick Actions`() {
        val l = "page:communication/my-rss"; val r = "page:config/controls"
        assertEquals(l, HomeSwipeRouter.downAction(10f, 1080, l, r))
        assertEquals(l, HomeSwipeRouter.downAction(539f, 1080, l, r))
        assertEquals(r, HomeSwipeRouter.downAction(540f, 1080, l, r))
        assertEquals(r, HomeSwipeRouter.downAction(1070f, 1080, l, r))
    }

    @Test fun `the last Cloud app is the newest fleet app, not the newest Android app`() {
        val entries = listOf(
            Entry.ExternalAppEntry("com.android.camera", "Camera", 30),
            Entry.PageEntry("cloud", "apps", "Apps", "ic", 25),
            Entry.ExternalAppEntry("com.diegonmarcos.cloudcalc", "Calc", 20),
            Entry.ExternalAppEntry("com.diegonmarcos.cloudwallet", "Wallet", 10),
        )
        val fleet = setOf("com.diegonmarcos.cloudcalc", "com.diegonmarcos.cloudwallet")
        assertEquals("com.diegonmarcos.cloudcalc", HomeSwipeRouter.lastCloudApp(entries, fleet)!!.packageName)
        assertNull(HomeSwipeRouter.lastCloudApp(entries.take(2), fleet))
    }
}
