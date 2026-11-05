package com.diegonmarcos.cloudstore

import com.diegonmarcos.superapp.shellclient.ShellStatus
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1512 Configs > ADB Shell reports the SuperApp's shell broker, never a channel of the Store's own. */
class ShellBrokerPageTest {
    @Test fun anArmedBrokerSaysInstallsNeedNoTap() {
        val l = ShellBrokerPage.lines(ShellStatus(true, true, "embedded-adb", "armed"))
        assertTrue(l.first().contains("armed") && l.first().contains("embedded-adb"))
        assertTrue(l.any { it.contains("no confirmation") })
    }

    @Test fun anUnarmedBrokerSaysWhy() {
        val l = ShellBrokerPage.lines(ShellStatus(true, false, "none", "not armed: arm it in the SuperApp"))
        assertTrue(l.first().contains("not armed") && l.any { it.contains("arm it in the SuperApp") })
    }

    @Test fun aMissingSuperAppSaysToInstallIt() {
        assertTrue(ShellBrokerPage.lines(ShellStatus(false, false, "none", "")).any { it.contains("Install Cloud SuperApp") })
    }

    @Test fun beforeTheFirstAnswerItSaysChecking() {
        assertTrue(ShellBrokerPage.lines(null).single().contains("Checking"))
    }
}
