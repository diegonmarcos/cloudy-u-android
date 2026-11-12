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
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
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

@Composable
fun RingScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val version by ClockEngine.changes.collectAsState()
    val d = remember(version) { ClockEngine.load(ctx) }
    LaunchedEffect(d.ringing.isEmpty()) { if (d.ringing.isEmpty()) onDone() }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(12.dp).testTag(RING_TAG),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(ClockEngine.now()).atZone(ClockEngine.zone())),
            style = MaterialTheme.typography.displayLarge,
        )
        d.ringing.forEach { r ->
            Text(
                r.label.ifBlank { stringResource(if (r.key.startsWith("alarm:")) R.string.clock_alarm else R.string.clock_times_up) },
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = { ClockEngine.dismiss(ctx, r.key) }) { Text(stringResource(R.string.clock_dismiss)) }
                if (r.key.startsWith("alarm:")) FilledTonalButton(onClick = { ClockEngine.snooze(ctx, r.key) }) { Text(stringResource(R.string.clock_snooze)) }
                if (r.key.startsWith("timer:")) {
                    FilledTonalButton(onClick = { ClockEngine.control(ctx, "timer_add", r.key) }) { Text(stringResource(R.string.clock_plus_minute)) }
                }
            }
        }
    }
}

const val RING_TAG = "clock_ring_screen"
