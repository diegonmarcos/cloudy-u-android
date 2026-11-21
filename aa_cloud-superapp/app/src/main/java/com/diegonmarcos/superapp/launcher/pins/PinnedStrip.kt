package com.diegonmarcos.superapp.launcher.pins

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Process
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/** #1840 test tags of the pinned-shortcuts section (UiAutomator resource ids and Compose tests). */
object PinnedTags {
    const val SECTION = "pins:section"
    fun tile(key: String) = "pins:tile:$key"
    const val REMOVE = "pins:remove"
}

/** Launch a pin: a SYSTEM pin through LauncherApps.startShortcut, an IPC pin as its web address in the app that added it. */
fun launchPin(ctx: Context, pin: PinnedShortcutStore.Pin): Boolean = runCatching {
    when (pin.kind) {
        PinnedShortcutStore.Kind.SYSTEM ->
            (ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps).startShortcut(pin.pkg, pin.id, null, null, Process.myUserHandle())
        PinnedShortcutStore.Kind.IPC ->
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pin.url)).setPackage(pin.pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    true
}.getOrElse { Toast.makeText(ctx, "Could not open “${pin.label}”", Toast.LENGTH_SHORT).show(); false }

/**
 * #1840 the pinned shortcuts on the home: one sideways line of tiles under a "Pinned" title, drawn only when something is pinned. A tap opens the
 * shortcut; hold offers "Remove from home". Re-reads when [PinnedShortcutStore.changes] moves, so a pin accepted while the page shows appears at once.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PinnedStrip(titleOf: @Composable (String) -> Unit) {
    val ctx = LocalContext.current
    val version = PinnedShortcutStore.changes.intValue
    val pins = remember(version) { PinnedShortcutStore.list(ctx) }
    if (pins.isEmpty()) return
    val p = LocalKitPalette.current
    Column(Modifier.fillMaxWidth().testTag(PinnedTags.SECTION)) {
        titleOf("Pinned")
        LazyRow(Modifier.fillMaxWidth()) {
            items(pins, key = { it.key }) { pin ->
                var menu by remember { mutableStateOf(false) }
                val bmp = remember(pin.iconFile) { pin.iconFile.takeIf { it.isNotBlank() }?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() } }
                Box(Modifier.width(60.dp)) {
                    Column(
                        Modifier.width(60.dp).combinedClickable(onLongClick = { menu = true }) { launchPin(ctx, pin) }.padding(6.dp).testTag(PinnedTags.tile(pin.key)),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (bmp != null) Image(bmp.asImageBitmap(), pin.label, Modifier.size(40.dp))
                        else Text(pin.label.take(1).uppercase(), color = p.textPrimary, style = MaterialTheme.typography.titleLarge, modifier = Modifier.size(40.dp))
                        Text(pin.label, color = p.textPrimary.copy(alpha = 0.8f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Remove from home") }, modifier = Modifier.testTag(PinnedTags.REMOVE),
                            onClick = { menu = false; PinnedShortcutStore.remove(ctx, pin.key) })
                    }
                }
            }
        }
    }
}
