@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.diegonmarcos.cloudcalc.ui

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.core.content.ContextCompat
import com.diegonmarcos.cloudcalc.Declarations
import com.diegonmarcos.cloudcalc.R
import com.diegonmarcos.cloudcalc.clock.Alarm
import com.diegonmarcos.cloudcalc.clock.ClockData
import com.diegonmarcos.cloudcalc.clock.ClockDecl
import com.diegonmarcos.cloudcalc.clock.ClockEngine
import com.diegonmarcos.cloudcalc.clock.ClockLogic
import com.diegonmarcos.cloudcalc.clock.ClockNotifications
import com.diegonmarcos.cloudcalc.clock.TimerState
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/*
 * The Clock tab (#768): World clock, Alarms, Timers, Stopwatch, Interval and Bedtime. Every screen
 * reads ClockEngine's store and writes only through ClockEngine, the path the notification buttons,
 * the wakeups and /api/clock/… take too — so what a screen shows is what will ring.
 */

/** The store, re-read on every ClockEngine write. */
@Composable
private fun rememberClock(): ClockData {
    val ctx = LocalContext.current
    val version by ClockEngine.changes.collectAsState()
    return remember(version) { ClockEngine.load(ctx) }
}

/** ClockEngine.now(), refreshed every [periodMs] while [active]; a still screen does not tick. */
@Composable
private fun rememberNow(periodMs: Long, active: Boolean = true): Long {
    var now by remember { mutableLongStateOf(ClockEngine.now()) }
    LaunchedEffect(periodMs, active) {
        now = ClockEngine.now()
        while (active) {
            delay(periodMs)
            now = ClockEngine.now()
        }
    }
    return now
}

/**
 * POST_NOTIFICATIONS is asked for HERE and nowhere else (test/test-calc-shell.sh holds that), and
 * only when the user starts something that will need a notification — an alarm, a timer, the
 * stopwatch — never when a screen merely opens.
 */
@Composable
private fun rememberNotificationAsk(): () -> Unit {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val HHMMSS: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

private fun firstDay(): DayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek
private fun shortDay(d: DayOfWeek): String = d.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)

private fun durationWords(seconds: Long): String = when {
    seconds % 3600 == 0L -> "${seconds / 3600} h"
    seconds % 60 == 0L -> "${seconds / 60} min"
    else -> "$seconds s"
}

/** What rings now, answerable from any Clock screen. */
@Composable
private fun RingingBar(d: ClockData) {
    val ctx = LocalContext.current
    d.ringing.forEach { r ->
        Card(Modifier.fillMaxWidth().padding(bottom = CalcMetrics.gap)) {
            Row(Modifier.fillMaxWidth().padding(CalcMetrics.gutter), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    r.label.ifBlank { stringResource(if (r.key.startsWith("alarm:")) R.string.clock_alarm else R.string.clock_times_up) },
                    Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                )
                if (r.key.startsWith("alarm:")) TextButton(onClick = { ClockEngine.snooze(ctx, r.key) }) { Text(stringResource(R.string.clock_snooze)) }
                Button(onClick = { ClockEngine.dismiss(ctx, r.key) }) { Text(stringResource(R.string.clock_dismiss)) }
            }
        }
    }
}

// ── world clock ─────────────────────────────────────────────────────────────────────────────

@Composable
fun WorldClockMode(mode: Declarations.Mode) {
    val ctx = LocalContext.current
    val d = rememberClock()
    val now = rememberNow(ClockLogic.SECOND_MS)
    val here = ClockEngine.zone()
    val zones = d.zones ?: ClockDecl.zones
    var adding by rememberSaveable { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().testTag(ClockTags.WORLD), contentPadding = androidx.compose.foundation.layout.PaddingValues(CalcMetrics.gutter)) {
        item {
            val t = Instant.ofEpochMilli(now).atZone(here)
            Text(HHMMSS.format(t), style = MaterialTheme.typography.displayMedium, fontFamily = FontFamily.Monospace)
            Text(DAY.format(t) + "  ·  " + here.id, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(Modifier.padding(vertical = CalcMetrics.gap))
        }
        items(zones, key = { it }) { id ->
            val z = runCatching { ZoneId.of(id) }.getOrNull() ?: return@items
            val i = zones.indexOf(id)
            Row(Modifier.fillMaxWidth().padding(vertical = CalcMetrics.small), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(ClockLogic.city(id), style = MaterialTheme.typography.titleMedium)
                    val shift = when (ClockLogic.dayShift(z, here, now)) {
                        1 -> stringResource(R.string.clock_tomorrow) + ", "
                        -1 -> stringResource(R.string.clock_yesterday) + ", "
                        else -> ""
                    }
                    // #1377 the city's UTC offset and whether it is day or night there.
                    val sky = if (ClockLogic.isDay(z, now)) "☀ day" else "☾ night"
                    Text(shift + ClockLogic.offsetText(z, here, now) + "  ·  " + ClockLogic.utcText(z, now) + "  ·  " + sky, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(HHMM.format(Instant.ofEpochMilli(now).atZone(z)), style = MaterialTheme.typography.headlineMedium)
                TextButton(onClick = { if (i > 0) ClockEngine.setZones(ctx, zones.toMutableList().apply { add(i - 1, removeAt(i)) }) }, enabled = i > 0) { Text("↑") }
                TextButton(onClick = { if (i < zones.size - 1) ClockEngine.setZones(ctx, zones.toMutableList().apply { add(i + 1, removeAt(i)) }) }, enabled = i < zones.size - 1) { Text("↓") }
                TextButton(onClick = { ClockEngine.setZones(ctx, zones - id) }) { Text("✕") }
            }
        }
        item { OutlinedButton(onClick = { adding = true }, Modifier.padding(top = CalcMetrics.gap)) { Text(stringResource(R.string.clock_add_city)) } }
    }
    if (adding) ZonePicker(onDismiss = { adding = false }) { id ->
        adding = false
        if (id !in zones) ClockEngine.setZones(ctx, zones + id)
    }
}

@Composable
private fun ZonePicker(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    // The device's own tzdata: offline, and as current as the phone's last system update.
    val all = remember { ZoneId.getAvailableZoneIds().filter { '/' in it && !it.startsWith("Etc/") && !it.startsWith("SystemV/") }.sorted() }
    val shown = remember(query) { all.filter { query.isBlank() || ClockLogic.city(it).contains(query, true) || it.contains(query, true) }.take(200) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.clock_cancel)) } },
        title = { Text(stringResource(R.string.clock_add_city)) },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text(stringResource(R.string.clock_search_city)) })
                LazyColumn(Modifier.height(CalcMetrics.plotHeight)) {
                    items(shown, key = { it }) { id ->
                        Text(ClockLogic.city(id) + "  ·  " + id, Modifier.fillMaxWidth().clickable { onPick(id) }.padding(vertical = CalcMetrics.gap))
                    }
                }
            }
        },
    )
}

// ── alarms ──────────────────────────────────────────────────────────────────────────────────

@Composable
fun AlarmsMode(mode: Declarations.Mode) {
    val ctx = LocalContext.current
    val d = rememberClock()
    val now = rememberNow(ClockLogic.MINUTE_MS)
    val ask = rememberNotificationAsk()
    var editing by remember { mutableStateOf<Alarm?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(CalcMetrics.gutter).testTag(ClockTags.ALARMS)) {
        RingingBar(d)
        PermissionBanners()
        val next = ClockLogic.nextAlarm(d, now, ClockEngine.zone())
        Text(
            if (next == null) stringResource(R.string.clock_no_alarm)
            else stringResource(R.string.clock_next_alarm, inWords(next.second - now)),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        d.alarms.filter { !it.quiet }.sortedBy { it.minuteOfDay }.forEach { a ->
            Row(Modifier.fillMaxWidth().clickable { editing = a }.padding(vertical = CalcMetrics.gap), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(ClockLogic.timeText(a.minuteOfDay), style = MaterialTheme.typography.displaySmall,
                        color = if (a.enabled) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(listOf(a.label, ClockLogic.daysText(a.days, firstDay(), ::shortDay)).filter { it.isNotBlank() }.joinToString("  ·  ") +
                        (if (a.snoozedUntil > now) "  ·  " + stringResource(R.string.clock_snoozed_until, HHMM.format(Instant.ofEpochMilli(a.snoozedUntil).atZone(ClockEngine.zone()))) else ""),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = a.enabled, onCheckedChange = { on -> if (on) ask(); ClockEngine.saveAlarm(ctx, a.copy(enabled = on)) })
            }
            HorizontalDivider()
        }
        Button(onClick = {
            ask()
            editing = Alarm(0, 7 * 60, vibrate = ClockDecl.vibrateDefault, snoozeMinutes = ClockDecl.snoozeDefault)
        }, Modifier.padding(top = CalcMetrics.gutter).testTag(ClockTags.ADD_ALARM)) { Text(stringResource(R.string.clock_add_alarm)) }
    }
    editing?.let { a -> AlarmEditor(a, onDone = { editing = null }) }
}

/** "7 h 12 min" until a moment [ms] away. */
private fun inWords(ms: Long): String {
    val minutes = (ms + ClockLogic.MINUTE_MS - 1) / ClockLogic.MINUTE_MS
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h >= 24 -> "${h / 24} d ${h % 24} h"
        h > 0 -> "$h h $m min"
        else -> "$m min"
    }
}

/** Exact alarms and full-screen rings are the two grants an alarm needs; each missing one says so, with the switch. */
@Composable
private fun PermissionBanners() {
    val ctx = LocalContext.current
    if (!ClockEngine.canExact(ctx) && Build.VERSION.SDK_INT >= 31) {
        Banner(stringResource(R.string.clock_exact_off)) {
            ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    val fullScreen = Build.VERSION.SDK_INT < 34 ||
        runCatching { ctx.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() ?: true }.getOrDefault(true)
    if (!fullScreen) {
        Banner(stringResource(R.string.clock_full_screen_off)) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

@Composable
private fun Banner(text: String, onFix: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(bottom = CalcMetrics.gap)) {
        Column(Modifier.padding(CalcMetrics.gutter)) {
            Text(text, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { runCatching(onFix) }) { Text(stringResource(R.string.clock_allow)) }
        }
    }
}

@Composable
private fun AlarmEditor(initial: Alarm, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val time = rememberTimePickerState(initial.minuteOfDay / 60, initial.minuteOfDay % 60, is24Hour = true)
    var label by remember { mutableStateOf(initial.label) }
    var days by remember { mutableStateOf(initial.days) }
    var vibrate by remember { mutableStateOf(initial.vibrate) }
    var sound by remember { mutableStateOf(initial.sound) }
    var snooze by remember { mutableStateOf(initial.snoozeMinutes) }
    // The system's own ringtone picker: offline, every sound on the device, "None" included.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val uri = r.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            sound = uri?.toString() ?: Alarm.SILENT
        }
    }
    AlertDialog(
        onDismissRequest = onDone,
        confirmButton = {
            TextButton(onClick = {
                ClockEngine.saveAlarm(ctx, initial.copy(
                    minuteOfDay = time.hour * 60 + time.minute, label = label.trim(), days = days,
                    vibrate = vibrate, sound = sound, snoozeMinutes = snooze, enabled = true,
                ))
                onDone()
            }, Modifier.testTag(ClockTags.SAVE)) { Text(stringResource(R.string.clock_save)) }
        },
        dismissButton = {
            Row {
                if (initial.id != 0L) TextButton(onClick = { ClockEngine.deleteAlarm(ctx, initial.id); onDone() }) { Text(stringResource(R.string.clock_delete)) }
                TextButton(onClick = onDone) { Text(stringResource(R.string.clock_cancel)) }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                TimeInput(time)
                OutlinedTextField(label, { label = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.clock_label)) })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(CalcMetrics.small)) {
                    ClockLogic.week(firstDay()).forEach { day ->
                        val bit = ClockLogic.dayBit(day)
                        FilterChip(selected = days and bit != 0, onClick = { days = days xor bit }, label = { Text(shortDay(day)) })
                    }
                }
                Text(ClockLogic.daysText(days, firstDay(), ::shortDay), style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.clock_vibrate), Modifier.weight(1f))
                    Switch(checked = vibrate, onCheckedChange = { vibrate = it })
                }
                OutlinedButton(onClick = {
                    runCatching {
                        picker.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, if (sound.isBlank() || sound == Alarm.SILENT) null else Uri.parse(sound)))
                    }
                }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.clock_sound, soundTitle(ctx, sound))) }
                Text(stringResource(R.string.clock_snooze_minutes), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(CalcMetrics.small)) {
                    ClockDecl.snoozeChoices.forEach { m -> FilterChip(selected = m == snooze, onClick = { snooze = m }, label = { Text("$m min") }) }
                }
            }
        },
    )
}

private fun soundTitle(ctx: Context, sound: String): String = when {
    sound == Alarm.SILENT -> ctx.getString(R.string.clock_silent)
    sound.isBlank() -> ctx.getString(R.string.clock_default_sound)
    else -> runCatching { RingtoneManager.getRingtone(ctx, Uri.parse(sound))?.getTitle(ctx) }.getOrNull() ?: sound
}

// ── timers ──────────────────────────────────────────────────────────────────────────────────

@Composable
fun TimersMode(mode: Declarations.Mode) {
    val ctx = LocalContext.current
    val d = rememberClock()
    val now = rememberNow(250, d.timers.any { it.state == TimerState.RUNNING })
    val ask = rememberNotificationAsk()
    var h by rememberSaveable { mutableStateOf("0") }
    var m by rememberSaveable { mutableStateOf("5") }
    var s by rememberSaveable { mutableStateOf("0") }
    var label by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(CalcMetrics.gutter).testTag(ClockTags.TIMERS)) {
        RingingBar(d)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(CalcMetrics.small)) {
            ClockDecl.timerPresets.forEach { sec ->
                FilterChip(selected = false, onClick = {
                    ask()
                    ClockEngine.addTimer(ctx, label.trim().ifBlank { durationWords(sec) }, sec * ClockLogic.SECOND_MS, start = true)
                }, label = { Text(durationWords(sec)) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
            OutlinedTextField(h, { h = it.filter(Char::isDigit) }, Modifier.weight(1f), label = { Text("h") }, singleLine = true)
            OutlinedTextField(m, { m = it.filter(Char::isDigit) }, Modifier.weight(1f), label = { Text("min") }, singleLine = true)
            OutlinedTextField(s, { s = it.filter(Char::isDigit) }, Modifier.weight(1f), label = { Text("s") }, singleLine = true)
        }
        OutlinedTextField(label, { label = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.clock_label)) })
        val total = ((h.toLongOrNull() ?: 0) * 3600 + (m.toLongOrNull() ?: 0) * 60 + (s.toLongOrNull() ?: 0)) * ClockLogic.SECOND_MS
        Button(onClick = {
            ask()
            ClockEngine.addTimer(ctx, label.trim(), total, start = true)
        }, enabled = total > 0, modifier = Modifier.padding(vertical = CalcMetrics.gap).testTag(ClockTags.START_TIMER)) { Text(stringResource(R.string.clock_start)) }
        d.timers.forEach { t ->
            HorizontalDivider()
            Column(Modifier.fillMaxWidth().padding(vertical = CalcMetrics.gap)) {
                Text(t.label.ifBlank { stringResource(R.string.clock_timer) }, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (t.state == TimerState.RINGING) stringResource(R.string.clock_times_up) else ClockLogic.countdown(ClockLogic.remaining(t, now)),
                    style = MaterialTheme.typography.displaySmall, fontFamily = FontFamily.Monospace,
                    color = if (t.state == TimerState.RINGING) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(CalcMetrics.small)) {
                    when (t.state) {
                        TimerState.RUNNING -> FilledTonalButton(onClick = { ClockEngine.timer(ctx, t.id, "pause") }) { Text(stringResource(R.string.clock_pause)) }
                        TimerState.RINGING -> Button(onClick = { ClockEngine.dismiss(ctx, ClockLogic.timerKey(t.id)) }) { Text(stringResource(R.string.clock_dismiss)) }
                        else -> FilledTonalButton(onClick = { ask(); ClockEngine.timer(ctx, t.id, "start") }) { Text(stringResource(R.string.clock_start)) }
                    }
                    OutlinedButton(onClick = { ClockEngine.timer(ctx, t.id, "add") }) { Text(stringResource(R.string.clock_plus_minute)) }
                    OutlinedButton(onClick = { ClockEngine.timer(ctx, t.id, "reset") }) { Text(stringResource(R.string.clock_reset)) }
                    TextButton(onClick = { ClockEngine.timer(ctx, t.id, "delete") }) { Text(stringResource(R.string.clock_delete)) }
                }
            }
        }
    }
}

// ── stopwatch ───────────────────────────────────────────────────────────────────────────────

@Composable
fun StopwatchMode(mode: Declarations.Mode) {
    val ctx = LocalContext.current
    val d = rememberClock()
    val sw = d.stopwatch
    val now = rememberNow(47, sw.running)
    val ask = rememberNotificationAsk()
    val elapsed = ClockLogic.elapsed(sw, now)
    Column(Modifier.fillMaxSize().padding(CalcMetrics.gutter).testTag(ClockTags.STOPWATCH), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(ClockLogic.stopwatchText(elapsed), style = MaterialTheme.typography.displayMedium, fontFamily = FontFamily.Monospace)
        Row(horizontalArrangement = Arrangement.spacedBy(CalcMetrics.gap), modifier = Modifier.padding(vertical = CalcMetrics.gap)) {
            Button(onClick = { if (!sw.running) ask(); ClockEngine.stopwatch(ctx, "toggle") }) {
                Text(stringResource(if (sw.running) R.string.clock_pause else R.string.clock_start))
            }
            if (sw.running) FilledTonalButton(onClick = { ClockEngine.stopwatch(ctx, "lap") }) { Text(stringResource(R.string.clock_lap)) }
            else if (elapsed > 0) OutlinedButton(onClick = { ClockEngine.stopwatch(ctx, "reset") }) { Text(stringResource(R.string.clock_reset)) }
        }
        val laps = ClockLogic.lapTimes(sw)
        LazyColumn(Modifier.fillMaxWidth()) {
            items(laps.indices.reversed().toList()) { i ->
                Row(Modifier.fillMaxWidth().padding(vertical = CalcMetrics.small), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.clock_lap_n, i + 1))
                    Text(ClockLogic.stopwatchText(laps[i]), fontFamily = FontFamily.Monospace)
                    Text(ClockLogic.stopwatchText(sw.laps[i]), fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ── interval / Pomodoro ─────────────────────────────────────────────────────────────────────

@Composable
fun IntervalMode(mode: Declarations.Mode) {
    val ctx = LocalContext.current
    val d = rememberClock()
    val i = d.interval
    val now = rememberNow(250, i != null)
    val ask = rememberNotificationAsk()
    var work by rememberSaveable { mutableStateOf("25") }
    var rest by rememberSaveable { mutableStateOf("5") }
    var rounds by rememberSaveable { mutableStateOf("4") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(CalcMetrics.gutter).testTag(ClockTags.INTERVAL)) {
        RingingBar(d)
        val phase = i?.let { ClockLogic.phaseAt(it, now) }
        if (i != null && phase != null) {
            Text(ClockNotifications.phaseName(ctx, phase.kind), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.clock_round, phase.round, i.rounds) + "  ·  " + i.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(ClockLogic.countdown(phase.endsAt - now), style = MaterialTheme.typography.displayMedium, fontFamily = FontFamily.Monospace)
            Text(stringResource(R.string.clock_session_left, ClockLogic.countdown(ClockLogic.phases(i).last().endsAt - now)), style = MaterialTheme.typography.bodySmall)
            Button(onClick = { ClockEngine.stopInterval(ctx) }, Modifier.padding(top = CalcMetrics.gap)) { Text(stringResource(R.string.clock_stop)) }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(CalcMetrics.small)) {
                ClockDecl.intervalPresets.forEach { p ->
                    FilterChip(selected = false, onClick = { ask(); ClockEngine.startInterval(ctx, p) }, label = { Text(p.label) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
                OutlinedTextField(work, { work = it.filter(Char::isDigit) }, Modifier.weight(1f), label = { Text(stringResource(R.string.clock_work_min)) }, singleLine = true)
                OutlinedTextField(rest, { rest = it.filter(Char::isDigit) }, Modifier.weight(1f), label = { Text(stringResource(R.string.clock_rest_min)) }, singleLine = true)
                OutlinedTextField(rounds, { rounds = it.filter(Char::isDigit) }, Modifier.weight(1f), label = { Text(stringResource(R.string.clock_rounds)) }, singleLine = true)
            }
            val w = work.toLongOrNull() ?: 0
            val n = rounds.toIntOrNull() ?: 0
            Button(onClick = {
                ask()
                ClockEngine.startInterval(ctx, ClockDecl.IntervalPreset(ctx.getString(R.string.clock_custom), w * 60, (rest.toLongOrNull() ?: 0) * 60, 0, 0, n))
            }, enabled = w > 0 && n > 0, modifier = Modifier.padding(top = CalcMetrics.gap)) { Text(stringResource(R.string.clock_start)) }
        }
    }
}

// ── bedtime / sleep timer ───────────────────────────────────────────────────────────────────

@Composable
fun BedtimeMode(mode: Declarations.Mode) {
    val ctx = LocalContext.current
    val d = rememberClock()
    val now = rememberNow(ClockLogic.SECOND_MS, d.sleepUntil > 0)
    val ask = rememberNotificationAsk()
    val bed = d.alarms.firstOrNull { it.quiet }
    var time by remember(bed?.minuteOfDay) { mutableStateOf(ClockLogic.timeText(bed?.minuteOfDay ?: ClockDecl.bedtimeDefault)) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(CalcMetrics.gutter).testTag(ClockTags.BEDTIME)) {
        Text(stringResource(R.string.clock_sleep_timer), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.clock_sleep_timer_doc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (d.sleepUntil > 0) {
            Text(ClockLogic.countdown(d.sleepUntil - now), style = MaterialTheme.typography.displaySmall, fontFamily = FontFamily.Monospace)
            OutlinedButton(onClick = { ClockEngine.cancelSleep(ctx) }) { Text(stringResource(R.string.clock_cancel)) }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(CalcMetrics.small)) {
                ClockDecl.sleepChoices.forEach { m -> FilterChip(selected = false, onClick = { ask(); ClockEngine.startSleep(ctx, m) }, label = { Text("$m min") }) }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = CalcMetrics.gutter))
        Text(stringResource(R.string.clock_bedtime), style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(time, { time = it }, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.clock_bedtime_at)) },
                isError = ClockLogic.parseTime(time) == null)
            Switch(
                checked = bed?.enabled == true,
                onCheckedChange = { on ->
                    ClockLogic.parseTime(time)?.let { minute ->
                        if (on) ask()
                        ClockEngine.saveAlarm(ctx, (bed ?: Alarm(0, minute, days = ClockLogic.ALL_DAYS, quiet = true, label = ctx.getString(R.string.clock_bedtime)))
                            .copy(minuteOfDay = minute, enabled = on))
                    }
                },
                modifier = Modifier.padding(start = CalcMetrics.gap),
            )
        }
        ClockLogic.sleepWindow(d, now, ClockEngine.zone())?.let { (from, to) ->
            Text(
                stringResource(R.string.clock_sleep_window,
                    HHMM.format(Instant.ofEpochMilli(from).atZone(ClockEngine.zone())),
                    HHMM.format(Instant.ofEpochMilli(to).atZone(ClockEngine.zone())),
                    inWords(to - from)),
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = CalcMetrics.gap),
            )
        }
    }
}

/** Test tags of the Clock screens. */
object ClockTags {
    const val WORLD = "clock_world"
    const val ALARMS = "clock_alarms"
    const val ADD_ALARM = "clock_add_alarm"
    const val SAVE = "clock_save"
    const val TIMERS = "clock_timers"
    const val START_TIMER = "clock_start_timer"
    const val STOPWATCH = "clock_stopwatch"
    const val INTERVAL = "clock_interval"
    const val BEDTIME = "clock_bedtime"
}
