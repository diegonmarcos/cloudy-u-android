package com.diegonmarcos.clocktools

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.diegonmarcos.clocktools.ui.RoundButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * What a ring shows over the lock screen (the service notification's full-screen intent): every
 * ringing item with Dismiss, and Snooze for an alarm or +1 min for a timer. It closes itself once
 * nothing rings — whether answered here, from the notification, or silenced by the timeout.
 */
class RingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val host = ClockTools.host(this)
        setContent { host.Theme { RingScreen { finish() } } }
    }
}

/**
 * #1986 the ring screen in the references' look: the ringing alarm's background (the sheet's preview; the default is the
 * reference's purple), "Alarm", the time large, the date; per ring its name, a round lilac Stop, Snooze (with the time it
 * rings again) or +1 min for a timer, and Dismiss (every ring at once).
 */
@Composable
fun RingScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val version by ClockEngine.changes.collectAsState()
    val d = remember(version) { ClockEngine.load(ctx) }
    LaunchedEffect(d.ringing.isEmpty()) { if (d.ringing.isEmpty()) onDone() }
    val now = Instant.ofEpochMilli(ClockEngine.now()).atZone(ClockEngine.zone())
    com.diegonmarcos.clocktools.ui.ClockFrame(Modifier.fillMaxSize()) {
    Column(
        // #1970 the ringing alarm's chosen background (the sheet's Alarm background preview)
        Modifier.fillMaxSize().background(com.diegonmarcos.clocktools.ui.ringBackground(
            d.alarms.firstOrNull { a -> d.ringing.lastOrNull()?.key == ClockLogic.alarmKey(a.id) }?.background ?: ClockDesign.BACKGROUND_DEFAULT,
            MaterialTheme.colorScheme,
        )).padding(24.dp).testTag(RING_TAG),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.clock_alarm), style = MaterialTheme.typography.titleMedium, color = Color(ClockLook.TITLE))
        Text(
            DateTimeFormatter.ofPattern("HH:mm").format(now),
            style = MaterialTheme.typography.displayLarge.copy(fontSize = 84.sp, fontWeight = FontWeight.SemiBold), color = Color(ClockLook.TITLE),
        )
        Text(DateTimeFormatter.ofPattern("EEE, MMMM d", java.util.Locale.ENGLISH).format(now), style = MaterialTheme.typography.bodyMedium, color = Color(ClockLook.DETAIL))
        d.ringing.forEach { r ->
            val alarm = d.alarms.firstOrNull { ClockLogic.alarmKey(it.id) == r.key }
            val card = RingCard.of(r.key, alarm?.let(ClockLogic::canSnooze) != false, RingCard.snoozeAt(now.hour * 60 + now.minute, alarm?.snoozeMinutes ?: 0))
            Text(
                r.label.ifBlank { stringResource(if (r.key.startsWith("alarm:")) R.string.clock_alarm else R.string.clock_times_up) },
                style = MaterialTheme.typography.headlineSmall, color = Color(ClockLook.TITLE),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 36.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 20.dp)) {
                card.actions.forEach { a ->
                    when {
                        a.label == RingCard.STOP -> RoundButton(stringResource(R.string.clock_stop), { ClockEngine.dismiss(ctx, r.key) }, size = 96.dp)
                        a.label.startsWith(RingCard.SNOOZE + ":") -> RoundButton(stringResource(R.string.clock_snooze_until, a.label.substringAfter(':')),
                            { ClockEngine.snooze(ctx, r.key) }, primary = false, size = 96.dp)
                        a.label == RingCard.PLUS_MINUTE -> RoundButton(stringResource(R.string.clock_plus_minute), { ClockEngine.control(ctx, "timer_add", r.key) }, primary = false, size = 96.dp)
                        a.label == RingCard.START -> RoundButton(stringResource(R.string.clock_start), { ClockEngine.control(ctx, "pomodoro_start", "") }, primary = false, size = 96.dp)
                        else -> {}
                    }
                }
            }
            if (card.actions.any { it.label == RingCard.DISMISS }) {
                TextButton(onClick = { ClockEngine.dismissAll(ctx) }, Modifier.padding(top = 12.dp)) {
                    Text(stringResource(R.string.clock_dismiss), color = Color(ClockLook.LABEL))
                }
            }
        }
    }
    }
}

const val RING_TAG = "clock_ring_screen"
