package com.diegonmarcos.superapp.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.shellclient.ShellStatus

/**
 * The home status strip's ADB readout: ONE dot under the "ADB" label, the central shell broker's LIVE status
 * ([com.diegonmarcos.superapp.shellbroker.ShellBroker.status]: an `id -u` round trip through the real channel).
 * Filled dot only when that round trip succeeded; outline dot when it failed; faint outline before the first answer (#1721: no red/green). The label itself still
 * follows USB / Wireless debugging. (Before: one dot per app, from the per-app channel design.)
 */
internal object AdbPeerDots {
    // #1721 no red/green: connected = a FILLED white dot, disconnected = an EMPTY (outline) white dot, not checked = a faint outline.
    // The three stay distinct values so the state is never lost; [isFilled] is what the drawing reads.
    const val UP: Int = 0xFFFFFFFF.toInt()
    const val DOWN: Int = 0xFFF0F0F0.toInt()
    const val OFF: Int = 0x55FFFFFF

    /** Filled only for a real "connected" verdict; every other state draws the outline. */
    fun isFilled(tint: Int): Boolean = tint == UP

    /** [armed] null = not checked yet (grey). */
    fun tint(armed: Boolean?): Int = when (armed) { true -> UP; false -> DOWN; null -> OFF }

    /** #1550 the ADB transport ([ShellStatus.adb]), never the broker's [ShellStatus.armed]: the local server outlives adb. */
    fun tints(status: ShellStatus?): List<Int> = listOf(tint(status?.adb?.verdict))

    /** The spoken form: the status line, which carries the reason and the check time. */
    fun describe(status: ShellStatus?): String = status?.let { it.adbLine() + "; " + it.line() } ?: "checking"
}

/** The 2 dp dot(s) in the strip's own dot size (the same footprint class as SignalDots). */
@Composable
internal fun PeerDots(tints: List<Int>, description: String, modifier: Modifier = Modifier, name: String = "adb") {
    // #1684 WebS / SSHd draw the same one-dot readout under their own name.
    Row(modifier.padding(top = 1.dp).semantics { contentDescription = "${if (name == "adb") "ADB" else name}: $description" }) {
        tints.forEachIndexed { i, tint ->
            val filled = AdbPeerDots.isFilled(tint)
            Box(Modifier.padding(start = if (i > 0) 2.dp else 0.dp).size(2.dp)
                .then(if (filled) Modifier.background(Color(tint), CircleShape) else Modifier.border(0.5.dp, Color(tint), CircleShape))
                .semantics { stateDescription = if (filled) "connected" else "disconnected" }
                .testTag("strip:$name:dot:$i"))
        }
    }
}
