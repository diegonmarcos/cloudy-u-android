package com.diegonmarcos.superapp.ui
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.App
import com.diegonmarcos.superapp.system.AppProcessUptime
import com.diegonmarcos.superapp.system.StatusDropdowns
import com.diegonmarcos.superapp.uikit.KitStoreRow

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.View
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * System info popup — shown when the user taps the RAM% or Storage%
 * icon in the right status-strip cluster. Mirrors the battery popup
 * layout: dark-glass bubble, monospace, anchored under the tapped
 * icon. Headline is the device model so the user can read it at a
 * glance.
 *
 * Header: Device, Android, App, Uptime. Body: one block per group declared in
 * build.json::home_status_bar.dropdowns[right_system], headed by its status-bar icon
 * (Memory, Storage, CPU, Graphics, Running Services, Last 3h Memory Usage); see
 * system/StatusDropdowns.
 */
object SystemInfoPopup {

    private val DIM = Color(0xAAFFFFFF)

    fun show(ctx: Context, anchor: View) {
        KitPopup.show(anchor, KitPopup.Align.END) { SystemInfoBubble(ctx) }
    }

    /** Under a Compose anchor whose window bounds are [rect]. */
    fun show(ctx: Context, host: View, rect: androidx.compose.ui.unit.IntRect) {
        KitPopup.show(host, rect, KitPopup.Align.END) { SystemInfoBubble(ctx) }
    }

    /** The bubble itself, split from [show] so it can be drawn without a window. */
    @Composable
    internal fun SystemInfoBubble(ctx: Context) {
        // Capped below the screen and scrolling: the grouped body is longer than one screenful (Running Services, 3 h memory).
        val maxH = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
        KitBubble(Modifier.heightIn(max = maxH).testTag("sysinfo:bubble"), scroll = rememberScrollState()) {
            Cap("Device", color = DIM); Cap("${Build.MANUFACTURER} · ${Build.MODEL}", bold = true)
            Cap("Android", color = DIM); Cap("${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            Cap("App", color = DIM); Cap("${BuildConfig.VERSION_NAME} · ${BuildConfig.GIT_SHORT_SHA} · ${BuildConfig.BUILD_TIMESTAMP}")
            Cap("Uptime", color = DIM); Cap(readUptime())
            // One block per declared group, headed by its status-bar icon (build.json::home_status_bar.dropdowns[right_system]).
            StatusDropdowns.SystemGroupsView(ctx)
        }
    }

    @Composable
    private fun Cap(t: String, color: Color = Color.White, bold: Boolean = false) =
        Text(t, color = color, fontSize = KitStoreRow.caption, fontFamily = FontFamily.Monospace, fontWeight = if (bold) FontWeight.Bold else null)

    private fun readUptime(): String {
        // AppProcessUptime exposes startedAtElapsed (an absolute
        // elapsed-realtime anchor); compute the delta to "now" here.
        // SystemClock.elapsedRealtime ticks through Doze so the count
        // doesn't reset on screen-off.
        val ms = (SystemClock.elapsedRealtime() - AppProcessUptime.startedAtElapsed)
            .coerceAtLeast(0L)
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return when {
            h > 0 -> "%d h %02d min".format(h, m)
            m > 0 -> "%d min %02d s".format(m, sec)
            else  -> "%d s".format(sec)
        }
    }
}
