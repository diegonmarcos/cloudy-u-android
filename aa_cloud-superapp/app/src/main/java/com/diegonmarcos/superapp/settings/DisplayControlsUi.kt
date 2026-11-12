package com.diegonmarcos.superapp.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import com.diegonmarcos.superapp.shellclient.ShellClient
import com.diegonmarcos.superapp.system.QuickDisplay
import com.diegonmarcos.superapp.system.SystemDisplay
import com.diegonmarcos.superapp.ui.Haptics
import com.diegonmarcos.superapp.uikit.KitSectionHeader
import com.diegonmarcos.superapp.uikit.KitStoreCaption
import com.diegonmarcos.superapp.uikit.KitStoreName
import com.diegonmarcos.superapp.uikit.KitStoreQuick
import com.diegonmarcos.superapp.uikit.KitStoreRow
import com.diegonmarcos.superapp.uikit.KitStoreRowLine
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import java.util.Locale
import kotlin.math.roundToInt

/** A wrapping status line (the kit caption is single-line and would cut a command). */
@Composable
private fun Info(text: String, tag: String, color: Color, mono: Boolean = false) {
    Text(text, color = color, fontSize = KitStoreRow.caption, fontFamily = if (mono) FontFamily.Monospace else null,
        modifier = Modifier.fillMaxWidth().padding(horizontal = KitStoreRow.padH, vertical = KitStoreRow.tight).testTag(tag))
}

/**
 * Quick Actions > UI: Cloud-superapp Scale (ruler centred on the declared 411 dp), Font Size and Zoom Size, each with live status.
 * Scale is in-process (no ADB); Font and Zoom are device-wide and write through [QuickDisplay], with a `needs ADB` state and the
 * exact command when no privileged path exists. Ranges, steps and presets all come from build.json::ui.launcher_settings.display_controls.
 */
@Composable
internal fun DisplayControlsSection() {
    val ctx = LocalContext.current
    val view = LocalView.current
    val p = LocalKitPalette.current
    val scale = QuickDisplay.scaleSpec
    val font = QuickDisplay.fontSpec
    val zoom = QuickDisplay.zoomSpec

    Column(Modifier.fillMaxWidth().testTag("display-controls")) {
        // ── Cloud-superapp Scale ─────────────────────────────────────────
        Spacer(Modifier.height(KitStoreRow.step(16)))
        KitSectionHeader(scale.label, scale.subtitle, Modifier.testTag("display:scale:header"))
        val override = (QuickDisplay.swOverride(ctx))
        val appSw = (QuickDisplay.appSw(ctx))
        var sel by remember { mutableIntStateOf((if (override > 0) override else appSw).coerceIn(scale.min, scale.max)) }
        Info(String.format(Locale.US, "This app: %d %s (density %d dpi)%s · Device: %d %s (density %d dpi)",
            appSw, scale.unit, ctx.resources.displayMetrics.densityDpi, if (override > 0) " · set by you" else " · no override",
            QuickDisplay.deviceSw(), scale.unit, QuickDisplay.deviceDensity()), "display:scale:status", p.textSecondary)
        KitStoreRowLine {
            KitStoreName("$sel ${scale.unit}", Modifier.testTag("display:scale:readout"), p.accent)
            Spacer(Modifier.width(KitStoreRow.gap))
            KitStoreCaption(String.format(Locale.US, "%+d from %d", sel - scale.center, scale.center), color = p.textSecondary)
        }
        val tickLow = p.textSecondary
        val tickMid = p.textPrimary
        val tickCentre = p.accent
        val thumbInset = KitStoreRow.step(14)
        val hair = KitStoreRow.step(1)
        val bold = KitStoreRow.step(3)
        Canvas(Modifier.fillMaxWidth().height(KitStoreRow.step(16)).padding(horizontal = KitStoreRow.padH + thumbInset).testTag("display:scale:ruler")) {
            val span = (scale.max - scale.min).toFloat().coerceAtLeast(1f)
            var v = scale.center - ((scale.center - scale.min) / scale.tickMinor) * scale.tickMinor
            while (v <= scale.max) {
                val x = (v - scale.min) / span * size.width
                val centre = v == scale.center
                val major = (v - scale.center) % scale.tickMajor == 0
                val len = if (centre) size.height else if (major) size.height * 0.6f else size.height * 0.35f
                drawLine(if (centre) tickCentre else if (major) tickMid else tickLow, Offset(x, size.height - len), Offset(x, size.height),
                    strokeWidth = if (centre) bold.toPx() else hair.toPx())
                v += scale.tickMinor
            }
        }
        Slider(
            value = sel.toFloat(),
            onValueChange = { sel = it.roundToInt().coerceIn(scale.min, scale.max) },
            onValueChangeFinished = {
                QuickDisplay.setSwOverride(ctx, sel); SystemDisplay.applyScale(ctx)
            },
            valueRange = scale.min.toFloat()..scale.max.toFloat(),
            modifier = Modifier.fillMaxWidth().padding(horizontal = KitStoreRow.padH).testTag("display:scale:slider"),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = KitStoreRow.padH + thumbInset)) {
            Text("${scale.min}", color = p.textSecondary, fontSize = KitStoreRow.caption, modifier = Modifier.weight(1f))
            Text("${scale.center}", color = p.accent, fontSize = KitStoreRow.caption, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            Text("${scale.max}", color = p.textSecondary, fontSize = KitStoreRow.caption, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }
        KitStoreRowLine(Modifier.testTag("display:scale:presets")) {
            for (pr in scale.presets) {
                KitStoreQuick(pr.label, if (override == pr.value) KitStoreRow.UP else KitStoreRow.BLUE_FILL, Modifier.testTag("display:scale:preset:${pr.value}")) {
                    Haptics.tap(view); sel = pr.value.coerceIn(scale.min, scale.max)
                    QuickDisplay.setSwOverride(ctx, pr.value); SystemDisplay.applyScale(ctx)
                }
                Spacer(Modifier.width(KitStoreRow.gap))
            }
            KitStoreQuick("Reset", KitStoreRow.AMBER_FILL, Modifier.testTag("display:scale:reset")) {
                Haptics.tap(view); QuickDisplay.setSwOverride(ctx, 0); SystemDisplay.applyScale(ctx)
            }
        }

        // ── Font Size ────────────────────────────────────────────────────
        val fontNow = (QuickDisplay.fontScale(ctx))
        val fontPath = if (android.provider.Settings.System.canWrite(ctx)) "WRITE_SETTINGS granted"
            else if (com.diegonmarcos.superapp.system.AdbReadiness.usableNow(ctx)) "ADB shell connected" else "needs ADB"
        StepControl(font, "font", QuickDisplay.nearest(font.steps, fontNow),
            String.format(Locale.US, "Now %.2fx (%s) · key font_scale · write path: %s", fontNow, font.nameOf(QuickDisplay.nearest(font.steps, fontNow)), fontPath),
            { String.format(Locale.US, "%.2fx", it) }) { i -> QuickDisplay.writeFont(ctx, font.steps[i]) }

        // ── Zoom Size ────────────────────────────────────────────────────
        val stable = QuickDisplay.stableDensity()
        val dens = (QuickDisplay.deviceDensity())
        val zoomPath = if (com.diegonmarcos.superapp.system.AdbReadiness.usableNow(ctx)) "ADB shell connected" else "needs ADB"
        val zi = QuickDisplay.nearest(zoom.steps, dens.toFloat() / stable)
        StepControl(zoom, "zoom", zi,
            String.format(Locale.US, "Now %d dpi = %.2fx of %d stable (%s) · forced: %s · smallest width %d dp · write path: %s",
                dens, dens.toFloat() / stable, stable, zoom.nameOf(zi), QuickDisplay.forcedDensity(ctx)?.toString() ?: "none",
                QuickDisplay.deviceSw(), zoomPath),
            { String.format(Locale.US, "%d dpi", QuickDisplay.densityForFactor(stable, it)) }) { i ->
            QuickDisplay.writeDensity(ctx, QuickDisplay.densityForFactor(stable, zoom.steps[i]))
        }
    }
}

/** One Font/Zoom row: header, live status, a button per declared step (name over value), and the write outcome with the ADB fallback. */
@Composable
private fun StepControl(
    spec: QuickDisplay.StepSpec, id: String, current: Int, status: String, valueOf: (Float) -> String,
    write: (Int) -> QuickDisplay.Write,
) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val p = LocalKitPalette.current
    var outcome by remember { mutableStateOf<QuickDisplay.Write?>(null) }
    var busy by remember { mutableStateOf(false) }
    Spacer(Modifier.height(KitStoreRow.step(16)))
    KitSectionHeader(spec.label, spec.subtitle, Modifier.testTag("display:$id:header"))
    Info(status, "display:$id:status", p.textSecondary)
    Row(Modifier.fillMaxWidth().padding(horizontal = KitStoreRow.padH).testTag("display:$id:steps"), horizontalArrangement = Arrangement.spacedBy(KitStoreRow.tight)) {
        spec.steps.forEachIndexed { i, f ->
            Column(Modifier.weight(1f), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                KitStoreQuick(spec.nameOf(i), if (i == current) KitStoreRow.UP else KitStoreRow.BLUE_FILL, Modifier.fillMaxWidth().testTag("display:$id:step:$i")) {
                    if (busy) return@KitStoreQuick
                    Haptics.tap(view); busy = true
                    Thread({ try { outcome = write(i) } finally { busy = false } }, "display-$id").start()
                }
                KitStoreCaption(valueOf(f), color = p.textSecondary, align = TextAlign.Center)
            }
        }
    }
    when (val o = outcome) {
        null -> Unit
        QuickDisplay.Write.Done -> Info("Applied.", "display:$id:result", KitStoreRow.UP)
        is QuickDisplay.Write.Failed -> Info("Not applied: ${o.note}.", "display:$id:result", KitStoreRow.BAD)
        is QuickDisplay.Write.NeedsAdb -> {
            Info("Needs ADB: no WRITE_SETTINGS grant and no shell channel. Run:", "display:$id:result", KitStoreRow.UPDATE)
            Info(o.command, "display:$id:command", p.textPrimary, mono = true)
            KitStoreRowLine { KitStoreQuick("Open ADB Shell", KitStoreRow.BLUE_FILL, Modifier.testTag("display:$id:adb")) { Haptics.tap(view); ShellClient.openArmPage(ctx) } }
        }
    }
}
