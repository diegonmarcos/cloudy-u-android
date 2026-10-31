package com.diegonmarcos.cloudcalc.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudcalc.Declarations
import com.diegonmarcos.cloudcalc.audio.Mic
import com.diegonmarcos.cloudcalc.audio.SoundDecl
import com.diegonmarcos.cloudcalc.audio.SoundStore
import com.diegonmarcos.cloudcalc.sound.Dsp
import com.diegonmarcos.cloudcalc.sound.Generator
import com.diegonmarcos.cloudcalc.sound.Notes
import com.diegonmarcos.cloudcalc.tools.Calendars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Locale
import kotlin.math.ln

/**
 * #1377 Calc ▸ Tools: the screens a form names with `view` (build.json::ui.modes[].forms[].view). The World clock and
 * the tone generator are the Clock's and Measure ▸ Sound's own pages; Calendars and FFT are drawn here. The maths is
 * the :tools and :sound modules'.
 */
@Composable
fun ToolView(mode: Declarations.Mode, view: String) {
    when (view) {
        "world_clock" -> WorldClockMode(mode)
        "calendars" -> CalendarsView()
        "fft" -> FftView()
        "tone" -> SoundGeneratorMode(mode)
        else -> Text("no view $view", Modifier.padding(CalcMetrics.gutter))
    }
}

object ToolTags {
    const val CALENDARS = "tools_calendars"
    const val FFT = "tools_fft"
    fun calendar(id: String) = "tools_calendar_$id"
    fun fftSource(id: String) = "tools_fft_$id"
}

/**
 * The Fin forms' currency: the user's last pick, else the locale's; the list is the device's ISO 4217 table (offline).
 * Kept in the app's declared `cloud_calc` store (fleet-config.json), under its own key.
 */
object ToolPrefs {
    private const val FILE = "cloud_calc"
    private const val CURRENCY = "tools_currency"
    fun currency(ctx: Context): String = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(CURRENCY, null)
        ?: runCatching { java.util.Currency.getInstance(Locale.getDefault()).currencyCode }.getOrDefault("EUR")
    fun setCurrency(ctx: Context, code: String) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(CURRENCY, code).apply()
    val currencies: List<Declarations.Option> by lazy {
        java.util.Currency.getAvailableCurrencies().sortedBy { it.currencyCode }
            .map { Declarations.Option(it.currencyCode, it.currencyCode + " · " + it.getDisplayName(Locale.getDefault())) }
    }
}

// ── Time & Date ▸ Calendars ─────────────────────────────────────────────────────────────────

/** The Chinese lunisolar date from android.icu (an astronomical table no plain-JVM module carries); null if ICU refuses. */
private fun chineseOf(d: LocalDate): Calendars.Chinese? = runCatching {
    val g = android.icu.util.GregorianCalendar(d.year, d.monthValue - 1, d.dayOfMonth)
    val c = android.icu.util.ChineseCalendar().apply { timeInMillis = g.timeInMillis }
    Calendars.Chinese(c.get(android.icu.util.Calendar.YEAR), c.get(android.icu.util.Calendar.MONTH) + 1,
        c.get(android.icu.util.Calendar.IS_LEAP_MONTH) == 1, c.get(android.icu.util.Calendar.DAY_OF_MONTH))
}.getOrNull()

@Composable
private fun CalendarsView() {
    var text by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    val date = runCatching { LocalDate.parse(text.trim()) }.getOrNull()
    val entries = remember(date) { date?.let { Calendars.all(it, chineseOf(it)) }.orEmpty() }
    LazyColumn(Modifier.fillMaxSize().testTag(ToolTags.CALENDARS), contentPadding = PaddingValues(CalcMetrics.gutter)) {
        item {
            DateField(
                label = "Date", text = text, onText = { text = it }, tag = CalcTags.INPUT + "_calendar",
                rangeEnd = null, onEndText = {}, endTag = "", open = false, calendar = { false to null },
            )
            if (date == null) Text("Not a date: use YYYY-MM-DD", color = MaterialTheme.colorScheme.error)
        }
        items(entries, key = { it.id }) { e ->
            Column(Modifier.fillMaxWidth().padding(vertical = CalcMetrics.small).testTag(ToolTags.calendar(e.id))) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(e.name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                Text(e.value, style = MaterialTheme.typography.bodyLarge)
                Text(e.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
        }
    }
}

// ── Sound ▸ FFT ─────────────────────────────────────────────────────────────────────────────

private const val FFT_N = 4096
private const val FFT_REFRESH_MS = 100L

/** A live microphone spectrum (asked through MicGate, the app's one microphone door) or a synthetic two-tone signal. */
@Composable
private fun FftView() {
    var source by rememberSaveable { mutableStateOf("synthetic") }
    Column(Modifier.fillMaxSize().padding(CalcMetrics.gutter).testTag(ToolTags.FFT)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
            items(listOf("synthetic" to "Synthetic", "mic" to "Microphone")) { (id, label) ->
                FilterChip(selected = source == id, onClick = { source = id }, label = { Text(label) }, modifier = Modifier.testTag(ToolTags.fftSource(id)))
            }
        }
        if (source == "mic") MicGate { MicSpectrum() } else SyntheticSpectrum()
    }
}

@Composable
private fun MicSpectrum() {
    val ctx = LocalContext.current
    val rate = SoundDecl.config.sampleRate
    var spectrum by remember { mutableStateOf<DoubleArray?>(null) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        error = withContext(Dispatchers.Default) {
            runCatching {
                var last = 0L
                Mic.stream(ctx, rate, FFT_N) { pcm ->
                    val now = System.currentTimeMillis()
                    if (now - last >= FFT_REFRESH_MS) { last = now; spectrum = Dsp.spectrumDb(pcm, FFT_N) }
                }
            }.exceptionOrNull()?.message.orEmpty()
        }
    }
    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    SpectrumPanel(spectrum, rate, SoundStore.knobs(ctx).a4Hz)
}

@Composable
private fun SyntheticSpectrum() {
    val ctx = LocalContext.current
    val rate = SoundDecl.config.sampleRate
    var wave by rememberSaveable { mutableStateOf("sine") }
    var f1 by rememberSaveable { mutableStateOf("440") }
    var f2 by rememberSaveable { mutableStateOf("1000") }
    val num = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
        items(Generator.WAVES) { w -> FilterChip(selected = w == wave, onClick = { wave = w }, label = { Text(w) }) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
        OutlinedTextField(f1, { f1 = it }, Modifier.weight(1f), label = { Text("Tone 1 (Hz)") }, singleLine = true, keyboardOptions = num)
        OutlinedTextField(f2, { f2 = it }, Modifier.weight(1f), label = { Text("Tone 2 (Hz)") }, singleLine = true, keyboardOptions = num)
    }
    val a = f1.toDoubleOrNull()?.takeIf { it > 0 && it < rate / 2 }
    val b = f2.toDoubleOrNull()?.takeIf { it > 0 && it < rate / 2 }
    val spectrum = remember(wave, a, b, rate) {
        if (a == null || b == null) null
        else {
            // Two tones at half scale each, rendered by the Sound generator's own maths (no fade), then the same FFT the meter uses.
            val ms = FFT_N * 1000 / rate + 1
            val pcm = Generator.pcm16(Generator.render(Generator.Spec(Generator.DUAL, wave, a, b, 0.5, ms, Generator.COLOURS.first(), false), rate, 0))
            Dsp.spectrumDb(pcm, FFT_N)
        }
    }
    if (spectrum == null) Text("Tones must be between 0 and ${rate / 2} Hz", color = MaterialTheme.colorScheme.error)
    SpectrumPanel(spectrum, rate, SoundStore.knobs(ctx).a4Hz)
}

/** The spectrum on a log-frequency axis (20 Hz to Nyquist, -100..0 dBFS) and its peak, as Hz and as a note. */
@Composable
private fun SpectrumPanel(spectrum: DoubleArray?, rate: Int, a4: Double) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Canvas(Modifier.fillMaxWidth().height(220.dp).padding(vertical = CalcMetrics.gap)) {
            val lo = ln(20.0); val hi = ln(rate / 2.0)
            fun x(f: Double) = ((ln(f) - lo) / (hi - lo) * size.width).toFloat()
            fun y(db: Double) = (size.height * (-db.coerceIn(-100.0, 0.0) / 100)).toFloat()
            for (f in listOf(100.0, 1000.0, 10000.0)) drawLine(grid, Offset(x(f), 0f), Offset(x(f), size.height))
            for (db in listOf(-20.0, -40.0, -60.0, -80.0)) drawLine(grid, Offset(0f, y(db)), Offset(size.width, y(db)))
            val s = spectrum ?: return@Canvas
            val p = Path(); var pen = false
            for (k in 1 until s.size) {
                val f = k.toDouble() * rate / FFT_N
                if (f < 20) continue
                if (!pen) { p.moveTo(x(f), y(s[k])); pen = true } else p.lineTo(x(f), y(s[k]))
            }
            drawPath(p, line, style = Stroke(CalcMetrics.stroke.toPx()))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("20 Hz", "100", "1k", "10k", "${rate / 2000}k").forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = label) }
        }
        if (spectrum != null) {
            val peak = Dsp.peakHzInterpolated(spectrum, rate)
            Fact("Peak", hz(peak) + "  ·  " + "%.1f dBFS".format(spectrum[Dsp.peakBin(spectrum)]))
            Fact("Note", noteText(Notes.of(peak, a4)))
            Fact("Resolution", "%.1f Hz per bin (N = %d at %d Hz)".format(rate.toDouble() / FFT_N, FFT_N, rate))
        }
    }
}
