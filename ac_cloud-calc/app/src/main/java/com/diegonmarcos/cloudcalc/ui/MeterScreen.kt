package com.diegonmarcos.cloudcalc.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.cloudcalc.Declarations
import com.diegonmarcos.cloudcalc.R
import com.diegonmarcos.cloudcalc.audio.Mic
import com.diegonmarcos.cloudcalc.audio.SoundDecl
import com.diegonmarcos.cloudcalc.audio.SoundFlow
import com.diegonmarcos.cloudcalc.audio.SoundStore
import com.diegonmarcos.cloudcalc.sound.CymaticsMap
import com.diegonmarcos.cloudlib.cymatics.CymaticsPlate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * The sound meter and oscilloscope (#767, #772): waveform, log spectrum, level in dB (calibrated)
 * with A and C weighting, sample peak, the dominant frequency, the fundamental with its note and
 * cents, and its wavelength at the declared temperature. The microphone is asked for by MicGate
 * (ui/SoundScreens.kt) when this mode opens, and nowhere else.
 */
@Composable
fun MeterMode(mode: Declarations.Mode) {
    val meter = mode.meter ?: return
    MicGate { MeterLive(mode, meter) }
}

@Composable
private fun MeterLive(mode: Declarations.Mode, meter: Declarations.Meter) {
    val ctx = LocalContext.current
    val cfg = SoundDecl.config
    var reading by remember { mutableStateOf<SoundFlow.Reading?>(null) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(meter) {
        error = withContext(Dispatchers.Default) {
            runCatching {
                var last = 0L
                Mic.stream(ctx, cfg.sampleRate, meter.fftSize) { pcm ->
                    val now = System.currentTimeMillis()
                    if (now - last >= meter.refreshMs) {
                        last = now
                        reading = SoundFlow.reading(pcm, cfg.sampleRate, cfg, SoundStore.knobs(ctx))
                    }
                }
            }.exceptionOrNull()?.message.orEmpty()
        }
    }
    val r = reading
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(CalcMetrics.gutter)) {
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        Text(if (r == null) "—" else db(r.levelDb), style = MaterialTheme.typography.displayMedium, modifier = Modifier.testTag(CalcTags.RESULT))
        if (r != null) {
            if (meter.aWeighting) Text("${db(r.aDb)}(A) · ${db(r.cDb)}(C)", style = MaterialTheme.typography.headlineSmall)
            Fact(stringResource(R.string.sound_peak), "%.1f dBFS".format(r.peakDb))
            Fact(stringResource(R.string.sound_dominant), hz(r.dominantHz))
            Fact(stringResource(R.string.sound_fundamental), hz(r.f0) + "  " + noteText(r.note))
            Fact(stringResource(R.string.sound_wavelength), metres(r.wavelengthM))
            // #770 a reading is a result too: score it (asked about the level at the moment of asking).
            AskAboutResult(mode.id, mode.label, db(r.levelDb) + if (meter.aWeighting) ", ${db(r.aDb)}(A)" else "")
            Scope(r.pcm, r.pcm.size / 4)
        }
        Text(stringResource(R.string.meter_relative), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SoundKnobs()
        val bar = MaterialTheme.colorScheme.primary
        Canvas(Modifier.fillMaxWidth().height(CalcMetrics.spectrumHeight)) {
            val s = r?.spectrumDb ?: return@Canvas
            val floor = SoundFlow.FLOOR_DB
            // Log-spaced columns, one per pixel step: a linear axis spends 90% of the width above 2 kHz.
            val cols = max(1, (size.width / CalcMetrics.stroke.toPx()).toInt())
            val lo = Math.log10(20.0); val hi = Math.log10(cfg.sampleRate / 2.0)
            for (c in 0 until cols) {
                val f = Math.pow(10.0, lo + (hi - lo) * c / cols)
                val k = (f * s.size * 2 / cfg.sampleRate).toInt().coerceIn(1, s.size - 1)
                val h = ((s[k] - floor) / -floor).coerceIn(0.0, 1.0).toFloat() * size.height
                val x = c * size.width / cols
                drawLine(bar, Offset(x, size.height), Offset(x, size.height - h), strokeWidth = CalcMetrics.stroke.toPx())
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("20 Hz", style = MaterialTheme.typography.labelSmall)
            Text("%.0f kHz".format(cfg.sampleRate / 2000.0), style = MaterialTheme.typography.labelSmall)
        }
        // #1892 below the frequency graphic: the Cymatics figure of the live spectrum (the plate Cloud Canvas Play draws)
        MeterCymatics(r, cfg.sampleRate)
    }
}

/**
 * #1892 the Cymatics figure of the meter: the dominant frequency and the strongest partials of the live spectrum pick the plate's modes,
 * the level its amplitude (CymaticsMap, shared plate math in libs:cymatics-core, drawn by libs:cymatics), eased between readings so it
 * does not flicker. Silence is a flat plate.
 */
@Composable
private fun MeterCymatics(r: SoundFlow.Reading?, sampleRate: Int) {
    val grid = 64
    val smoother = remember { CymaticsMap.Smoother() }
    var drive by remember { mutableStateOf(CymaticsMap.SILENT) }
    var field by remember { mutableStateOf(FloatArray(0)) }
    LaunchedEffect(r) {
        val d = smoother.update(if (r == null) CymaticsMap.SILENT else CymaticsMap.drive(r.spectrumDb, sampleRate, r.levelDb))
        field = withContext(Dispatchers.Default) { CymaticsMap.field(d, grid) }
        drive = d
    }
    Text(stringResource(R.string.meter_cymatics), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = CalcMetrics.gutter))
    Box(Modifier.fillMaxWidth().testTag(CalcTags.METER_CYMATICS), contentAlignment = Alignment.Center) {
        CymaticsPlate(field, grid, Modifier.height(CalcMetrics.spectrumHeight * 2).aspectRatio(1f))
    }
    val lead = drive.partials.firstOrNull()
    Text(
        if (lead == null) stringResource(R.string.meter_cymatics_silent)
        else drive.partials.zip(drive.modes()).joinToString("  ·  ") { (p, m) -> "%.0f Hz → %d,%d".format(p.first, m.first, m.second) },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
