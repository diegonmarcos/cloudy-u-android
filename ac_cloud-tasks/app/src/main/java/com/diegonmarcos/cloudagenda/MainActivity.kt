package com.diegonmarcos.cloudagenda

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import com.diegonmarcos.cloudagenda.data.AttachmentKind
import com.diegonmarcos.cloudagenda.data.EventIntents
import com.diegonmarcos.cloudagenda.data.Ics
import com.diegonmarcos.cloudagenda.data.ProjectSummaries
import com.diegonmarcos.cloudagenda.data.TasksDb
import com.diegonmarcos.cloudagenda.data.TasksPrefs
import com.diegonmarcos.cloudagenda.data.TasksRepository
import com.diegonmarcos.cloudagenda.ui.AppEnv
import com.diegonmarcos.cloudagenda.ui.TasksApp
import com.diegonmarcos.superapp.bottomnav.FleetChrome
import com.diegonmarcos.superapp.updater.Updater

/**
 * Cloud Tasks: one Compose activity. The window is the fleet's (FleetChrome: edge-to-edge, transparent bars), the
 * chrome and every native page are Compose (ui/), and the Events / Calendar / Invites pages are the legacy tasks.html
 * WebView hosted inside that shell (ui/LegacyWeb.kt).
 */
class MainActivity : ComponentActivity() {

    private lateinit var env: AppEnv

    private val documentPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) env.picked(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FleetChrome.apply(this)
        // #1987 a link to a Clock page (a clock notification, or Cloud Calc handing over a page it no longer shows)
        com.diegonmarcos.clocktools.ClockLink.offer(intent)
        openProject(intent)
        env = AppEnv(this) { kind ->
            documentPicker.launch(if (kind == AttachmentKind.IMAGE) arrayOf("image/*") else arrayOf("*/*"))
        }
        offerImport(intent)
        setContent { TasksApp(env) }
        Updater.start(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // A project deep link while the app is open: select it, then rebuild so the page reads the new selection.
        setIntent(intent)
        com.diegonmarcos.clocktools.ClockLink.offer(intent)
        val offered = ::env.isInitialized && offerImport(intent)
        if (openProject(intent) && !offered) recreate()
    }

    /**
     * `shortcut_action = project:<id>` (the SuperApp's Boards ▸ Tasks sends it through extapp:cloud-tasks#project:<id>):
     * select that project's first list before the page reads its selection. An unknown id leaves the selection alone.
     */
    private fun openProject(intent: Intent?): Boolean {
        val id = intent?.getStringExtra(EXTRA_TARGET)?.takeIf { it.startsWith(PROJECT_PREFIX) }?.removePrefix(PROJECT_PREFIX)
            ?: return false
        intent?.removeExtra(EXTRA_TARGET)
        val list = runCatching { ProjectSummaries.firstList(TasksRepository(TasksDb.shared(this)).snapshot(), id) }.getOrNull()
            ?: return false
        TasksPrefs(this).selectedListId = list
        return true
    }

    /**
     * #1766 an "Add to calendar" Intent (title, beginTime, ... extras) or an .ics file (VIEW / SEND, file or content URI): the events go
     * to the New event sheet. A calendar VIEW (an event or a moment of the calendar provider) just opens the app: with no calendar
     * permission there is nothing to read behind it.
     */
    private fun offerImport(intent: Intent?): Boolean {
        if (intent == null) return false
        val events = runCatching {
            EventIntents.fromIntent(intent)?.let { listOf(it) } ?: icsOf(intent)?.let { Ics.parse(it) }.orEmpty()
        }.getOrDefault(emptyList())
        if (events.isEmpty()) return false
        env.controller.offerImport(events); intent.action = Intent.ACTION_MAIN
        return true
    }

    @Suppress("DEPRECATION")
    private fun icsOf(intent: Intent): String? {
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            else -> null
        }
        if (uri != null) {
            if (uri.scheme == "content" && uri.authority == "com.android.calendar") return null
            return contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText().take(MAX_ICS) }
        }
        return intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.contains("BEGIN:VCALENDAR", true) }
    }

    private companion object {
        const val MAX_ICS = 2_000_000
        const val EXTRA_TARGET = "shortcut_action"
        const val PROJECT_PREFIX = "project:"
    }
}
