package com.diegonmarcos.cloudaccount

import com.diegonmarcos.superapp.shellclient.ShellStatus
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1512 The shell panel reports the SuperApp's broker; nothing about pairing or reconnecting lives in this app. */
class ShellBrokerLinesTest {
    @Test fun armed() = assertTrue(ShellBrokerLines.of(ShellStatus(true, true, "embedded-adb", "")).first().contains("armed"))
    @Test fun notArmedSaysWhy() = assertTrue(ShellBrokerLines.of(ShellStatus(true, false, "none", "arm it")).any { it.contains("arm it") })
    @Test fun missingBroker() = assertTrue(ShellBrokerLines.of(ShellStatus(false, false, "none", "")).any { it.contains("Install Cloud SuperApp") })
    @Test fun checking() = assertTrue(ShellBrokerLines.of(null).single().contains("Checking"))
}
