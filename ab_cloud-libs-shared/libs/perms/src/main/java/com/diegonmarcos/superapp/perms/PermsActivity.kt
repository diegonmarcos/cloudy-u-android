package com.diegonmarcos.superapp.perms

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * #1274 the Permissions page libs:perms hosts in any app without a [PermsHost] page of its own (Calc, Browser, Nav,
 * Keyboard, Drive, Store, ...), and for contexts with no activity at all (an IME, a plugin, a notification). It is the one
 * place in that app that asks: opened by [PermsLink.open] it lists the requesting feature's entries first and highlighted,
 * then every other applicable entry this app declares. Runtime entries go through the system dialog, the rest through
 * [PermsSettings.open] (the exact settings page). Re-read on every resume, so a grant made in Settings shows on return.
 * #1372 first run ([FIRST_RUN]) is a full-height gate page (FirstRunGate): only what is missing, in the Perms row style,
 * Grant all and Later; it never opens when nothing is missing and closes by itself once nothing is.
 */
class PermsActivity : ComponentActivity() {
    private var tick by mutableIntStateOf(0)

    private val ask = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val feature = intent.getStringExtra(EXTRA_FEATURE).orEmpty()
        val ids = intent.getStringArrayExtra(EXTRA_IDS)?.toSet().orEmpty()
        // #1372 the gate never opens on a phone where nothing is missing (it would only say so)
        if (feature == FIRST_RUN && AppPerms.missingOwn(this).isEmpty() && AppPerms.missingHelpers(this).isEmpty()) { finish(); return }
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                if (feature == FIRST_RUN) Gate(ids, tick) else Page(feature, ids, tick)
            }
        }
    }

    override fun onResume() { super.onResume(); tick++ }

    @Composable
    private fun Page(feature: String, ids: Set<String>, tick: Int) {
        // the Scaffold clears the system bars (its content insets); this page names no inset itself (ShellTopInsetStaticGuardTest S4b)
        Scaffold(Modifier.fillMaxSize()) { bars ->
        Column(Modifier.padding(bars).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                when (feature) { "" -> "Permissions"; else -> "Permissions needed by $feature" },
                fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp).semantics { heading() }.testTag("perms:title"))
            // #1360 the one shared section: this app's declared grants and its helper apps', live, Grant / Open, Grant all.
            AppPermsSection(tick, highlight = ids)
        }
        }
    }

    /** #1372 first run: the full-height gate while something is missing; it closes itself the moment nothing is. */
    @Composable
    private fun Gate(ids: Set<String>, tick: Int) {
        val perms = rememberAppPerms(tick, ids)
        val name = remember { PermsView.appName(applicationInfo.loadLabel(packageManager).toString()) }
        val shows = PermsView.gateShows(perms.items)
        LaunchedEffect(shows) { if (!shows) finish() }
        if (shows) FirstRunGate(name, perms.items, perms.act, perms.grantAll, onLater = { finish() })
    }

    private fun request(entries: List<PermEntry>) {
        val perms = entries.map { it.permission }.filter { it.isNotEmpty() }.distinct()
        if (perms.isEmpty()) return
        PermAskLog.markAsked(this, perms)
        PermAskLog.markAskedIds(this, entries.map { it.id })
        ask.launch(perms.toTypedArray())
    }

    data class Row(val entry: PermEntry, val status: PermStatus, val wanted: Boolean)

    companion object {
        const val EXTRA_FEATURE = "com.diegonmarcos.perms.FEATURE"
        const val EXTRA_IDS = "com.diegonmarcos.perms.IDS"
        /** The feature name first run opens the page with (PermsFirstRun). */
        const val FIRST_RUN = "first-run"

        fun intent(ctx: Context, feature: String, ids: Collection<String>): Intent =
            Intent(ctx, PermsActivity::class.java)
                .putExtra(EXTRA_FEATURE, feature)
                .putExtra(EXTRA_IDS, ids.toTypedArray())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** The page's rows: the requested entries first (catalogue order), then the rest; not-applicable ones dropped. */
        fun rows(entries: List<PermEntry>, wanted: Set<String>, status: (PermEntry) -> PermStatus): List<Row> =
            entries.map { Row(it, status(it), it.id in wanted) }
                .filter { it.status.state != PermState.NOT_APPLICABLE }
                .sortedBy { if (it.wanted) 0 else 1 }

        fun stateText(s: PermState): String = when (s) {
            PermState.GRANTED -> "Granted"
            PermState.DENIED -> "Not granted"
            PermState.PERMANENTLY_DENIED -> "Denied: grant it in settings"
            PermState.NOT_APPLICABLE -> "Not applicable"
            PermState.UNKNOWN -> "Unknown"
        }
    }
}
