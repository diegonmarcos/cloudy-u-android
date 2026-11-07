package com.diegonmarcos.superapp.battery

import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitPalette
import kotlinx.coroutines.delay

/**
 * The home-screen TOP-RIGHT battery bubble (tap the battery icon). It draws
 * the battery SoT's report ([BatteryRepository.report]) through
 * [BatteryRows.popup] — the same values the Battery badge and Configs ›
 * About › Battery print, computed once — in the network popup's shape: bold
 * section headers, a full-width 1 dp divider between sections, scrolling,
 * capped at ~85% of the screen.
 *
 *   Battery                                   Battery stats ›
 *   NOW               level, state + source, current, power, rate (%/h · W),
 *                     to empty / to full, voltage, temperature, health
 *   ───────────────
 *   SINCE LAST CHARGE unplugged, used, average (%/h · W), to empty at avg,
 *                     screen on / off   (SINCE PLUGGED IN while on power)
 *
 * While open it re-reads every [REFRESH_MS], so the smoothed "now" settles in
 * front of the user; the refresh stops when the bubble closes.
 *
 * 2026-10-16 owner decision: all apps must be Compose. The bubble is a ComposeView in a
 * ComponentDialog (which carries the view-tree owners a bare PopupWindow lacks) pinned under the
 * anchor's right edge, under CloudKitTheme (libs:ui-kit) with the battery_* colour resources.
 */
object BatteryEstimatePopup {

    const val REFRESH_MS = 2_000L

    /** [openStats] opens Configs › About › Battery (the host app owns navigation); null hides the link. */
    fun show(ctx: Context, anchor: View, openStats: (() -> Unit)? = null, extra: (@Composable ColumnScope.() -> Unit)? = null) {
        val dm = ctx.resources.displayMetrics
        val d = dm.density
        val dialog = ComponentDialog(ctx)
        val view = ComposeView(ctx).apply {
            setContent {
                val palette = KitPalette(
                    surface = colorResource(R.color.battery_popup_bg),
                    surfaceSelected = colorResource(R.color.battery_pill),
                    textPrimary = colorResource(R.color.battery_text),
                    textSecondary = colorResource(R.color.battery_text_dim),
                    accent = colorResource(R.color.battery_link),
                    hairline = colorResource(R.color.battery_popup_stroke),
                    tileInk = colorResource(R.color.battery_bg),
                )
                CloudKitTheme(palette) {
                    Bubble(
                        minWidthDp = (dm.widthPixels * 0.5f / d).dp,
                        maxHeightDp = (dm.heightPixels * 0.85f / d).dp,
                        openStats = openStats?.let { go -> { dialog.dismiss(); go() } },
                        extra = extra,
                    )
                }
            }
        }
        dialog.setContentView(view, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            // The bubble's right edge on the icon's, so it extends leftward and stays on screen.
            val at = IntArray(2).also { anchor.getLocationOnScreen(it) }
            setGravity(Gravity.TOP or Gravity.END)
            attributes = attributes.apply {
                x = (dm.widthPixels - (at[0] + anchor.width)).coerceAtLeast(0)
                y = at[1] + anchor.height + (6 * d).toInt()
            }
        }
        dialog.show()
    }

    @Composable
    private fun Bubble(minWidthDp: androidx.compose.ui.unit.Dp, maxHeightDp: androidx.compose.ui.unit.Dp, openStats: (() -> Unit)?, extra: (@Composable ColumnScope.() -> Unit)? = null) {
        val ctx = LocalContext.current
        // Re-read every REFRESH_MS while the bubble is composed (it leaves composition when closed).
        var tick by remember { mutableIntStateOf(0) }
        LaunchedEffect(Unit) { while (true) { delay(REFRESH_MS); tick++ } }
        val report = remember(tick) { runCatching { BatteryRepository.report(ctx) }.getOrNull() }
        val white = colorResource(R.color.battery_text)
        val dim = colorResource(R.color.battery_text_dim)
        Column(
            Modifier.widthIn(min = minWidthDp).heightIn(max = maxHeightDp)
                .background(colorResource(R.color.battery_popup_bg), RoundedCornerShape(12.dp))
                .border(1.dp, colorResource(R.color.battery_popup_stroke), RoundedCornerShape(12.dp))
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Battery", color = white, fontSize = 16.sp, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (openStats != null) {
                    Text("Battery stats ›", color = colorResource(R.color.battery_link), fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { openStats() }
                            .padding(start = 10.dp, top = 4.dp, bottom = 4.dp))
                }
            }
            if (report == null) {
                Text("Battery state unavailable", color = white, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                return@Column
            }
            BatteryRows.popup(report).forEachIndexed { i, sec ->
                if (i > 0) {
                    // The full-width 1 dp line between two sections, with 8 dp above the next header.
                    Box(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp).height(1.dp)
                        .background(colorResource(R.color.battery_popup_divider)))
                }
                Text(sec.title, color = dim, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
                for (row in sec.rows) {
                    Text("${row.label}: ${row.value}", color = white, fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace)
                }
            }
            // The host app's own sections, after the battery SoT's (e.g. the since-last-charge usage from batterystats).
            extra?.invoke(this)
        }
    }
}
