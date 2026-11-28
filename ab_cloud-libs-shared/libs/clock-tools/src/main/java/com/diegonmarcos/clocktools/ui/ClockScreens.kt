@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.diegonmarcos.clocktools.ui

import com.diegonmarcos.superapp.perms.PermsFixChip
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import com.diegonmarcos.clocktools.ClockLook
import kotlinx.coroutines.launch
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.diegonmarcos.clocktools.ClockCalendarProvider
import com.diegonmarcos.clocktools.ClockDesign
import com.diegonmarcos.clocktools.ClockService
import com.diegonmarcos.clocktools.Pomodoro
import com.diegonmarcos.clocktools.PomodoroLogic
import com.diegonmarcos.clocktools.PomodoroPhase
import com.diegonmarcos.clocktools.TimerPresets
import com.diegonmarcos.clocktools.Wheel
import com.diegonmarcos.superapp.perms.PermsChip
import com.diegonmarcos.superapp.perms.rememberPermsAsk
import com.diegonmarcos.superapp.perms.rememberRawPermAsk
import java.time.LocalDate
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
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
import com.diegonmarcos.clocktools.Alarm
import com.diegonmarcos.clocktools.ClockConfig
import com.diegonmarcos.clocktools.ClockData
import com.diegonmarcos.clocktools.ClockEngine
import com.diegonmarcos.clocktools.ClockLogic
import com.diegonmarcos.clocktools.ClockNotifications
import com.diegonmarcos.clocktools.ClockTools
import com.diegonmarcos.clocktools.R
import com.diegonmarcos.clocktools.Stopwatch
import com.diegonmarcos.clocktools.TimerState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/*
 * The Clock tab (#768): World clock, Alarms, Timers, Stopwatch, Interval, Bedtime and (#1970) Pomodoro. Every screen
 * reads ClockEngine's store and writes only through ClockEngine, the path the notification buttons,
 * the wakeups and /api/clock/… take too — so what a screen shows is what will ring.
 */

/** The dimensions the clock screens use (Cloud Calc's CalcMetrics values, #768). */
internal object ClockMetrics {
    val small: Dp = 4.dp
    val gap: Dp = 8.dp
    val gutter: Dp = 12.dp
    val plotHeight: Dp = 280.dp
    val dayBar: Dp = 14.dp
}

/** The store, re-read on every ClockEngine write. */
@Composable
internal fun rememberClock(): ClockData {
    val ctx = LocalContext.current
    val version by ClockEngine.changes.collectAsState()
    return remember(version) { ClockEngine.load(ctx) }
}

/** ClockEngine.now(), refreshed every [periodMs] while [active]; a still screen does not tick. */
@Composable
internal fun rememberNow(periodMs: Long, active: Boolean = true): Long {
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

internal val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
internal val HHMMSS: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
internal val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

private fun firstDay(): DayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek
private fun shortDay(d: DayOfWeek): String = d.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)

private fun durationWords(seconds: Long): String = when {
    seconds % 3600 == 0L -> "${seconds / 3600} h"
    seconds % 60 == 0L -> "${seconds / 60} min"
    else -> "$seconds s"
}

/** What rings now, answerable from any Clock screen. */
@Composable
internal fun RingingBar(d: ClockData) {
    val ctx = LocalContext.current
    d.ringing.forEach { r ->
        Card(Modifier.fillMaxWidth().padding(bottom = ClockMetrics.gap)) {
            Row(Modifier.fillMaxWidth().padding(ClockMetrics.gutter), verticalAlignment = Alignment.CenterVertically) {
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

// ── alarms ──────────────────────────────────────────────────────────────────────────────────

@Composable
fun AlarmsScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val d = rememberClock()
    val now = rememberNow(ClockLogic.MINUTE_MS)
    var editing by remember { mutableStateOf<Alarm?>(null) }
    // #1986 the reference's dark page: each alarm a dark card, the lilac switch, Add alarm a lilac pill
    ClockFrame(modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ClockMetrics.gutter).testTag(ClockTags.ALARMS)) {
        RingingBar(d)
        PermissionBanners()
        val next = ClockLogic.nextAlarm(d, now, ClockEngine.zone())
        Text(
            if (next == null) stringResource(R.string.clock_no_alarm)
            else stringResource(R.string.clock_next_alarm, inWords(next.second - now)),
            style = MaterialTheme.typography.bodyMedium, color = look(ClockLook.LABEL), modifier = Modifier.padding(vertical = ClockMetrics.gap),
        )
        // #1970 alarm groups: each group under its name, Unassigned last; one list as before when no alarm has a group.
        val all = d.alarms.filter { !it.quiet }
        val grouped = all.any { it.group.isNotBlank() }
        all.groupBy { it.group }.toSortedMap(compareBy<String>({ it.isBlank() }, { it })).forEach { (g, list) ->
        if (grouped) Text(g.ifBlank { stringResource(R.string.clock_unassigned) }, style = MaterialTheme.typography.titleSmall,
            color = look(ClockLook.ACCENT), modifier = Modifier.padding(top = ClockMetrics.gutter, bottom = ClockMetrics.small).testTag(ClockTags.group(g)))
        list.sortedBy { it.minuteOfDay }.forEach { a ->
            Row(Modifier.fillMaxWidth().padding(bottom = ClockMetrics.gap).clockCard().clickable { editing = a }.padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(ClockLogic.timeText(a.minuteOfDay), style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.SemiBold),
                        color = if (a.enabled) look(ClockLook.TITLE) else look(ClockLook.HINT))
                    Text(listOf(a.label, ClockLogic.daysText(a.days, firstDay(), ::shortDay)).filter { it.isNotBlank() }.joinToString("  ·  ") +
                        (if (a.calendar) "  ·  " + stringResource(R.string.clock_add_to_calendar) else "") +
                        (if (a.snoozedUntil > now) "  ·  " + stringResource(R.string.clock_snoozed_until, HHMM.format(Instant.ofEpochMilli(a.snoozedUntil).atZone(ClockEngine.zone()))) else ""),
                        style = MaterialTheme.typography.bodySmall, color = look(ClockLook.DETAIL))
                }
                ClockSwitch(checked = a.enabled, onCheckedChange = { on -> ClockEngine.saveAlarm(ctx, a.copy(enabled = on)) })
            }
        }
        }
        Button(onClick = {
            val cfg = ClockTools.config(ctx)
            editing = Alarm(0, 7 * 60, vibrate = cfg.vibrateDefault, snoozeMinutes = cfg.snoozeDefault)
        }, Modifier.padding(top = ClockMetrics.gutter).align(Alignment.CenterHorizontally).testTag(ClockTags.ADD_ALARM),
            colors = ButtonDefaults.buttonColors(containerColor = look(ClockLook.ACCENT), contentColor = look(ClockLook.ON_DAY))) { Text(stringResource(R.string.clock_add_alarm)) }
    }
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
    // #1515 notifications and exact alarms are asked and granted in Configs > Perms only; this is the chip that points there.
    PermsFixChip(listOf("notifications", "exact_alarm"), "clock", Modifier.padding(bottom = ClockMetrics.gap))
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
    Card(Modifier.fillMaxWidth().padding(bottom = ClockMetrics.gap)) {
        Column(Modifier.padding(ClockMetrics.gutter)) {
            Text(text, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { runCatching(onFix) }) { Text(stringResource(R.string.clock_allow)) }
        }
    }
}

/**
 * #1970 / #1986 the alarm editor as the reference: hour : minute wheels on black, a dark rounded sheet of every setting
 * (repeat days, name, Sound / Vibration / Snooze with their switches behind a hairline, groups, the ring background), the
 * floating Cancel | Save pill. Positions are the reference's, measured from the window's top and bottom.
 */
@Composable
private fun AlarmEditor(initial: Alarm, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val d = rememberClock()
    var hour by remember { mutableIntStateOf(initial.minuteOfDay / 60) }
    var minute by remember { mutableIntStateOf(initial.minuteOfDay % 60) }
    var label by remember { mutableStateOf(initial.label) }
    var days by remember { mutableIntStateOf(initial.days) }
    var date by remember { mutableLongStateOf(initial.date) }
    var vibrate by remember { mutableStateOf(initial.vibrate) }
    var vibration by remember { mutableStateOf(initial.vibration) }
    var sound by remember { mutableStateOf(initial.sound) }
    // The sound the switch brings back: the last one picked (the system alarm sound until one is).
    var lastSound by remember { mutableStateOf(initial.sound.takeIf { it != Alarm.SILENT }.orEmpty()) }
    var snooze by remember { mutableIntStateOf(initial.snoozeMinutes) }
    var snoozeOn by remember { mutableStateOf(initial.snoozeOn) }
    var snoozeTimes by remember { mutableIntStateOf(initial.snoozeTimes) }
    var group by remember { mutableStateOf(initial.group) }
    var background by remember { mutableStateOf(initial.background) }
    var calendar by remember { mutableStateOf(initial.calendar) }
    var detail by remember { mutableStateOf<String?>(null) }
    var granted by remember { mutableStateOf(ClockCalendarProvider.granted(ctx)) }
    // WRITE_CALENDAR is asked only when "Add to calendar" is switched on, through libs:perms (the one place that asks).
    val askPerms = rememberPermsAsk { granted = ClockCalendarProvider.granted(ctx) }
    val askRaw = rememberRawPermAsk { granted = ClockCalendarProvider.granted(ctx) }
    // The system's own ringtone picker: offline, every sound on the device, "None" included.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val uri = r.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            sound = uri?.toString() ?: Alarm.SILENT
            if (sound != Alarm.SILENT) lastSound = sound
        }
    }
    fun pickSound() = runCatching {
        picker.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, if (sound.isBlank() || sound == Alarm.SILENT) null else Uri.parse(sound)))
    }
    fun setCalendar(on: Boolean) {
        calendar = on
        if (on && !ClockCalendarProvider.granted(ctx)) {
            val missing = PermsChip.missing(ctx, ClockCalendarProvider.PERMS)
            if (missing.isNotEmpty()) askPerms(missing) else askRaw(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
        }
    }
    val big = MaterialTheme.typography.displayLarge.copy(fontSize = ClockLook.ALARM_DIGITS_SP.sp, fontWeight = FontWeight.Bold)
    val title = MaterialTheme.typography.titleMedium.copy(fontSize = ClockLook.ROW_TITLE_SP.sp, lineHeight = 17.sp, fontWeight = FontWeight.Normal)
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ClockFrame(Modifier.fillMaxSize().testTag(ClockTags.ALARM_EDITOR), glow = false) {
            // the wheels: their middle row 189 dp from the top, 88 dp rows, the two columns 163 dp apart
            Row(Modifier.align(Alignment.TopCenter).padding(top = 57.dp), verticalAlignment = Alignment.CenterVertically) {
                WheelPicker(hour, 0, 23, { hour = it }, Modifier.width(120.dp), rowHeight = ClockLook.ALARM_ROW.dp, style = big, tag = ClockTags.ALARM_HOUR,
                    color = look(ClockLook.DIGITS_WHITE), dim = ClockLook.WHEEL_DIM)
                WheelColon(big, look(ClockLook.DIGITS_WHITE), Modifier.width((ClockLook.ALARM_COLUMN - 120).dp).padding(bottom = 6.dp).wrapContentWidth())
                WheelPicker(minute, 0, 59, { minute = it }, Modifier.width(120.dp), rowHeight = ClockLook.ALARM_ROW.dp, style = big, tag = ClockTags.ALARM_MINUTE,
                    color = look(ClockLook.DIGITS_WHITE), dim = ClockLook.WHEEL_DIM)
            }
            // the sheet: 362 dp from the top to 63 dp above the bottom, 8 dp from the sides
            Column(
                Modifier.fillMaxSize().padding(start = ClockLook.SHEET_MARGIN.dp, end = ClockLook.SHEET_MARGIN.dp, top = 362.dp, bottom = 63.dp)
                    .clip(RoundedCornerShape(ClockLook.SHEET_RADIUS.dp)).background(look(ClockLook.SHEET))
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(Modifier.fillMaxWidth().height(35.dp).padding(start = ClockLook.SHEET_INSET.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(repeatText(days, date), Modifier.weight(1f).padding(top = 6.dp), style = title.copy(fontSize = ClockLook.HEADER_SP.sp), color = look(ClockLook.TITLE))
                    IconButton(onClick = { detail = "date" }, Modifier.size(32.dp).padding(top = 6.dp).testTag(ClockTags.ALARM_DATE)) { Box(Modifier.size(17.dp)) { CalendarGlyph() } }
                }
                Row(Modifier.fillMaxWidth().height(37.dp).padding(horizontal = 8.5.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    ClockLogic.week(firstDay()).forEach { day ->
                        val bit = ClockLogic.dayBit(day)
                        DayChip(shortDay(day).take(1), days and bit != 0, Modifier.testTag(ClockTags.day(day.name))) {
                            days = days xor bit
                            if (days != 0) date = -1
                        }
                    }
                }
                // Alarm name: a hint over an underline, the reference's plain field
                BasicTextField(label, { label = it }, Modifier.fillMaxWidth().height(45.dp).padding(horizontal = ClockLook.SHEET_INSET.dp).testTag(ClockTags.ALARM_NAME),
                    singleLine = true, textStyle = title.copy(fontSize = ClockLook.NAME_SP.sp, color = look(ClockLook.TITLE)),
                    cursorBrush = SolidColor(look(ClockLook.ACCENT)),
                    decorationBox = { field ->
                        Box(Modifier.fillMaxSize().drawBehind {
                            drawLine(look(ClockLook.UNDERLINE), Offset(0f, size.height - 0.5.dp.toPx()), Offset(size.width, size.height - 0.5.dp.toPx()), 1.dp.toPx())
                        }.padding(bottom = 8.dp), contentAlignment = Alignment.BottomStart) {
                            if (label.isEmpty()) Text(stringResource(R.string.clock_alarm_name), style = title.copy(fontSize = ClockLook.NAME_SP.sp), color = look(ClockLook.HINT))
                            field()
                        }
                    })
                Box(Modifier.height(19.dp))
                SheetRow(stringResource(R.string.clock_sound_row), if (sound == Alarm.SILENT) stringResource(R.string.clock_off) else soundTitle(ctx, sound),
                    sound != Alarm.SILENT, { on -> sound = if (on) lastSound else Alarm.SILENT }, ClockTags.ROW_SOUND) { pickSound() }
                SheetRow(stringResource(R.string.clock_vibration), if (vibrate) ClockDesign.vibrationLabel(vibration) else stringResource(R.string.clock_off),
                    vibrate, { vibrate = it }, ClockTags.ROW_VIBRATION) { detail = "vibration" }
                SheetRow(stringResource(R.string.clock_snooze_minutes), if (snoozeOn) ClockDesign.snoozeText(snooze, snoozeTimes) else stringResource(R.string.clock_off),
                    snoozeOn, { snoozeOn = it }, ClockTags.ROW_SNOOZE) { detail = "snooze" }
                SheetRow(stringResource(R.string.clock_alarm_groups), group.ifBlank { stringResource(R.string.clock_unassigned) }, null, null, ClockTags.ROW_GROUP) { detail = "group" }
                Text(stringResource(R.string.clock_alarm_background), style = title, color = look(ClockLook.TITLE),
                    modifier = Modifier.padding(start = ClockLook.SHEET_INSET.dp, top = 12.dp))
                BackgroundPreview(background, Wheel.minuteOfDay(hour, minute), label, Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp))
                Row(Modifier.fillMaxWidth().padding(vertical = ClockMetrics.gutter), horizontalArrangement = Arrangement.spacedBy(ClockMetrics.gap, Alignment.CenterHorizontally)) {
                    ClockDesign.BACKGROUNDS.forEach { id ->
                        Box(Modifier.size(30.dp).clip(CircleShape).background(ringBackground(id, MaterialTheme.colorScheme))
                            .border(if (id == background) 2.dp else 1.dp, look(if (id == background) ClockLook.ACCENT else ClockLook.DIVIDER), CircleShape)
                            .clickable { background = id }.testTag(ClockTags.background(id)))
                    }
                }
                // #1970 Add to calendar, past the reference's rows
                SheetRow(stringResource(R.string.clock_add_to_calendar),
                    stringResource(if (calendar && !granted) R.string.clock_calendar_needs_grant else R.string.clock_add_to_calendar_doc),
                    calendar, { setCalendar(it) }, ClockTags.ROW_CALENDAR) { setCalendar(!calendar) }
                if (initial.id != 0L) TextButton(onClick = { ClockEngine.deleteAlarm(ctx, initial.id); onDone() }, Modifier.padding(start = 4.dp).testTag(ClockTags.DELETE_ALARM)) {
                    Text(stringResource(R.string.clock_delete), color = MaterialTheme.colorScheme.error)
                }
                Box(Modifier.height(36.dp))
            }
            // the floating Cancel | Save pill, 20 dp above the bottom
            ClockBar(Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp)) {
                val bar = MaterialTheme.typography.titleMedium.copy(fontSize = ClockLook.BAR_SP.sp, fontWeight = FontWeight.SemiBold)
                Box(Modifier.weight(1f).fillMaxHeight().clickable(onClick = onDone).testTag(ClockTags.CANCEL), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.clock_cancel), style = bar, color = look(ClockLook.BAR_TEXT))
                }
                VerticalDivider(Modifier.height(13.dp), thickness = 1.dp, color = look(ClockLook.BAR_DIVIDER))
                Box(Modifier.weight(1f).fillMaxHeight().clickable {
                    ClockEngine.saveAlarm(ctx, initial.copy(
                        minuteOfDay = Wheel.minuteOfDay(hour, minute), label = label.trim(), days = days,
                        vibrate = vibrate, sound = sound, snoozeMinutes = snooze, enabled = true,
                        snoozeOn = snoozeOn, snoozeTimes = snoozeTimes, snoozeCount = 0, vibration = vibration, group = group.trim(),
                        background = background, date = if (days == 0) date else -1, calendar = calendar,
                    ))
                    onDone()
                }.testTag(ClockTags.SAVE), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.clock_save), style = bar, color = look(ClockLook.BAR_TEXT))
                }
            }
        }
    }
    when (detail) {
        "vibration" -> ChoiceDialog(stringResource(R.string.clock_vibration), { detail = null }) {
            ClockDesign.VIBRATIONS.forEach { (id, v) ->
                Row(Modifier.fillMaxWidth().clickable { vibration = id; vibrate = true; ClockService.buzz(ctx, v.second) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = id == vibration, onClick = { vibration = id; vibrate = true; ClockService.buzz(ctx, v.second) })
                    Text(v.first)
                }
            }
        }
        "snooze" -> ChoiceDialog(stringResource(R.string.clock_snooze_minutes), { detail = null }) {
            Text(stringResource(R.string.clock_interval_min), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(ClockMetrics.small)) {
                ClockTools.config(ctx).snoozeChoices.forEach { m -> FilterChip(selected = m == snooze, onClick = { snooze = m; snoozeOn = true }, label = { Text("$m min") }) }
            }
            Text(stringResource(R.string.clock_times), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(ClockMetrics.small)) {
                ClockDesign.SNOOZE_TIMES.forEach { n ->
                    FilterChip(selected = n == snoozeTimes, onClick = { snoozeTimes = n; snoozeOn = true },
                        label = { Text(if (n == 0) stringResource(R.string.clock_unlimited) else "$n") })
                }
            }
        }
        "group" -> {
            var typed by remember { mutableStateOf("") }
            ChoiceDialog(stringResource(R.string.clock_alarm_groups), { detail = null }) {
                (listOf("") + d.alarms.map { it.group }.filter { it.isNotBlank() }.distinct().sorted()).forEach { g ->
                    Row(Modifier.fillMaxWidth().clickable { group = g }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = g == group, onClick = { group = g })
                        Text(g.ifBlank { stringResource(R.string.clock_unassigned) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(typed, { typed = it }, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.clock_new_group)) })
                    TextButton(onClick = { if (typed.isNotBlank()) { group = typed.trim(); detail = null } }) { Text(stringResource(R.string.clock_ok)) }
                }
            }
        }
        "date" -> {
            val state = rememberDatePickerState(initialSelectedDateMillis = if (date >= 0) date * 86_400_000L else null)
            DatePickerDialog(
                onDismissRequest = { detail = null },
                confirmButton = {
                    TextButton(onClick = {
                        state.selectedDateMillis?.let { date = it / 86_400_000L; days = 0 }
                        detail = null
                    }) { Text(stringResource(R.string.clock_ok)) }
                },
                dismissButton = { TextButton(onClick = { date = -1; detail = null }) { Text(stringResource(R.string.clock_clear_date)) } },
            ) { DatePicker(state) }
        }
    }
}

/** "Every day", "Weekdays", "Mon Wed", or the picked date of a one-off ("Fri 27 Nov"). */
private fun repeatText(days: Int, date: Long): String =
    if (days and ClockLogic.ALL_DAYS == 0 && date >= 0) DAY.format(LocalDate.ofEpochDay(date)) else ClockLogic.daysText(days, firstDay(), ::shortDay)

@Composable
private fun DayChip(letter: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    // #1986 the reference's filled lilac circle
    DayCircle(letter, selected, modifier, onClick)
}

/**
 * A sheet row (49 dp, as the reference): title, its detail line, and (when [checked] is given) a hairline and the slim
 * switch. A tap opens the detail. A divider closes it.
 */
@Composable
private fun SheetRow(title: String, detail: String, checked: Boolean?, onToggle: ((Boolean) -> Unit)?, tag: String, onClick: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().height(ClockLook.ROW_H.dp).clickable(onClick = onClick).padding(start = ClockLook.SHEET_INSET.dp, end = 14.dp).testTag(tag),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium.copy(fontSize = ClockLook.ROW_TITLE_SP.sp, lineHeight = 17.sp, fontWeight = FontWeight.Normal),
                    color = look(ClockLook.TITLE))
                Text(detail, style = MaterialTheme.typography.bodySmall.copy(fontSize = ClockLook.ROW_DETAIL_SP.sp, lineHeight = 13.sp), color = look(ClockLook.DETAIL),
                    maxLines = 1)
            }
            if (checked != null && onToggle != null) {
                VerticalDivider(Modifier.height(17.dp), thickness = 1.dp, color = look(ClockLook.SWITCH_DIVIDER))
                Box(Modifier.width(7.7.dp))
                ClockSwitch(checked = checked, onCheckedChange = onToggle)
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 12.dp), thickness = 0.8.dp, color = look(ClockLook.DIVIDER))
    }
}

/** The ring screen as it will look (the reference's small purple card): "Alarm", the time and today's date. */
@Composable
private fun BackgroundPreview(id: String, minuteOfDay: Int, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier.size(82.dp, 178.dp).clip(RoundedCornerShape(6.dp)).background(ringBackground(id, MaterialTheme.colorScheme)).testTag(ClockTags.BACKGROUND_PREVIEW),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label.ifBlank { stringResource(R.string.clock_alarm) }, style = MaterialTheme.typography.labelSmall.copy(fontSize = 4.5.sp, lineHeight = 6.sp),
            color = look(ClockLook.TITLE), modifier = Modifier.padding(top = 18.dp), maxLines = 1)
        Text(ClockLogic.timeText(minuteOfDay), style = MaterialTheme.typography.headlineMedium.copy(fontSize = 15.sp, lineHeight = 18.sp), fontWeight = FontWeight.SemiBold,
            color = look(ClockLook.TITLE))
        Text(DAY.format(Instant.ofEpochMilli(ClockEngine.now()).atZone(ClockEngine.zone())), style = MaterialTheme.typography.labelSmall.copy(fontSize = 3.5.sp, lineHeight = 5.sp),
            color = look(ClockLook.TITLE))
    }
}

@Composable
private fun ChoiceDialog(title: String, onDone: () -> Unit, content: @Composable () -> Unit) {
    AlertDialog(
        onDismissRequest = onDone,
        confirmButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.clock_ok)) } },
        title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(ClockMetrics.small)) { content() } },
    )
}

private fun soundTitle(ctx: Context, sound: String): String = when {
    sound == Alarm.SILENT -> ctx.getString(R.string.clock_silent)
    sound.isBlank() -> ctx.getString(R.string.clock_default_sound)
    else -> runCatching { RingtoneManager.getRingtone(ctx, Uri.parse(sound))?.getTitle(ctx) }.getOrNull() ?: sound
}

// ── timers ──────────────────────────────────────────────────────────────────────────────────

/**
 * #1970 / #1986 the Timer as the reference: the ⋮ top right, Hours / Minutes / Seconds over three wheels (dim neighbours, a
 * lilac middle row), the three preset pills (tap sets the wheels, long-press edits), the round Start low on the page. Running
 * timers, the label and the quick-start chips follow below the first screen (scroll), each running timer as a dark card.
 */
@Composable
fun TimersScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val d = rememberClock()
    val now = rememberNow(250, d.timers.any { it.state == TimerState.RUNNING })
    var h by rememberSaveable { mutableIntStateOf(0) }
    var m by rememberSaveable { mutableIntStateOf(0) }   // #1986 opens on 00:00:00, as the reference
    var s by rememberSaveable { mutableIntStateOf(0) }
    var label by rememberSaveable { mutableStateOf("") }
    var editPreset by remember { mutableStateOf<Int?>(null) }
    val presets = TimerPresets.effective(d.wheelPresets, ClockTools.config(ctx).wheelPresets)
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val digits = MaterialTheme.typography.displayMedium.copy(fontSize = ClockLook.TIMER_DIGITS_SP.sp, fontWeight = FontWeight.SemiBold)
    ClockFrame(modifier.fillMaxSize()) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val page = maxHeight.coerceAtLeast(500.dp)
    Column(Modifier.fillMaxSize().verticalScroll(scroll).testTag(ClockTags.TIMERS), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(page)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().height(48.dp)) {
                    ClockOverflow(presets.mapIndexed { i, sec -> stringResource(R.string.clock_edit_preset) + " " + TimerPresets.text(sec) to { editPreset = i } } +
                        listOf(stringResource(R.string.clock_label) + " / " + stringResource(R.string.clock_quick_start) to { scope.launch { scroll.animateScrollTo(scroll.maxValue) }; Unit }),
                        Modifier.align(Alignment.CenterEnd).padding(end = 2.dp))
                }
                Column(Modifier.padding(horizontal = ClockMetrics.gutter)) {
                    RingingBar(d)
                    PermsFixChip(listOf("notifications"), "clock", Modifier.padding(bottom = ClockMetrics.gap))
                }
                Box(Modifier.height(11.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    val w = (ClockLook.TIMER_COLUMN - 23).dp
                    LabelledWheel(stringResource(R.string.clock_hours), h, 0, 99, { h = it }, tag = ClockTags.WHEEL_H, rowHeight = ClockLook.TIMER_ROW.dp, style = digits, width = w)
                    Box(Modifier.width(23.dp).height((ClockLook.TIMER_ROW * 3).dp), contentAlignment = Alignment.Center) { WheelColon(digits, look(ClockLook.DIGITS), Modifier.padding(bottom = 5.dp)) }
                    LabelledWheel(stringResource(R.string.clock_minutes), m, 0, 59, { m = it }, tag = ClockTags.WHEEL_M, rowHeight = ClockLook.TIMER_ROW.dp, style = digits, width = w)
                    Box(Modifier.width(23.dp).height((ClockLook.TIMER_ROW * 3).dp), contentAlignment = Alignment.Center) { WheelColon(digits, look(ClockLook.DIGITS), Modifier.padding(bottom = 5.dp)) }
                    LabelledWheel(stringResource(R.string.clock_seconds), s, 0, 59, { s = it }, tag = ClockTags.WHEEL_S, rowHeight = ClockLook.TIMER_ROW.dp, style = digits, width = w)
                }
                Row(Modifier.padding(top = 52.dp), horizontalArrangement = Arrangement.spacedBy(ClockLook.PRESET_GAP.dp)) {
                    presets.forEachIndexed { i, sec ->
                        Box(Modifier.size(ClockLook.PRESET_W.dp, ClockLook.PRESET_H.dp).clip(RoundedCornerShape(50)).background(look(ClockLook.PILL)).combinedClickable(
                                onClick = { Wheel.msToHms(sec * ClockLogic.SECOND_MS).let { (a, b, c) -> h = a; m = b; s = c } },
                                onLongClick = { editPreset = i },
                            ).testTag(ClockTags.preset(i)), contentAlignment = Alignment.Center) {
                            Text(TimerPresets.text(sec), color = look(ClockLook.PILL_TEXT),
                                style = MaterialTheme.typography.titleSmall.copy(fontSize = ClockLook.PRESET_SP.sp, fontWeight = FontWeight.SemiBold))
                        }
                    }
                }
                // #1986 running timers show here, between the presets and Start (the reference's empty middle), as a pill each
                val active = d.timers.filter { it.state != TimerState.IDLE }
                if (active.isNotEmpty()) Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(ClockMetrics.gap)) {
                    active.take(2).forEach { t ->
                        ClockPill((t.label.ifBlank { stringResource(R.string.clock_timer) }) + "  " +
                            (if (t.state == TimerState.RINGING) stringResource(R.string.clock_times_up) else ClockLogic.countdown(ClockLogic.remaining(t, now)))) {
                            scope.launch { scroll.animateScrollTo(scroll.maxValue) }
                        }
                    }
                }
            }
            val total = Wheel.hmsToMs(h, m, s)
            RoundButton(stringResource(R.string.clock_start), { ClockEngine.addTimer(ctx, label.trim(), total, start = true) }, enabled = total > 0,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 19.5.dp).testTag(ClockTags.START_TIMER))
        }
        // below the first screen: the label, the quick-start chips and every timer with its controls
        Column(Modifier.fillMaxWidth().padding(ClockMetrics.gutter)) {
            OutlinedTextField(label, { label = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.clock_label)) })
            Text(stringResource(R.string.clock_quick_start), style = MaterialTheme.typography.labelLarge, color = look(ClockLook.LABEL),
                modifier = Modifier.fillMaxWidth().padding(top = ClockMetrics.gutter, bottom = ClockMetrics.small))
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ClockMetrics.gap), verticalArrangement = Arrangement.spacedBy(ClockMetrics.gap)) {
                ClockTools.config(ctx).timerPresets.forEach { sec ->
                    ClockPill(durationWords(sec)) {
                        ClockEngine.addTimer(ctx, label.trim().ifBlank { durationWords(sec) }, sec * ClockLogic.SECOND_MS, start = true)
                    }
                }
            }
            d.timers.forEach { t ->
                Row(Modifier.fillMaxWidth().padding(top = ClockMetrics.gap).clockCard().padding(ClockMetrics.gutter), verticalAlignment = Alignment.CenterVertically) {
                    ProgressRing(if (t.durationMs > 0) 1f - ClockLogic.remaining(t, now).toFloat() / t.durationMs else 1f, Modifier.size(56.dp)) {}
                    Column(Modifier.weight(1f).padding(start = ClockMetrics.gutter)) {
                        Text(t.label.ifBlank { stringResource(R.string.clock_timer) }, style = MaterialTheme.typography.titleMedium, color = look(ClockLook.TITLE))
                        Text(
                            if (t.state == TimerState.RINGING) stringResource(R.string.clock_times_up) else ClockLogic.countdown(ClockLogic.remaining(t, now)),
                            style = MaterialTheme.typography.displaySmall, fontFamily = FontFamily.Monospace,
                            color = if (t.state == TimerState.RINGING) MaterialTheme.colorScheme.error else look(ClockLook.DIGITS),
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(ClockMetrics.small)) {
                            when (t.state) {
                                TimerState.RUNNING -> FilledTonalButton(onClick = { ClockEngine.timer(ctx, t.id, "pause") }) { Text(stringResource(R.string.clock_pause)) }
                                TimerState.RINGING -> Button(onClick = { ClockEngine.dismiss(ctx, ClockLogic.timerKey(t.id)) }) { Text(stringResource(R.string.clock_dismiss)) }
                                else -> FilledTonalButton(onClick = { ClockEngine.timer(ctx, t.id, "start") }) { Text(stringResource(R.string.clock_start)) }
                            }
                            OutlinedButton(onClick = { ClockEngine.timer(ctx, t.id, "add") }) { Text(stringResource(R.string.clock_plus_minute)) }
                            OutlinedButton(onClick = { ClockEngine.timer(ctx, t.id, "reset") }) { Text(stringResource(R.string.clock_reset)) }
                            TextButton(onClick = { ClockEngine.timer(ctx, t.id, "delete") }) { Text(stringResource(R.string.clock_delete)) }
                        }
                    }
                }
            }
            Box(Modifier.height(ClockMetrics.gutter))
        }
    }
    }
    }
    editPreset?.let { i ->
        var text by remember(i) { mutableStateOf(TimerPresets.text(presets.getOrElse(i) { 0L })) }
        AlertDialog(
            onDismissRequest = { editPreset = null },
            title = { Text(stringResource(R.string.clock_edit_preset)) },
            text = { OutlinedTextField(text, { text = it }, singleLine = true, isError = TimerPresets.parse(text) == null, modifier = Modifier.testTag(ClockTags.PRESET_EDIT)) },
            confirmButton = {
                TextButton(onClick = { TimerPresets.parse(text)?.let { ClockEngine.editWheelPreset(ctx, i, it) }; editPreset = null },
                    enabled = TimerPresets.parse(text) != null) { Text(stringResource(R.string.clock_save)) }
            },
            dismissButton = { TextButton(onClick = { editPreset = null }) { Text(stringResource(R.string.clock_cancel)) } },
        )
    }
}

// ── stopwatch ───────────────────────────────────────────────────────────────────────────────

/**
 * #1970 / #1986 the Stopwatch as the reference: the ⋮, the dial (ticks round a dark disc, the indigo hand) with 00:00.00 in
 * it, the page dots, round dark Lap (Reset when stopped) on the left and round lilac Start / Pause on the right. Swipe for
 * the laps page (copy, share).
 */
@Composable
fun StopwatchScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val d = rememberClock()
    val sw = d.stopwatch
    val now = rememberNow(47, sw.running)
    val elapsed = ClockLogic.elapsed(sw, now)
    val laps = ClockLogic.lapTimes(sw)
    val pager = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()
    ClockFrame(modifier.fillMaxSize().testTag(ClockTags.STOPWATCH)) {
        HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
            if (page == 0) {
                Box(Modifier.fillMaxSize()) {
                    // the dial: its tick ring 149 dp in radius, 66 dp under the top
                    StopwatchDial(elapsed, Modifier.align(Alignment.TopCenter).padding(top = 66.dp).size((ClockLook.DIAL_RING * 2).dp).testTag(ClockTags.STOPWATCH_DIAL)) {
                        Text(ClockLook.dialText(elapsed), style = MaterialTheme.typography.displaySmall.copy(fontSize = ClockLook.DIAL_TEXT_SP.sp, fontWeight = FontWeight.Medium),
                            color = look(ClockLook.DIGITS), modifier = Modifier.padding(bottom = 94.dp).semantics { contentDescription = ClockLogic.stopwatchText(elapsed) })
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().padding(start = ClockMetrics.gutter, end = ClockMetrics.gutter, top = 48.dp, bottom = 150.dp).testTag(ClockTags.LAPS)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.clock_laps) + " (" + laps.size + ")", style = MaterialTheme.typography.titleMedium, color = look(ClockLook.TITLE), modifier = Modifier.weight(1f))
                        TextButton(onClick = { copyLaps(ctx, sw) }, enabled = laps.isNotEmpty()) { Text(stringResource(R.string.clock_copy_laps)) }
                        TextButton(onClick = { shareLaps(ctx, sw) }, enabled = laps.isNotEmpty(), modifier = Modifier.testTag(ClockTags.SHARE_LAPS)) { Text(stringResource(R.string.clock_share_laps)) }
                    }
                    LazyColumn(Modifier.fillMaxWidth().clockCard().padding(horizontal = ClockMetrics.gutter)) {
                        items(laps.indices.reversed().toList()) { i ->
                            Row(Modifier.fillMaxWidth().padding(vertical = ClockMetrics.gap), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(stringResource(R.string.clock_lap_n, i + 1), color = look(ClockLook.TITLE))
                                Text(ClockLogic.stopwatchText(laps[i]), fontFamily = FontFamily.Monospace, color = look(ClockLook.DIGITS))
                                Text(ClockLogic.stopwatchText(sw.laps[i]), fontFamily = FontFamily.Monospace, color = look(ClockLook.DETAIL))
                            }
                        }
                    }
                }
            }
        }
        ClockOverflow(listOf(
            stringResource(R.string.clock_laps) to { scope.launch { pager.animateScrollToPage(1) }; Unit },
            stringResource(R.string.clock_copy_laps) to { copyLaps(ctx, sw); Unit },
            stringResource(R.string.clock_share_laps) to { shareLaps(ctx, sw); Unit },
            stringResource(R.string.clock_reset) to { ClockEngine.stopwatch(ctx, "reset"); Unit },
        ), Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 2.dp))
        // dots 48 dp over the buttons' middle, the buttons 19.5 dp over the island, their middles 72 dp in from the sides
        PagerDots(2, pager.currentPage, Modifier.align(Alignment.BottomCenter).padding(bottom = (19.5 + ClockLook.ROUND / 2.0 + 48 - 2.75).dp))
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = (72 - ClockLook.ROUND / 2.0).dp, end = (72 - ClockLook.ROUND / 2.0).dp, bottom = 19.5.dp),
            horizontalArrangement = Arrangement.SpaceBetween) {
            when {
                sw.running -> RoundButton(stringResource(R.string.clock_lap), { ClockEngine.stopwatch(ctx, "lap") }, primary = false, modifier = Modifier.testTag(ClockTags.LAP))
                elapsed > 0 -> RoundButton(stringResource(R.string.clock_reset), { ClockEngine.stopwatch(ctx, "reset") }, primary = false, modifier = Modifier.testTag(ClockTags.RESET))
                else -> RoundButton(stringResource(R.string.clock_lap), {}, enabled = false, primary = false, modifier = Modifier.testTag(ClockTags.LAP))
            }
            RoundButton(stringResource(if (sw.running) R.string.clock_pause else R.string.clock_start), { ClockEngine.stopwatch(ctx, "toggle") },
                modifier = Modifier.testTag(ClockTags.TOGGLE))
        }
    }
}

/** The laps as text: one line per lap, its own time and the running total. */
private fun lapsText(sw: Stopwatch): String = ClockLogic.lapTimes(sw).mapIndexed { i, l ->
    "Lap ${i + 1}\t${ClockLogic.stopwatchText(l)}\t${ClockLogic.stopwatchText(sw.laps[i])}"
}.joinToString("\n")

private fun shareLaps(ctx: Context, sw: Stopwatch) = runCatching {
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, lapsText(sw)), null)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun copyLaps(ctx: Context, sw: Stopwatch) = runCatching {
    ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("laps", lapsText(sw)))
}

// ── interval ────────────────────────────────────────────────────────────────────────────────

@Composable
fun IntervalScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val d = rememberClock()
    val i = d.interval
    val now = rememberNow(250, i != null)
    var work by rememberSaveable { mutableIntStateOf(25) }
    var rest by rememberSaveable { mutableIntStateOf(5) }
    var rounds by rememberSaveable { mutableIntStateOf(4) }
    // #1986 the reference's dark page and glow
    ClockFrame(modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ClockMetrics.gutter).testTag(ClockTags.INTERVAL),
        horizontalAlignment = Alignment.CenterHorizontally) {
        RingingBar(d)
        PermsFixChip(listOf("notifications"), "clock", Modifier.padding(bottom = ClockMetrics.gap))
        val phase = i?.let { ClockLogic.phaseAt(it, now) }
        if (i != null && phase != null) {
            val len = (phase.endsAt - phase.startsAt).coerceAtLeast(1)
            ProgressRing(1f - (phase.endsAt - now).toFloat() / len, Modifier.fillMaxWidth(0.8f).testTag(ClockTags.INTERVAL_RING)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(ClockNotifications.phaseName(ctx, phase.kind), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                    Text(ClockLogic.countdown(phase.endsAt - now), style = MaterialTheme.typography.displayMedium, fontFamily = FontFamily.Monospace)
                    Text(stringResource(R.string.clock_round, phase.round, i.rounds), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(i.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.clock_session_left, ClockLogic.countdown(ClockLogic.phases(i).last().endsAt - now)), style = MaterialTheme.typography.bodySmall)
            RoundButton(stringResource(R.string.clock_stop), { ClockEngine.stopInterval(ctx) }, Modifier.padding(top = ClockMetrics.gutter))
        } else {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ClockMetrics.small)) {
                ClockTools.config(ctx).intervalPresets.forEach { p ->
                    FilterChip(selected = false, onClick = { ClockEngine.startInterval(ctx, p) }, label = { Text(p.label) })
                }
            }
            val small = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.Bottom) {
                LabelledWheel(stringResource(R.string.clock_work_min), work, 0, 180, { work = it }, rowHeight = 56.dp, style = small, tag = ClockTags.INTERVAL_WORK)
                LabelledWheel(stringResource(R.string.clock_rest_min), rest, 0, 60, { rest = it }, rowHeight = 56.dp, style = small, tag = ClockTags.INTERVAL_REST)
                LabelledWheel(stringResource(R.string.clock_rounds), rounds, 0, 99, { rounds = it }, rowHeight = 56.dp, style = small, tag = ClockTags.INTERVAL_ROUNDS)
            }
            RoundButton(stringResource(R.string.clock_start), {
                ClockEngine.startInterval(ctx, ClockConfig.IntervalPreset(ctx.getString(R.string.clock_custom), work * 60L, rest * 60L, 0, 0, rounds))
            }, enabled = work > 0 && rounds > 0, modifier = Modifier.padding(top = ClockMetrics.gutter).testTag(ClockTags.INTERVAL_START))
        }
    }
    }
}

// ── bedtime / sleep timer ───────────────────────────────────────────────────────────────────

@Composable
fun BedtimeScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val d = rememberClock()
    val now = rememberNow(ClockLogic.SECOND_MS, d.sleepUntil > 0)
    val bed = d.alarms.firstOrNull { it.quiet }
    val start = bed?.minuteOfDay ?: ClockTools.config(ctx).bedtimeDefault
    var hour by remember(bed?.minuteOfDay) { mutableIntStateOf(start / 60) }
    var minute by remember(bed?.minuteOfDay) { mutableIntStateOf(start % 60) }
    var sleepTotal by remember { mutableLongStateOf(0L) }
    // A bedtime reminder that is on follows the wheels (after they settle).
    LaunchedEffect(hour, minute) {
        delay(700)
        val m = Wheel.minuteOfDay(hour, minute)
        if (bed != null && bed.enabled && bed.minuteOfDay != m) ClockEngine.saveAlarm(ctx, bed.copy(minuteOfDay = m))
    }
    // #1986 the reference's dark page and glow
    ClockFrame(modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ClockMetrics.gutter).testTag(ClockTags.BEDTIME),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.clock_sleep_timer), style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.clock_sleep_timer_doc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
        if (d.sleepUntil > 0) {
            val left = d.sleepUntil - now
            val total = if (sleepTotal >= left) sleepTotal else left
            ProgressRing(1f - left.toFloat() / total.coerceAtLeast(1), Modifier.fillMaxWidth(0.6f).testTag(ClockTags.SLEEP_RING)) {
                Text(ClockLogic.countdown(left), style = MaterialTheme.typography.displaySmall, fontFamily = FontFamily.Monospace)
            }
            RoundButton(stringResource(R.string.clock_cancel), { ClockEngine.cancelSleep(ctx) }, primary = false)
        } else {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ClockMetrics.small)) {
                ClockTools.config(ctx).sleepChoices.forEach { m ->
                    FilterChip(selected = false, onClick = { sleepTotal = m * ClockLogic.MINUTE_MS; ClockEngine.startSleep(ctx, m) }, label = { Text("$m min") })
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = ClockMetrics.gutter))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.clock_bedtime), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            ClockSwitch(
                checked = bed?.enabled == true,
                onCheckedChange = { on ->
                    val minuteOfDay = Wheel.minuteOfDay(hour, minute)
                    ClockEngine.saveAlarm(ctx, (bed ?: Alarm(0, minuteOfDay, days = ClockLogic.ALL_DAYS, quiet = true, label = ctx.getString(R.string.clock_bedtime)))
                        .copy(minuteOfDay = minuteOfDay, enabled = on))
                },
                modifier = Modifier.padding(start = ClockMetrics.gap).testTag(ClockTags.BEDTIME_SWITCH),
            )
        }
        Text(stringResource(R.string.clock_bedtime_time), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val big = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            WheelPicker(hour, 0, 23, { hour = it }, Modifier.width(110.dp), style = big, tag = ClockTags.BEDTIME_HOUR)
            WheelColon(big)
            WheelPicker(minute, 0, 59, { minute = it }, Modifier.width(110.dp), style = big, tag = ClockTags.BEDTIME_MINUTE)
        }
        ClockLogic.sleepWindow(d, now, ClockEngine.zone())?.let { (from, to) ->
            Text(
                stringResource(R.string.clock_sleep_window,
                    HHMM.format(Instant.ofEpochMilli(from).atZone(ClockEngine.zone())),
                    HHMM.format(Instant.ofEpochMilli(to).atZone(ClockEngine.zone())),
                    inWords(to - from)),
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = ClockMetrics.gap),
            )
        }
    }
    }
}

// ── Pomodoro (#1970) ────────────────────────────────────────────────────────────────────────

/** Work / short break / long break on the timer engine: a progress ring, cycle and session counts, today's count, settings. */
@Composable
fun PomodoroScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val d = rememberClock()
    val p = d.pomodoro
    val now = rememberNow(250, p.running)
    val day = Instant.ofEpochMilli(now).atZone(ClockEngine.zone()).toLocalDate().toEpochDay()
    // A Pomodoro never configured takes the host's declared lengths once.
    LaunchedEffect(Unit) {
        val decl = ClockTools.config(ctx).pomodoro
        if (p == Pomodoro() && decl != Pomodoro()) ClockEngine.configurePomodoro(ctx, decl.workMin, decl.shortMin, decl.longMin, decl.longEvery, decl.autoAdvance)
    }
    // #1986 the reference's dark page and glow
    ClockFrame(modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ClockMetrics.gutter).testTag(ClockTags.POMODORO),
        horizontalAlignment = Alignment.CenterHorizontally) {
        RingingBar(d)
        PermsFixChip(listOf("notifications"), "clock", Modifier.padding(bottom = ClockMetrics.gap))
        ProgressRing(PomodoroLogic.progress(p, now), Modifier.fillMaxWidth(0.8f).testTag(ClockTags.POMODORO_RING),
            color = if (p.phase == PomodoroPhase.WORK) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(ClockNotifications.pomodoroName(ctx, p.phase), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                Text(ClockLogic.countdown(PomodoroLogic.remaining(p, now)), style = MaterialTheme.typography.displayMedium, fontFamily = FontFamily.Monospace)
            }
        }
        Text(stringResource(R.string.clock_pomodoro_counts, PomodoroLogic.cycleOf(p), p.longEvery, p.sessions, PomodoroLogic.todayCount(p, day)),
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag(ClockTags.POMODORO_COUNTS))
        Row(Modifier.fillMaxWidth().padding(vertical = ClockMetrics.gutter), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            RoundButton(stringResource(R.string.clock_reset), { ClockEngine.pomodoro(ctx, "reset") }, primary = false, size = 64.dp, modifier = Modifier.testTag(ClockTags.POMODORO_RESET))
            RoundButton(stringResource(if (p.running) R.string.clock_pause else if (p.remainingMs >= 0) R.string.clock_resume else R.string.clock_start),
                { ClockEngine.pomodoro(ctx, if (p.running) "pause" else "start") }, modifier = Modifier.testTag(ClockTags.POMODORO_TOGGLE))
            RoundButton(stringResource(R.string.clock_skip), { ClockEngine.pomodoro(ctx, "skip") }, primary = false, size = 64.dp, modifier = Modifier.testTag(ClockTags.POMODORO_SKIP))
        }
        HorizontalDivider()
        val small = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.SemiBold)
        fun set(w: Int = p.workMin, s: Int = p.shortMin, l: Int = p.longMin, e: Int = p.longEvery, a: Boolean = p.autoAdvance) =
            ClockEngine.configurePomodoro(ctx, w, s, l, e, a)
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = ClockMetrics.gap)) {
            LabelledWheel(stringResource(R.string.clock_work_len), p.workMin, 1, 180, { set(w = it) }, rowHeight = 48.dp, style = small, tag = ClockTags.POMODORO_WORK)
            LabelledWheel(stringResource(R.string.clock_short_len), p.shortMin, 1, 60, { set(s = it) }, rowHeight = 48.dp, style = small, tag = ClockTags.POMODORO_SHORT)
            LabelledWheel(stringResource(R.string.clock_long_len), p.longMin, 1, 120, { set(l = it) }, rowHeight = 48.dp, style = small, tag = ClockTags.POMODORO_LONG)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.clock_long_every), Modifier.weight(1f))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(ClockMetrics.small)) {
                listOf(2, 3, 4, 5, 6).forEach { n -> FilterChip(selected = p.longEvery == n, onClick = { set(e = n) }, label = { Text("$n") }) }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.clock_auto_advance), Modifier.weight(1f))
            ClockSwitch(checked = p.autoAdvance, onCheckedChange = { set(a = it) }, modifier = Modifier.testTag(ClockTags.POMODORO_AUTO))
        }
    }
    }
}

/** Test tags of the Clock screens. */
object ClockTags {
    const val WORLD = "clock_world"
    const val ALARMS = "clock_alarms"
    const val ADD_ALARM = "clock_add_alarm"
    const val SAVE = "clock_save"
    // #1970 the alarm editor sheet
    const val ALARM_EDITOR = "clock_alarm_editor"
    const val ALARM_HOUR = "clock_alarm_hour"
    const val ALARM_MINUTE = "clock_alarm_minute"
    const val ALARM_DATE = "clock_alarm_date"
    const val ALARM_NAME = "clock_alarm_name"
    const val ROW_SOUND = "clock_row_sound"
    const val ROW_VIBRATION = "clock_row_vibration"
    const val ROW_SNOOZE = "clock_row_snooze"
    const val ROW_GROUP = "clock_row_group"
    const val ROW_CALENDAR = "clock_row_calendar"
    const val BACKGROUND_PREVIEW = "clock_background_preview"
    const val CANCEL = "clock_cancel"
    const val DELETE_ALARM = "clock_delete_alarm"
    fun day(name: String) = "clock_day:$name"
    fun background(id: String) = "clock_background:$id"
    fun group(name: String) = "clock_group:$name"
    // #1970 timer wheels and presets, stopwatch dial, interval / bedtime wheels, Pomodoro
    const val WHEEL_H = "clock_wheel_h"
    const val WHEEL_M = "clock_wheel_m"
    const val WHEEL_S = "clock_wheel_s"
    const val PRESET_EDIT = "clock_preset_edit"
    fun preset(i: Int) = "clock_preset:$i"
    const val STOPWATCH_DIAL = "clock_stopwatch_dial"
    const val LAPS = "clock_laps"
    const val SHARE_LAPS = "clock_share_laps"
    const val LAP = "clock_lap"
    const val RESET = "clock_reset"
    const val TOGGLE = "clock_toggle"
    const val INTERVAL_RING = "clock_interval_ring"
    const val INTERVAL_WORK = "clock_interval_work"
    const val INTERVAL_REST = "clock_interval_rest"
    const val INTERVAL_ROUNDS = "clock_interval_rounds"
    const val INTERVAL_START = "clock_interval_start"
    const val SLEEP_RING = "clock_sleep_ring"
    const val BEDTIME_SWITCH = "clock_bedtime_switch"
    const val BEDTIME_HOUR = "clock_bedtime_hour"
    const val BEDTIME_MINUTE = "clock_bedtime_minute"
    const val WORLD_DIAL = "clock_world_dial"
    const val POMODORO = "clock_pomodoro"
    const val POMODORO_RING = "clock_pomodoro_ring"
    const val POMODORO_COUNTS = "clock_pomodoro_counts"
    const val POMODORO_TOGGLE = "clock_pomodoro_toggle"
    const val POMODORO_SKIP = "clock_pomodoro_skip"
    const val POMODORO_RESET = "clock_pomodoro_reset"
    const val POMODORO_WORK = "clock_pomodoro_work"
    const val POMODORO_SHORT = "clock_pomodoro_short"
    const val POMODORO_LONG = "clock_pomodoro_long"
    const val POMODORO_AUTO = "clock_pomodoro_auto"
    const val TIMERS = "clock_timers"
    const val START_TIMER = "clock_start_timer"
    const val STOPWATCH = "clock_stopwatch"
    const val INTERVAL = "clock_interval"
    const val BEDTIME = "clock_bedtime"
    // #1667 the converter, the default city and the day bars
    const val REF_NOW = "clock_ref_now"
    const val REF_DATE = "clock_ref_date"
    const val REF_TIME = "clock_ref_time"
    const val TABLE = "clock_table"
    const val ADD_CITY = "clock_add_city"
    const val EDIT_DEFAULT = "clock_edit_default"
    fun row(zone: String) = "clock_row:$zone"
    fun bar(zone: String) = "clock_day_bar:$zone"
    fun setDefault(zone: String) = "clock_set_default:$zone"
    // #1668 the full clock page and its tabs
    const val FULL = "clock_full"
    fun tab(id: String) = "clock_tab:$id"
}

/**
 * #1646 the lib's ONE stopwatch, observable from composition: the store's stopwatch, re-read on every ClockEngine write.
 * The SuperApp's Home bar readout and its date dropdown read this, so they and the Stopwatch page show one state.
 */
@Composable
fun rememberStopwatch(): Stopwatch = rememberClock().stopwatch
