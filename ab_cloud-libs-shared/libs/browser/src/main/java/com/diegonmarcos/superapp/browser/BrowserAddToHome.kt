package com.diegonmarcos.superapp.browser

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import java.io.ByteArrayOutputStream

/**
 * #1840 Add to Home Screen. Two paths, tried in order, and the sheet says which one answered:
 *  1. the system pin (ShortcutManager.requestPinShortcut): the default launcher's own confirmation. With Cloud SuperApp as the launcher its
 *     PinShortcutActivity accepts it and draws the shortcut on its home (removable there);
 *  2. when the launcher in charge refuses ("This launcher cannot pin shortcuts"), the DECLARED IPC into Cloud SuperApp's home: an ordered
 *     broadcast to its signature-guarded AddHomeShortcutReceiver; its result code is the success or failure shown.
 */
object BrowserAddToHome {
    const val SUPERAPP_PKG = "com.diegonmarcos.superapp"
    const val IPC_ACTION = "com.diegonmarcos.superapp.action.ADD_HOME_SHORTCUT"
    const val IPC_RECEIVER = "com.diegonmarcos.superapp.launcher.pins.AddHomeShortcutReceiver"
    const val IPC_PERMISSION = "com.diegonmarcos.cloud.permission.CONSTELLATION_DATA"
    const val MAX_ICON_BYTES = 96 * 1024

    enum class Route { SYSTEM_PIN, SUPERAPP_IPC, NONE }
    enum class Outcome { PIN_SENT_TO_LAUNCHER, ADDED_TO_SUPERAPP, FAILED }
    data class Result(val outcome: Outcome, val message: String) { val ok: Boolean get() = outcome != Outcome.FAILED }

    /** Pure: the route. The launcher's own pin when it supports one, else Cloud SuperApp's home over the IPC when it is installed, else none. */
    fun route(systemPinSupported: Boolean, superAppInstalled: Boolean): Route = when {
        systemPinSupported -> Route.SYSTEM_PIN
        superAppInstalled -> Route.SUPERAPP_IPC
        else -> Route.NONE
    }

    /** Pure: the name the shortcut gets: the edited text, else the page title, else the host; one line, at most 64 characters. */
    fun cleanName(edited: String?, title: String?, url: String): String {
        val host = BrowserSitePolicy.hostOf(url)
        return listOf(edited, title, host, url).map { (it ?: "").replace('\n', ' ').trim() }.first { it.isNotEmpty() }.take(64)
    }

    /** Pure: the sentence the sheet shows for an outcome. */
    fun say(outcome: Outcome, launcher: String, name: String): String = when (outcome) {
        Outcome.PIN_SENT_TO_LAUNCHER -> "“$name” was sent to $launcher. If it asks, confirm there; the shortcut then sits on your home screen."
        Outcome.ADDED_TO_SUPERAPP -> "Added “$name” to the Cloud SuperApp home (Cloud page, Pinned). Hold it there to remove it."
        Outcome.FAILED -> "Could not add “$name”: $launcher"
    }

    fun superAppInstalled(ctx: Context): Boolean = runCatching { ctx.packageManager.getPackageInfo(SUPERAPP_PKG, 0); true }.getOrDefault(false)

    /** The home app the system would pin to, by label ("Cloud SuperApp", "Pixel Launcher"); "no launcher" when none resolves. */
    fun launcherLabel(ctx: Context): String = runCatching {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val ri = ctx.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
        ri?.loadLabel(ctx.packageManager)?.toString()?.takeIf { it.isNotBlank() && ri.activityInfo.packageName != "android" } ?: "your launcher"
    }.getOrDefault("your launcher")

    fun iconPng(bmp: Bitmap?): ByteArray? {
        if (bmp == null || bmp.isRecycled || bmp.width <= 0) return null
        val scaled = if (bmp.width > 192) Bitmap.createScaledBitmap(bmp, 192, (192f * bmp.height / bmp.width).toInt().coerceAtLeast(1), true) else bmp
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray().takeIf { it.size <= MAX_ICON_BYTES }
    }

    /** The IPC: an ordered broadcast to Cloud SuperApp's receiver; [done] gets the outcome on the main thread (no receiver at all = failed). */
    fun sendToSuperApp(ctx: Context, url: String, name: String, icon: Bitmap?, done: (Result) -> Unit) {
        val i = Intent(IPC_ACTION).setComponent(ComponentName(SUPERAPP_PKG, IPC_RECEIVER))
            .putExtra("id", "page:" + Integer.toHexString(url.hashCode())).putExtra("label", name).putExtra("url", url).putExtra("pkg", ctx.packageName)
        iconPng(icon)?.let { i.putExtra("icon", it) }
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                done(if (resultCode == Activity.RESULT_OK) Result(Outcome.ADDED_TO_SUPERAPP, say(Outcome.ADDED_TO_SUPERAPP, "", name))
                else Result(Outcome.FAILED, say(Outcome.FAILED, resultData?.takeIf { it.isNotBlank() } ?: "Cloud SuperApp did not answer (is it installed and up to date?)", name)))
            }
        }
        val ok = runCatching { ctx.sendOrderedBroadcast(i, IPC_PERMISSION, rx, Handler(Looper.getMainLooper()), Activity.RESULT_CANCELED, null, null) }
        if (ok.isFailure) done(Result(Outcome.FAILED, say(Outcome.FAILED, "the broadcast was refused (${ok.exceptionOrNull()?.javaClass?.simpleName})", name)))
    }

    /** Run the add: system pin first ([systemPin] true = the launcher took the request), else the IPC; [done] gets the result. */
    fun add(ctx: Context, url: String, name: String, icon: Bitmap?, systemPin: () -> Boolean, done: (Result) -> Unit) {
        val launcher = launcherLabel(ctx)
        val supported = runCatching { androidx.core.content.pm.ShortcutManagerCompat.isRequestPinShortcutSupported(ctx) }.getOrDefault(false)
        val installed = superAppInstalled(ctx)
        val r = route(supported, installed)
        BrowserLog.i(BrowserLog.TAB, "add-to-home", "route" to r, "launcher" to launcher, "superapp" to installed, "url" to BrowserLog.url(url))
        when (r) {
            Route.SYSTEM_PIN ->
                if (systemPin()) done(Result(Outcome.PIN_SENT_TO_LAUNCHER, say(Outcome.PIN_SENT_TO_LAUNCHER, launcher, name)))
                else if (installed) sendToSuperApp(ctx, url, name, icon, done)
                else done(Result(Outcome.FAILED, say(Outcome.FAILED, "$launcher refused the shortcut", name)))
            Route.SUPERAPP_IPC -> sendToSuperApp(ctx, url, name, icon, done)
            Route.NONE -> done(Result(Outcome.FAILED, say(Outcome.FAILED, "$launcher cannot pin shortcuts and Cloud SuperApp is not installed", name)))
        }
    }
}

/** The sheet's state: the editable name, what it will be added to, and the result once it ran. */
class AddToHomeState(val url: String, title: String, val icon: Bitmap?, val target: String, val superAppPresent: Boolean) {
    var name by mutableStateOf(title)
    var result by mutableStateOf<BrowserAddToHome.Result?>(null)
    var busy by mutableStateOf(false)
}

/** #1840 the add-to-home sheet: the site's icon, an editable name, the launcher it goes to, and a clear success or failure line. */
@Composable
fun BrowserAddToHomeSheet(s: AddToHomeState, onAdd: () -> Unit, onClose: () -> Unit) {
    val p = LocalKitPalette.current
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(onClick = onClose)) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(p.surface, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .clickable(enabled = false) {}.padding(16.dp).testTag("browser:addhome"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Add to Home Screen", color = p.textPrimary, style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(64.dp).background(p.surfaceSelected, RoundedCornerShape(14.dp)).testTag("browser:addhome:icon"), contentAlignment = Alignment.Center) {
                    if (s.icon != null) Image(s.icon.asImageBitmap(), null, Modifier.size(48.dp))
                    else Text(s.name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "•", color = p.textPrimary, style = MaterialTheme.typography.headlineMedium)
                }
                OutlinedTextField(s.name, { s.name = it.take(64) }, Modifier.weight(1f).testTag("browser:addhome:name"), singleLine = true, label = { Text("Name") })
            }
            Text("Goes to: ${s.target}" + if (s.superAppPresent && !s.target.contains("SuperApp")) "  (Cloud SuperApp home is the fallback)" else "",
                color = p.textSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("browser:addhome:target"))
            s.result?.let { r ->
                Text(r.message, color = if (r.ok) p.accent else Color(0xFFFF6B6B), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(if (r.ok) "browser:addhome:ok" else "browser:addhome:failed"))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClose, Modifier.testTag("browser:addhome:close")) { Text(if (s.result == null) "Cancel" else "Close") }
                if (s.result?.ok != true) TextButton(onAdd, enabled = !s.busy && s.name.isNotBlank(), modifier = Modifier.testTag("browser:addhome:add")) { Text(if (s.result == null) "Add" else "Try again") }
            }
        }
    }
}
