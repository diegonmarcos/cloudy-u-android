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
        // The SuperApp's real ADB Shell page (Configs > ADB Shell, in the frame) through the launcher's own deep-link extra; the bare
        // lib screen only when that start is refused. (Before: always the bare lib screen, which is not the broker page.)
        val home = android.content.Intent().setClassName(packageName, "$packageName.MainActivity")
            .putExtra("shortcut_action", "page:config/adb-shell")
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or
                android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (runCatching { startActivity(home) }.isFailure) AdbShellLink.open(this)
        finish()
    }
}
