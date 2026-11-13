package com.diegonmarcos.cloudstore

import com.diegonmarcos.superapp.shellclient.ShellStatus

/** #1512 The lines Configs > Shell broker shows for the broker's state (pure, unit-tested). */
object ShellBrokerPage {
    const val ARM_TAG = "shell-broker-arm"

    fun lines(s: ShellStatus?): List<String> = when {
        s == null -> listOf("Checking the ADB shell of Cloud C3 Webserver...")
        !s.brokerInstalled -> listOf("Cloud C3 Webserver: not installed.", "Install Cloud C3 Webserver: it owns the one privileged shell. Without it installs show the system prompt.")
        s.armed -> listOf(s.line(), "Installs and updates carry no confirmation tap.")
        else -> listOf(s.line(), "Open Cloud C3 Webserver > ADB Shell to re-arm it.")
    }
}
