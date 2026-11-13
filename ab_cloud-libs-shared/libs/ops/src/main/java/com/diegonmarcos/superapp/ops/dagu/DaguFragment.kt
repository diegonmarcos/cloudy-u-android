package com.diegonmarcos.superapp.ops.dagu

import android.content.Context
import com.diegonmarcos.superapp.ops.OpsCredentials
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.ops.R
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * Infos/Dagu — GitHub-Actions-style list of every registered DAG on
 * workflows.diegonmarcos.com with a coloured status dot per row +
 * relative last-run timestamp + schedule. Mirrors the Mattermost
 * page's flow:
 *
 *   • No token saved → login form (URL pre-filled from BuildConfig,
 *     bearer token pasted by the user from
 *     vault/A0_keys/providers/authelia/oauth/get_token.py output).
 *   • Token saved → fetch GET /api/v1/dags on a background Thread,
 *     render the list. Refresh button re-fetches. Sign out wipes
 *     prefs.
 *
 * Threading: every REST call runs on a dedicated background Thread.
 * Results land in Compose state (and Toasts on the main Handler). Same
 * pattern MattermostFragment / JmapClient uses — keeps libs/ops
 * coroutine-free.
 *
 * 2026-10-16 owner decision: all apps must be Compose. The page is a ComposeView under
 * CloudKitTheme (libs:ui-kit) with its palette in the ops_* colour resources; the host
 * contract (newInstance, DaguPrefs) is unchanged.
 */
class DaguFragment : Fragment() {

    /** What the DAG list shows. */
    private sealed interface Load {
        data object Loading : Load
        data class Done(val dags: List<DaguDag>) : Load
        data class Failed(val message: String) : Load
    }

    private lateinit var prefs: DaguPrefs
    private val main = Handler(Looper.getMainLooper())

    private var loggedIn by mutableStateOf(false)
    private var userLabel by mutableStateOf("")
    private var loginStatus by mutableStateOf("")
    private var testing by mutableStateOf(false)
    private var load by mutableStateOf<Load>(Load.Loading)

    /** fileNames with a start request in flight (a double-tap cannot queue two runs). */
    private var starting by mutableStateOf<Set<String>>(emptySet())

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        prefs = DaguPrefs(ctx)
        loggedIn = prefs.isLoggedIn()
        userLabel = prefs.userLabel
        if (loggedIn) loadAndRender(ctx)
        return ComposeView(ctx).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val palette = KitPalette(
                    surface = colorResource(R.color.ops_surface),
                    surfaceSelected = colorResource(R.color.ops_selected),
                    textPrimary = colorResource(R.color.ops_text),
                    textSecondary = colorResource(R.color.ops_text_dim),
                    accent = colorResource(R.color.ops_accent),
                    hairline = colorResource(R.color.ops_hairline),
                    tileInk = colorResource(R.color.ops_bg),
                )
                CloudKitTheme(palette) {
                    Column(Modifier.fillMaxSize().background(colorResource(R.color.ops_bg)).padding(16.dp)) {
                        if (loggedIn) DagList(ctx) else LoginForm(ctx)
                    }
                }
            }
        }
    }

    // ─────────────────────────── login form ───────────────────────────

    @Composable
    private fun LoginForm(ctx: Context) {
        val p = LocalKitPalette.current
        var url by remember { mutableStateOf(prefs.serverUrl) }
        var label by remember { mutableStateOf("") }
        Header("Dagu — Sign in")
        // #1693 the Authelia bearer token is typed once in Cloud Account (App credentials); this page only reads it from there.
        Small("The Authelia bearer token is entered in Cloud Account (App credentials) and read from there.")
        Field("Server URL", url, KeyboardType.Uri) { url = it }
        Field("Label (optional)", label, KeyboardType.Text) { label = it }
        Text(loginStatus, color = p.textSecondary, fontSize = 12.sp,
            modifier = Modifier.padding(vertical = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = !testing, onClick = {
            val u = url.trim(); val l = label.trim()
            if (u.isBlank()) {
                loginStatus = "Server URL required."
                return@Button
            }
            loginStatus = "Reading Cloud Account…"
            testing = true
            Thread {
                val cred = runCatching { OpsCredentials.read(ctx, OpsCredentials.ID_DAGU_TOKEN) }.getOrElse { OpsCredentials.Cred("", "Cloud Account read failed") }
                val why = OpsCredentials.usable(cred)
                if (why != null) { main.post { loginStatus = why; testing = false }; return@Thread }
                val t = cred.value.trim()
                main.post { loginStatus = "Testing…" }
                val result = runCatching { DaguClient(ctx, u, t).listDags() }
                main.post {
                    result.onSuccess {
                        prefs.serverUrl = u
                        prefs.bearerToken = t
                        prefs.userLabel = l
                        userLabel = l
                        loggedIn = true
                        testing = false
                        loadAndRender(ctx)
                    }.onFailure { e ->
                        loginStatus = "Test failed: ${e.message}"
                        testing = false
                    }
                }
            }.start()
        }) { Text("Sign in with Cloud Account") }
        Button(onClick = { loginStatus = OpsCredentials.open(ctx).orEmpty() }) { Text("Set it in Cloud Account") }
        }
    }

    // ─────────────────────────── list view ───────────────────────────

    @Composable
    private fun DagList(ctx: Context) {
        val p = LocalKitPalette.current
        Header("Dagu — Workflow runs")
        if (userLabel.isNotBlank()) Small("signed in as $userLabel")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { loadAndRender(ctx) }) { Text("Refresh") }
            Button(onClick = {
                prefs.clear()
                loginStatus = ""
                loggedIn = false
            }) { Text("Sign out") }
        }
        Box(Modifier.fillMaxSize().padding(vertical = 8.dp)) {
            when (val l = load) {
                Load.Loading -> Text("Loading…", color = p.textSecondary, fontSize = 13.sp)
                is Load.Failed -> Text(
                    "Load failed: ${l.message}\nIf the token expired, change it in Cloud Account, then Sign out and sign in again.",
                    color = colorResource(R.color.ops_error), fontSize = 12.sp)
                is Load.Done ->
                    if (l.dags.isEmpty()) {
                        Text("No DAGs registered.", color = p.textSecondary, fontSize = 13.sp)
                    } else {
                        // Sort by last-run timestamp desc — most recent on top,
                        // never-run DAGs at the bottom. Same default GHA shows.
                        val sorted = remember(l) { l.dags.sortedByDescending { it.lastRun?.finishedAtMs ?: 0L } }
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(sorted, key = { it.fileName }) { DagRow(ctx, it) }
                        }
                    }
            }
        }
    }

    private fun loadAndRender(ctx: Context) {
        load = Load.Loading
        val client = DaguClient(ctx, prefs.serverUrl, prefs.bearerToken)
        Thread {
            val result = runCatching { client.listDags() }
            main.post {
                result.onSuccess { resp -> load = Load.Done(resp.dags) }
                    .onFailure { e ->
                        load = Load.Failed(e.message ?: "")
                        Toast.makeText(ctx, "Dagu: ${e.message}", Toast.LENGTH_LONG).show()
                    }
            }
        }.start()
    }

    // ─────────────────────────── widgets ───────────────────────────

    @Composable
    private fun DagRow(ctx: Context, d: DaguDag) {
        val p = LocalKitPalette.current
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 10.dp)) {
            // Status dot — a coloured circle. Cheap + no asset.
            Box(Modifier.padding(top = 4.dp).size(12.dp).clip(CircleShape)
                .background(statusColor(d.lastRun?.status ?: 0)))
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(d.displayLabel, color = p.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                val subParts = mutableListOf<String>()
                subParts += statusLabel(d.lastRun?.status ?: 0)
                d.lastRun?.finishedAtMs?.takeIf { it > 0L }?.let { subParts += "  ·  ${relativeAgo(it)}" }
                if (d.schedule.isNotBlank()) subParts += "  ·  ⏰ ${d.schedule}"
                Text(subParts.joinToString(""), color = p.textSecondary, fontSize = 12.sp)
                if (d.description.isNotBlank()) {
                    Text(d.description, color = p.textSecondary.copy(alpha = 0.55f), fontSize = 11.sp)
                }
            }
            RunButton(ctx, d)
        }
    }

    /**
     * Per-row "Run" button — dispatches the DAG through
     * [DaguClient.startDag] (POST /api/v1/dags/{fileName}/start).
     *
     * The outcome is ALWAYS shown. Success reports the run id Dagu
     * handed back, which is the proof the run really was created rather
     * than the request merely not having thrown. Failure shows the
     * server's own message. There is no branch on which this button can
     * complete silently — the only quiet path would be the fragment
     * being gone, and then there is no UI left to talk to anyway.
     */
    @Composable
    private fun RunButton(ctx: Context, d: DaguDag) {
        val busy = d.fileName in starting
        Button(enabled = !busy, modifier = Modifier.padding(start = 8.dp), onClick = {
            // Disable immediately so a double-tap cannot queue two runs.
            starting = starting + d.fileName
            val client = DaguClient(ctx, prefs.serverUrl, prefs.bearerToken)
            Thread {
                val result = runCatching { client.startDag(d.fileName) }
                main.post {
                    starting = starting - d.fileName
                    result.onSuccess { runId ->
                        Toast.makeText(
                            ctx,
                            "Started ${d.displayLabel} · run ${runId.take(8)}",
                            Toast.LENGTH_LONG,
                        ).show()
                        // Re-fetch so the row's dot flips to "running".
                        loadAndRender(ctx)
                    }.onFailure { e ->
                        Toast.makeText(
                            ctx,
                            "Start failed — ${d.displayLabel}: ${e.message}",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }.start()
        }) { Text(if (busy) "Starting…" else "Run", fontSize = 12.sp) }
    }

    /** Dagu status int → row dot colour. Lifted from Dagu's own
     *  internal/scheduler/scheduler.go status constants. */
    @Composable
    private fun statusColor(status: Int): Color = colorResource(when (status) {
        1 -> R.color.ops_status_running    // blue
        2 -> R.color.ops_status_error      // red
        3 -> R.color.ops_status_cancel     // orange
        4 -> R.color.ops_status_success    // green
        5 -> R.color.ops_status_skipped    // grey
        6 -> R.color.ops_status_partial    // amber
        else -> R.color.ops_status_unknown // unknown / no runs — dim grey
    })

    private fun statusLabel(status: Int): String = when (status) {
        1 -> "running"
        2 -> "error"
        3 -> "cancelled"
        4 -> "success"
        5 -> "skipped"
        6 -> "partial-success"
        else -> "no runs yet"
    }

    /** "5 m ago" / "2 h ago" / "3 d ago" — same readable shape GHA
     *  uses for the workflows column. */
    private fun relativeAgo(epochMs: Long): String {
        val deltaSec = ((System.currentTimeMillis() - epochMs) / 1000).coerceAtLeast(0)
        return when {
            deltaSec < 60        -> "$deltaSec s ago"
            deltaSec < 3600      -> "${deltaSec / 60} m ago"
            deltaSec < 86400     -> "${deltaSec / 3600} h ago"
            deltaSec < 86400 * 7 -> "${deltaSec / 86400} d ago"
            else                 -> {
                val days = deltaSec / 86400
                "$days d ago"
            }
        }
    }

    @Composable
    private fun Header(text: String) {
        Text(text, color = LocalKitPalette.current.accent, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp))
    }

    @Composable
    private fun Small(t: String) {
        Text(t, color = LocalKitPalette.current.textSecondary, fontSize = 11.sp,
            modifier = Modifier.padding(bottom = 8.dp))
    }

    @Composable
    private fun Field(label: String, value: String, type: KeyboardType, onChange: (String) -> Unit) {
        OutlinedTextField(
            value = value, onValueChange = onChange, singleLine = true,
            label = { Text(label, fontSize = 11.sp) },
            keyboardOptions = KeyboardOptions(keyboardType = type),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp))
    }

    companion object { fun newInstance() = DaguFragment() }
}
