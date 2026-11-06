package com.diegonmarcos.superapp.perms

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** #1403 tags of the filter chips and the actions row. */
object PermsToolbarTags {
    const val FILTERS = "perms:filters"
    const val ACTIONS = "perms:actions"
    const val RESULT = "perms:actions:result"
    fun state(f: StateFilter) = "perms:filter:state:${f.name.lowercase()}"
    fun kind(f: KindFilter) = "perms:filter:kind:${f.name.lowercase()}"
    fun action(id: String) = "perms:action:$id"
}

/**
 * #1403 the row of filter chips (State, then the Kind chips the page has) and the actions row: Grant all (runtime dialogs),
 * Ask Missing (each missing dialog or page in turn), Adb Connect, Grant All Adb missing, Grant All (denied also), Copy Adb
 * Grants. The adb actions grant every kind over [adb] and are off, with the reason, when the app has no channel.
 */
@Composable
internal fun PermsToolbar(
    items: List<PermItem>,
    states: Set<StateFilter>, kinds: Set<KindFilter>,
    onStates: (Set<StateFilter>) -> Unit, onKinds: (Set<KindFilter>) -> Unit,
    onGrantRuntime: () -> Unit, onAskMissing: () -> Unit,
    adb: PermsAdb?, onAdb: (PermsActions.Mode) -> Unit, onCopy: () -> Unit,
    result: String?,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = PermsType.pad)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).permsTag(PermsToolbarTags.FILTERS),
            horizontalArrangement = Arrangement.spacedBy(PermsType.gap), verticalAlignment = Alignment.CenterVertically) {
            for (f in StateFilter.values()) FilterChip(selected = f in states, onClick = { onStates(if (f in states) states - f else states + f) },
                label = { Text(f.label, fontSize = PermsType.label) }, modifier = Modifier.permsTag(PermsToolbarTags.state(f)))
            Text("·", fontSize = PermsType.label)
            for (k in PermsActions.kindsPresent(items)) FilterChip(selected = k in kinds, onClick = { onKinds(if (k in kinds) kinds - k else kinds + k) },
                label = { Text(k.label, fontSize = PermsType.label) }, modifier = Modifier.permsTag(PermsToolbarTags.kind(k)))
        }
        val up = adb?.channel?.up() == true
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).permsTag(PermsToolbarTags.ACTIONS),
            horizontalArrangement = Arrangement.spacedBy(PermsType.gap)) {
            Action("grant-all", "Grant all", true, onGrantRuntime)
            Action("ask-missing", "Ask Missing", true, onAskMissing)
            Action("adb-connect", "Adb Connect", adb != null) { adb?.connect?.invoke() }
            Action("adb-missing", "Grant All Adb missing", up) { onAdb(PermsActions.Mode.ADB_MISSING) }
            Action("adb-denied", "Grant All (denied also)", up) { onAdb(PermsActions.Mode.DENIED_ALSO) }
            Action("adb-copy", "Copy Adb Grants", true, onCopy)
        }
        val why = when { adb == null -> "Adb actions: this app has no adb channel; Copy Adb Grants still works."; !up -> "Adb actions: ${PermsGrant.DISABLED_REASON} (Adb Connect)."; else -> null }
        if (why != null) Text(why, fontSize = PermsType.label, modifier = Modifier.padding(top = PermsType.gap))
        if (result != null) Text(result, fontSize = PermsType.caption, modifier = Modifier.padding(top = PermsType.gap).permsTag(PermsToolbarTags.RESULT))
    }
}

@Composable
private fun Action(id: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    // 30 dp: the dense Configs pages hold buttons to 32 dp (ConfigsScreensTest)
    FilledTonalButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        modifier = Modifier.height(30.dp).permsTag(PermsToolbarTags.action(id))) {
        Text(label, fontSize = PermsType.label)
    }
}

/** The app's own components for the bind-service entries (entry id -> "pkg/class"), and the a11y list in force. */
internal object PermsComponents {
    fun of(ctx: Context): Map<String, String> {
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        val info = runCatching { pm.getPackageInfo(ctx.packageName, PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS) }.getOrNull()
            ?: return emptyMap()
        val out = HashMap<String, String>()
        fun put(id: String, perm: String, names: List<Pair<String, String?>>) {
            names.firstOrNull { it.second == perm }?.let { out[id] = "${ctx.packageName}/${it.first}" }
        }
        val services = info.services.orEmpty().map { it.name to it.permission }
        put("notification_listener", "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE", services)
        put("accessibility", "android.permission.BIND_ACCESSIBILITY_SERVICE", services)
        put("device_admin", "android.permission.BIND_DEVICE_ADMIN", info.receivers.orEmpty().map { it.name to it.permission })
        return out
    }

    fun enabledA11y(ctx: Context): String =
        runCatching { Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) }.getOrNull().orEmpty()

    /** The adb plan for this app, then run off the main thread. */
    suspend fun grant(ctx: Context, items: List<PermItem>, mode: PermsActions.Mode, adb: PermsAdb): String = withContext(Dispatchers.IO) {
        PermsActions.run(PermsActions.plan(ctx.packageName, items, mode, of(ctx), enabledA11y(ctx)), adb.channel).text()
    }

    fun copy(ctx: Context, items: List<PermItem>): String {
        val text = PermsActions.copyText(PermsActions.plan(ctx.packageName, items, PermsActions.Mode.DENIED_ALSO, of(ctx), enabledA11y(ctx)))
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("adb grants", text))
        return if (text.isEmpty()) "Nothing missing to copy." else "Copied ${text.lines().size} lines:\n$text"
    }
}
