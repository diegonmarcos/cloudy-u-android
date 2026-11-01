package com.diegonmarcos.cgomniverse

import android.content.Intent
import android.os.Bundle
import android.os.StrictMode
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import com.diegonmarcos.cgomniverse.ui.AppShell
import com.diegonmarcos.cgomniverse.ui.AppTheme
import com.diegonmarcos.superapp.bottomnav.FleetChrome

/** The one Activity: edge-to-edge through the fleet's FleetChrome; the bars clear their own insets. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // #1373 launch-freeze proof: `adb shell setprop log.tag.CgcStrict VERBOSE` turns on a log-only StrictMode for the main
        // thread (disk, network, slow calls); the avatar proof counts the violations. Off by default, so users pay nothing.
        if (Log.isLoggable(STRICT_TAG, Log.VERBOSE)) {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
        }
        super.onCreate(savedInstanceState)
        FleetChrome.apply(this)
        // the start page and world, read once from the launching intent (Launch); state restores take over after that
        val section = intent?.getStringExtra(Launch.EXTRA_SECTION)
        val world = worldOf(intent)
        setContent { AppTheme { AppShell(startSection = section, startWorld = world, incoming = incoming.value, onIncoming = { incoming.value = null }) } }
    }

    /** singleTask: a world extra or deep link sent while the app runs arrives here and opens (or focuses) its Galaxy tab. */
    private val incoming = mutableStateOf<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        worldOf(intent)?.let { incoming.value = it }
    }

    private fun worldOf(i: Intent?): String? {
        val link = i?.data
        return i?.getStringExtra(Launch.EXTRA_WORLD) ?: Launch.worldFromLink(link?.scheme, link?.host, link?.path)
    }

    private companion object { const val STRICT_TAG = "CgcStrict" }
}
