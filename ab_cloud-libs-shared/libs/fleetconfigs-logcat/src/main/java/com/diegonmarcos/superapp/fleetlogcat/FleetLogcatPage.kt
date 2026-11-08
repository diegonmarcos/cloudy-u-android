package com.diegonmarcos.superapp.fleetlogcat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.devtools.LogPipe
import com.diegonmarcos.superapp.fleetconfigs.FleetConfigs
import com.diegonmarcos.superapp.themebg.ThemeBackground
import com.diegonmarcos.superapp.themebg.ThemeColors
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Test tags of the Logcat tab (UiAutomator resource ids). */
public object FleetLogcatTags {
    public const val PAGE: String = "fleetcfg_page_logcat"
    public const val ERRORS_ONLY: String = "fleetlogcat_errors_only"
    public const val COPY_MD: String = "fleetlogcat_copy_md"
    public const val SAVE_HTML: String = "fleetlogcat_save_html"
    public const val SHARE: String = "fleetlogcat_share"
    public const val BODY: String = "fleetlogcat_body"
}

private const val TAIL_LINES = 600

/**
 * Configs ▸ Logcat: THIS app's own log stream (libs:devtools LogPipe, one reader per process, uid-filtered to this app) with an
 * errors-only toggle and the same two-way export the X-Ray tab offers — Copy (token-optimized Markdown), Save (an HTML page) and
 * Share. The view refreshes every second while it is open.
 */
@Composable
public fun FleetLogcatPage(dark: Boolean) {
    val c = ThemeBackground.colors(dark)
    val ctx = LocalContext.current
    var errorsOnly by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }
    val uid = remember { ctx.applicationInfo.uid }
    val text = remember(tick, errorsOnly) { LogPipe.tail(TAIL_LINES, uid, errorsOnly) }

    Column(Modifier.fillMaxSize().testTag(FleetLogcatTags.PAGE).padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Errors only", Modifier.weight(1f), color = c.ink, fontSize = 13.sp)
            Switch(checked = errorsOnly, onCheckedChange = { errorsOnly = it }, modifier = Modifier.testTag(FleetLogcatTags.ERRORS_ONLY))
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LogcatButton("Copy", c, FleetLogcatTags.COPY_MD) { copyMarkdown(ctx, errorsOnly, uid) }
            LogcatButton("Save", c, FleetLogcatTags.SAVE_HTML) { saveHtml(ctx, errorsOnly, uid) }
            LogcatButton("Share", c, FleetLogcatTags.SHARE) { shareMarkdown(ctx, errorsOnly, uid) }
        }
        Text(
            text,
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())
                .testTag(FleetLogcatTags.BODY),
            color = c.ink, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 15.sp,
        )
    }
}

@Composable
private fun LogcatButton(label: String, c: ThemeColors, tag: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Text(
        label,
        Modifier.clip(shape).background(c.tabSelected, shape).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp).testTag(tag),
        color = c.onTabSelected, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
    )
}

private fun snapshot(ctx: Context, errorsOnly: Boolean, uid: Int): FleetLogExport.Snapshot {
    val decl = FleetConfigs.current
    val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
    val body = LogPipe.tail(TAIL_LINES, uid, errorsOnly)
    return FleetLogExport.Snapshot(
        app = decl.name.ifBlank { ctx.packageName },
        pkg = ctx.packageName,
        sha = decl.sha,
        capturedAt = stamp,
        health = LogPipe.health,
        errorsOnly = errorsOnly,
        lines = body.split("\n").filter { it.isNotBlank() },
    )
}

private fun copyMarkdown(ctx: Context, errorsOnly: Boolean, uid: Int) {
    runCatching {
        val md = FleetLogExport.markdown(snapshot(ctx, errorsOnly, uid))
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("logcat", md))
        Toast.makeText(ctx, "Logcat copied as Markdown", Toast.LENGTH_SHORT).show()
    }
}

private fun shareMarkdown(ctx: Context, errorsOnly: Boolean, uid: Int) {
    runCatching {
        val md = FleetLogExport.markdown(snapshot(ctx, errorsOnly, uid))
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, md).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(Intent.createChooser(send, "Share logcat").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun saveHtml(ctx: Context, errorsOnly: Boolean, uid: Int) {
    runCatching {
        val html = FleetLogExport.html(snapshot(ctx, errorsOnly, uid))
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val out = File(dir, "logcat-" + System.currentTimeMillis() + ".html")
        out.writeText(html)
        Toast.makeText(ctx, "Saved " + out.absolutePath, Toast.LENGTH_LONG).show()
    }
}
