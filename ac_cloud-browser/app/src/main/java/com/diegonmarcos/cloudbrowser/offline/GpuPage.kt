package com.diegonmarcos.cloudbrowser.offline

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.webkit.WebViewCompat
import com.diegonmarcos.cloudlib.web.GpuCaps
import com.diegonmarcos.cloudlib.web.GpuPageModel
import com.diegonmarcos.cloudlib.web.GpuProbe
import com.diegonmarcos.cloudlib.web.PageConsole
import com.diegonmarcos.superapp.uikit.KitSectionHeader
import com.diegonmarcos.superapp.uikit.KitSettingsRow
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * #1518 Configs > GPU: the WebView provider and version, the WebGL renderer and vendor (measured in a hidden test WebView),
 * the WebGPU adapter or the exact reason there is none, a chrome://gpu-like feature list, the owner steps for the
 * ignore-gpu-blocklist experiment, and the Open WebView DevTools and Retest buttons. The words come from web-core's GpuPageModel.
 */
@Composable
fun ColumnScope.GpuPageBlock() {
    val ctx = LocalContext.current
    val p = LocalKitPalette.current
    var caps by remember { mutableStateOf<GpuCaps?>(GpuProbe.last) }
    var round by remember { mutableIntStateOf(0) }
    var note by remember { mutableStateOf("") }
    LaunchedEffect(round) {
        if (round > 0) caps = null
        GpuProbe.probe(ctx, force = round > 0) { caps = it }
    }
    val pkg = remember(round) { WebViewCompat.getCurrentWebViewPackage(ctx) }
    Column(Modifier.testTag("browser:gpu")) {
        GpuPageModel.rows(pkg?.packageName, pkg?.versionName, caps).forEach { r ->
            KitSettingsRow(r.label, r.value, Modifier.testTag("browser:gpu:row:${r.label}"))
        }
        val features = GpuPageModel.features(caps)
        if (features.isNotEmpty()) {
            KitSectionHeader("Features", "", Modifier.padding(top = 10.dp).testTag("browser:gpu:features"))
            features.forEach { KitSettingsRow(it.label, it.value) }
        }
        // #1537 Page diagnostics: the errors the pages logged to their console (also in the log export, tag CloudWebConsole)
        val errors = PageConsole.errors().takeLast(30)
        KitSectionHeader(GpuPageModel.DIAG_TITLE, "", Modifier.padding(top = 10.dp).testTag("browser:gpu:console"))
        if (errors.isEmpty()) Text("No page console errors logged yet.", color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
        errors.forEach { Text(PageConsole.format(it), color = p.textSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 1.dp)) }
        KitSectionHeader("Owner steps", "", Modifier.padding(top = 10.dp))
        GpuPageModel.steps.forEachIndexed { i, s -> Text("${i + 1}. $s", color = p.textSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp)) }
        Text("From a shell: ${GpuPageModel.DEV_UI_ADB}", color = p.textSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
        Row {
            TextButton({ note = openDevTools(ctx) }, modifier = Modifier.testTag("browser:gpu:devtools")) { Text("Open WebView DevTools") }
            TextButton({ round++; note = "" }, modifier = Modifier.testTag("browser:gpu:retest")) { Text("Retest") }
        }
        if (note.isNotEmpty()) Text(note, color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
    }
}

/** Starts WebView DevTools as an intent from this app; the words say what happened. */
internal fun openDevTools(ctx: Context): String = try {
    ctx.startActivity(Intent(GpuPageModel.DEV_UI_ACTION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    "WebView DevTools opened: Flags, enable ignore-gpu-blocklist, force-stop this app, then Retest."
} catch (e: ActivityNotFoundException) {
    "This phone has no WebView DevTools screen for the active provider; run the shell command above."
} catch (e: SecurityException) {
    "Android refused to open WebView DevTools from this app; run the shell command above."
}
