package com.diegonmarcos.superapp.shellbroker

import android.app.Activity
import android.os.Bundle
import com.diegonmarcos.superapp.adbdebug.AdbShellLink

/**
 * #1510 "Arm in SuperApp": the target of ShellClient.arm. Opens the SuperApp's ADB Shell page (pair, connect, Wireless debugging
 * switch) and finishes. Exported behind the signature permission, so only a fleet app can send the owner here.
 */
class ShellArmActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AdbShellLink.open(this)
        finish()
    }
}
