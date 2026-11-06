package org.fossify.phone.configs

import org.fossify.phone.BuildConfig
import org.fossify.phone.sim.SimStatus
import org.fossify.phone.sim.SimUsage
import org.fossify.phone.sim.StatusFormat
import org.fossify.phone.sim.UsagePeriod
import org.fossify.phone.sim.UsageTotals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1415 the one Configs, its top-nav pages, the bottom nav, and the runtime classes the SIM pages need. */
class DialerConfigsTest {
    /** sha-61326f78 crashed with NoClassDefFoundError MasterKey$Builder: libs:crash links security-crypto compileOnly. */
    @Test fun securityCryptoIsOnTheAppsRuntimeClasspath() {
        for (cls in listOf(
            "androidx.security.crypto.MasterKey\$Builder",
            "androidx.security.crypto.EncryptedSharedPreferences",
            "com.diegonmarcos.superapp.crash.SafeEncryptedPrefs",
        )) {
            assertTrue("$cls missing", runCatching { Class.forName(cls, false, javaClass.classLoader) }.isSuccess)
        }
    }

    @Test fun configsPagesAreTheDeclaredTopNavInOrder() {
        val pages = DialerConfigsPages.parse(BuildConfig.CONFIGS_TOP_NAV)
        assertEquals(DialerConfigsPages.ORDER, pages.map { it.id })
        assertEquals(listOf("General", "Perms", "About", "SIM", "Status", "Usage"), pages.map { it.label })
        assertTrue(pages.all { it.icon.isNotBlank() })
    }

    @Test fun bottomNavOrder() {
        assertEquals("sms,keypad,recents,home,voip,sims,contacts", BuildConfig.UI_BOTTOM_NAV)
    }

    @Test fun keypadVoipAndSimsAreNotPagerPages() {
        assertEquals(setOf("keypad", "voip", "sims"), org.fossify.phone.nav.DialerNav.NON_PAGER)
    }

    @Test fun tablesHaveOneCellPerHeaderColumn() {
        val s = SimStatus(0, "X", 0, "Op", "LTE", -90, null, null, null, null, 2, emptyList(), emptyList(), emptyList(), null, false, true, 0, null, null, 1L)
        StatusFormat.table(listOf(s)).forEach { assertEquals(StatusFormat.TABLE_HEADER.size, it.size) }
        SimUsage.table(listOf(UsagePeriod("Today", 0, 1) to UsageTotals(callsIn = 1, minutesIn = 2))).forEach {
            assertEquals(SimUsage.TABLE_HEADER.size, it.size)
            assertEquals("1", it[1])
        }
    }
}
