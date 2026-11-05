package com.diegonmarcos.cloudstore

import com.diegonmarcos.superapp.shellclient.ShellStatus

/** #1512 The lines Configs > Shell broker shows for the broker's state (pure, unit-tested). */
object ShellBrokerPage {
    const val ARM_TAG = "shell-broker-arm"

    fun lines(s: ShellStatus?): List<String> = when {
        s == null -> listOf("Checking the SuperApp's shell broker...")
        !s.brokerInstalled -> listOf("Broker: not installed.", "Install Cloud SuperApp: it owns the one privileged shell. Without it installs show the system prompt.")
        s.armed -> listOf("Broker: armed (${s.channel}).", "Installs and updates carry no confirmation tap.")
        else -> listOf("Broker: reachable, not armed.", s.detail.ifBlank { "Arm it in the SuperApp." })
    }
}
