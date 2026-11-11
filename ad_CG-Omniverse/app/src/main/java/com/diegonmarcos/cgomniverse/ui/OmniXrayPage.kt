package com.diegonmarcos.cgomniverse.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.cgomniverse.BuildConfig
import com.diegonmarcos.cgomniverse.OmniProbe
import com.diegonmarcos.cgomniverse.OmniXray
import com.diegonmarcos.cloudlib.web.PageConsole
import com.diegonmarcos.superapp.themebg.ThemeBackground
import com.diegonmarcos.superapp.themebg.ThemeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** Test tags of the X-Ray App page (UiAutomator resource ids). */
object OmniXrayTags {
    const val PAGE = "omni_xray_page"
    const val VERDICT = "omni_xray_verdict"
    const val COPY_MD = "omni_xray_copy_md"
    const val COPY_JSON = "omni_xray_copy_json"
    const val SHARE = "omni_xray_share"
    const val RETEST = "omni_xray_retest"
    fun section(id: String): String = "omni_xray_section_$id"
}

/**
 * #1631 Configs > X-Ray App for CG-Omniverse: the real state of the 3D render pipeline (WebView, page load, WebGL context, asset
 * fetch, GLB decode, canvas surface, draw), the verdict naming the first failed stage, every measured field under it, and the
 * copyable Markdown / JSON / share export. Live: the page's own probe and the WebView callbacks are re-read every second; the native
 * probe (EGL, WebView package, assets, GLB header, detached WebGL probe) runs when the page opens and on Retest.
 */
@Composable
fun OmniXrayPage(dark: Boolean = true) {
    val c = ThemeBackground.colors(dark)
    val ctx = LocalContext.current
    var probe by remember { mutableStateOf<OmniXray.Probe?>(null) }
    var probing by remember { mutableStateOf(true) }
    var run by remember { mutableIntStateOf(0) }
    var copied by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }
    LaunchedEffect(run) {
        probing = true
        val gl = suspendCoroutine<OmniXray.WebGl?> { k -> OmniProbe.webgl(ctx) { k.resume(it) } }
        probe = withContext(Dispatchers.IO) { OmniProbe.run(ctx, gl) }
        probing = false
        android.util.Log.w("OmniXray", "probe done: " + OmniXray.verdict(OmniXray.stages(probe, OmniXray.avatar())))
    }
    val report = remember(tick, probe) { build(ctx, probe) }
    var lastVerdict by remember { mutableStateOf("") }
    LaunchedEffect(report.verdict) {
        if (report.verdict != lastVerdict) { lastVerdict = report.verdict; android.util.Log.w("OmniXray", report.verdict) }
    }
    Column(Modifier.fillMaxSize().testTag(OmniXrayTags.PAGE).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Copy MD", c, OmniXrayTags.COPY_MD) { copy(ctx, "X-Ray App (Markdown)", OmniXray.markdown(report)); copied = "Markdown copied" }
            Btn("Copy JSON", c, OmniXrayTags.COPY_JSON) { copy(ctx, "X-Ray App (JSON)", OmniXray.json(report)); copied = "JSON copied" }
            Btn("Share", c, OmniXrayTags.SHARE) { share(ctx, OmniXray.markdown(report)) }
            Btn(if (probing) "Probing..." else "Retest", c, OmniXrayTags.RETEST) { if (!probing) run++ }
        }
        if (copied.isNotEmpty()) Text(copied, color = c.accent, fontSize = 11.sp)
        val bad = report.stages.any { it.state == OmniXray.State.FAIL }
        Card("Verdict", c, Modifier.testTag(OmniXrayTags.VERDICT)) {
            Text(report.verdict, color = if (bad) Color(0xFFFF6B6B) else c.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        report.sections.forEach { s ->
            Card(s.title, c, Modifier.testTag(OmniXrayTags.section(s.id))) {
                s.rows.forEach { r ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                        Text(r.key, color = c.inkDim, fontSize = 11.sp)
                        Text(r.value, color = valueColor(r.value, c), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

private fun valueColor(v: String, c: ThemeColors): Color = when {
    v.startsWith("[FAIL]") -> Color(0xFFFF6B6B)
    v.startsWith("[WARN]") -> Color(0xFFFFC857)
    v.startsWith("[OK]") -> Color(0xFF6BE675)
    else -> c.ink
}

private fun build(ctx: Context, p: OmniXray.Probe?): OmniXray.Report {
    val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
    val dm = ctx.resources.displayMetrics
    val device = listOf(
        OmniXray.Row("model", Build.MANUFACTURER + " " + Build.MODEL),
        OmniXray.Row("soc", if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL else Build.HARDWARE),
        OmniXray.Row("android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"),
        OmniXray.Row("abis", Build.SUPPORTED_ABIS.joinToString(", ")),
        OmniXray.Row("display", dm.widthPixels.toString() + "x" + dm.heightPixels + " @ " + dm.densityDpi + " dpi"),
        OmniXray.Row("max heap", (Runtime.getRuntime().maxMemory() / 1048576).toString() + " MB"),
    )
    val console = PageConsole.errors().takeLast(30).map { PageConsole.format(it) + " [" + it.page.takeLast(60) + "]" }
    return OmniXray.report(
        "CG-Omniverse", BuildConfig.VERSION_NAME, BuildConfig.GIT_SHORT_SHA, stamp, p, OmniXray.avatar(), OmniXray.worlds(),
        console, OmniXray.events(), device,
    )
}

private fun copy(ctx: Context, label: String, text: String) {
    runCatching {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))
    }
}

private fun share(ctx: Context, text: String) {
    runCatching {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(Intent.createChooser(send, "Share X-Ray App").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
private fun Card(title: String, c: ThemeColors, modifier: Modifier, body: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier.fillMaxWidth().padding(bottom = 10.dp).clip(shape).background(c.tabStrip, shape).border(1.dp, c.islandBorder, shape).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(title, color = c.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        body()
    }
}

@Composable
private fun Btn(label: String, c: ThemeColors, tag: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Text(
        label,
        Modifier.clip(shape).background(c.tabSelected, shape).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp).testTag(tag),
        color = c.onTabSelected, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
    )
}
