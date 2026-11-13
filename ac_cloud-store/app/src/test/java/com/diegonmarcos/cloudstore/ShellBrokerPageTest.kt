package com.diegonmarcos.cloudstore

import com.diegonmarcos.superapp.shellclient.ShellStatus
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1512 Configs > ADB Shell reports the SuperApp's shell broker, never a channel of the Store's own. */
class ShellBrokerPageTest {
    @Test fun anArmedBrokerSaysInstallsNeedNoTap() {
        val l = ShellBrokerPage.lines(ShellStatus(true, true, "embedded-adb", "armed", 1_000L))
        assertTrue(l.first().contains("up via") && l.first().contains("checked") && l.first().contains("embedded-adb"))
        assertTrue(l.any { it.contains("no confirmation") })
    }

    @Test fun anUnarmedBrokerSaysWhy() {
        val l = ShellBrokerPage.lines(ShellStatus(true, false, "none", "", 1_000L, "wireless debugging is off"))
        assertTrue(l.first().contains("down") && l.first().contains("wireless debugging is off") && l.first().contains("checked"))
    }

    @Test fun aMissingSuperAppSaysToInstallIt() {
        assertTrue(ShellBrokerPage.lines(ShellStatus(false, false, "none", "")).any { it.contains("Install Cloud C3 Webserver") })
    }

    @Test fun beforeTheFirstAnswerItSaysChecking() {
        assertTrue(ShellBrokerPage.lines(null).single().contains("Checking"))
    }
}
