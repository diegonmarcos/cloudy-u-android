package com.diegonmarcos.cloudme

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #1906 page:profile/profile (the SuperApp Me row's former Profile tile) still resolves to a live page: the settings section Profile. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ProfileReachableTest {
    init { BuroLlcTestData.installRenamed() }

    @Test fun `the profile deep link lands on the live profile page and the avatar section is declared`() {
        assertEquals("profile" to "profile", LegacyRoutes.resolve("profile", "profile"))
        val s = Sections.byId("profile")
        assertNotNull(s)
        assertTrue(s!!.path("profile").isNotEmpty())
        assertTrue(Sections.settings().any { it.id == "profile" })
    }
}
