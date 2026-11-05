package com.diegonmarcos.superapp.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.shellclient.ShellStatus

/**
 * The home status strip's ADB readout: ONE dot under the "ADB" label, the central shell broker's LIVE status
 * ([com.diegonmarcos.superapp.shellbroker.ShellBroker.status]: an `id -u` round trip through the real channel).
 * Green only when that round trip succeeded; red when it failed; grey before the first answer. The label itself still
 * follows USB / Wireless debugging. (Before: one dot per app, from the per-app channel design.)
 */
internal object AdbPeerDots {
    const val UP: Int = 0xFF4ADE80.toInt()
    const val DOWN: Int = 0xFFFF5A5F.toInt()   // strip-on-wallpaper red, deliberately not the status_light_off hex (T14 forbids copies)
    const val OFF: Int = 0x55FFFFFF

    /** [armed] null = not checked yet (grey). */
    fun tint(armed: Boolean?): Int = when (armed) { true -> UP; false -> DOWN; null -> OFF }

    fun tints(status: ShellStatus?): List<Int> = listOf(tint(status?.armed))

    /** The spoken form: the status line, which carries the reason and the check time. */
    fun describe(status: ShellStatus?): String = status?.line() ?: "checking"
}

/** The 2 dp dot(s) in the strip's own dot size (the same footprint class as SignalDots). */
@Composable
internal fun PeerDots(tints: List<Int>, description: String, modifier: Modifier = Modifier) {
    Row(modifier.padding(top = 1.dp).semantics { contentDescription = "ADB: $description" }) {
        tints.forEachIndexed { i, tint ->
            Box(Modifier.padding(start = if (i > 0) 2.dp else 0.dp).size(2.dp).background(Color(tint), CircleShape).testTag("strip:adb:dot:$i"))
        }
    }
}
