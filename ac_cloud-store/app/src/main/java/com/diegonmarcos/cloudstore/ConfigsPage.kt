package com.diegonmarcos.cloudstore

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.ai.AiFleetChat
import com.diegonmarcos.superapp.ai.AiSettingsPickers
import com.diegonmarcos.superapp.ai.materialAiTheme
import com.diegonmarcos.superapp.appstore.FeedViewer
import com.diegonmarcos.superapp.appstore.FleetBearer
import com.diegonmarcos.superapp.appstore.PhoneAppActions
import com.diegonmarcos.superapp.appstore.StoreDensity
import com.diegonmarcos.superapp.appstore.StoreSourcePrefs
import com.diegonmarcos.superapp.core.StoreNotifyPrefs
import com.diegonmarcos.superapp.fleetdata.FleetDataRouting
import com.diegonmarcos.superapp.fleetdata.provider.FleetDataEndpoint
import com.diegonmarcos.superapp.fleetdata.provider.FleetDataSchedule
import com.diegonmarcos.superapp.fleetdata.ui.FleetDataActivity
import com.diegonmarcos.superapp.shellclient.ShellClient

/**
 * Settings > Configs: a PAGE with its own top tabs, not a screen that is one thing. The tabs are the
 * build.json::ui.sections `settings` pages (Sources | ADB Shell | AI | Notifications | Torrent | Data | About), drawn by MainActivity's
 * PageTabs like every other section's; [page] is the open one. Each page below is declarative: it lists what the
 * Store already declares (its feeds, the installer ladder, its privileged-channel needs, the fleet AI gateway) and
 * writes only the owner's switches. All sizes read [StoreDensity]; the app is wrapped in KitDenseMaterial.
 */
object ConfigsPages {
    const val SOURCES = "sources"
    const val ADB = "adb"
    const val AI = "ai"
    const val NOTIFICATIONS = "notifications"
    const val DATA = "data"
    const val TORRENT = "torrent"
    const val ABOUT = "about"

    /** Test tags: the open page's body, and a switch by what it flips. */
    fun body(page: String) = "configs_page_$page"
    fun feedSwitch(id: String) = "configs_feed_$id"
    fun notifySwitch(kind: StoreNotifyPrefs.Kind, channel: StoreNotifyPrefs.Channel) = "configs_notify_${StoreNotifyPrefs.key(kind, channel)}"
}

@Composable
fun ConfigsPage(page: String) {
    // #1630 the fleet-default tail pages (Agents, Perms, Logcat, X-Ray App) are libs:ui-nav-bars' and scroll themselves; About stays the Store's own.
    if (page == "perms" || com.diegonmarcos.superapp.fleetconfigs.FleetConfigsExtensions.isOptInTail(page)) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().testTag(ConfigsPages.body(page))) {
            com.diegonmarcos.superapp.fleetconfigs.FleetConfigsPageBody(page)
        }
        return
    }
    val pad = StoreDensity.dpValue(StoreDensity.S12).dp
    // #1512 every page (the shell-broker page included) is a column this host scrolls.
    Column(
        Modifier.fillMaxSize().testTag(ConfigsPages.body(page)).verticalScroll(rememberScrollState()).padding(pad),
        verticalArrangement = Arrangement.spacedBy(StoreDensity.dpValue(StoreDensity.S8).dp),
    ) {
        when (page) {
            ConfigsPages.ADB -> AdbPage()
            ConfigsPages.AI -> AiPage()
            ConfigsPages.NOTIFICATIONS -> NotificationsPage()
            ConfigsPages.DATA -> DataPage()
            ConfigsPages.TORRENT -> TorrentConfigPage()
            ConfigsPages.ABOUT -> AboutPage()
            else -> SourcesPage()
        }
    }
}

@Composable internal fun Head(text: String) = Text(text, fontSize = StoreDensity.T_TITLE.sp, color = MaterialTheme.colorScheme.onSurface)
@Composable internal fun Note(text: String) = Text(text, fontSize = StoreDensity.T_CAPTION.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
@Composable internal fun Body(text: String) = Text(text, fontSize = StoreDensity.T_BODY.sp, color = MaterialTheme.colorScheme.onSurface)

@Composable
internal fun SwitchRow(label: String, sub: String?, on: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) { Body(label); if (sub != null) Note(sub) }
        Switch(on, onChange, Modifier.testTag(tag))
    }
}

// ── Sources ───────────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun SourcesPage() {
    val ctx = LocalContext.current
    Head("Feeds")
    Note("The repository feeds the Cloud page's Commits and CI-CD panels read (appstore-feeds.json). Off = not read.")
    val feeds = remember { FeedViewer.feeds(ctx) }
    feeds.forEach { f ->
        var on by remember(f.id) { mutableStateOf(StoreSourcePrefs.enabled(ctx, f.id)) }
        SwitchRow(f.label, f.blurb, on, ConfigsPages.feedSwitch(f.id)) { on = it; StoreSourcePrefs.set(ctx, f.id, it) }
    }
    if (feeds.isEmpty()) Note("No feed is declared.")

    Head("Install sources")
    Note("The ladder tried, in order, for an app this phone has no store for (appstore-install-sources.json). Declared, read-only here.")
    val ladder = remember { runCatching { PhoneAppActions.resolver(PhoneAppActions.sources(ctx)).kinds }.getOrDefault(emptyList()) }
    ladder.forEachIndexed { i, k -> Body("${i + 1}. ${k.label}" + if (k.fetches) "" else "  (hand-off to its own store)") }
    if (ladder.isEmpty()) Note("No install source is declared.")

    Head("Fleet token")
    FleetTokenEntry(ctx)
}

/** #866 the fleet token for the feeds, used only when Cloud Account / SuperApp is not supplying it. */
@Composable
private fun FleetTokenEntry(ctx: Context) {
    val own = remember { FleetBearer.Own(ctx) }
    var token by remember { mutableStateOf(own.token) }
    var source by remember { mutableStateOf(FleetBearer.source(ctx)) }
    Note(when (source) {
        FleetBearer.Source.ACCOUNT -> "Supplied by Cloud Account; the entry below is not used."
        FleetBearer.Source.SUPERAPP -> "Supplied by Cloud SuperApp; the entry below is not used."
        FleetBearer.Source.OWN -> "Using the token entered below."
        FleetBearer.Source.NONE -> "No token: the Commits and CI/CD feeds read their public sources."
    })
    OutlinedTextField(
        value = token,
        onValueChange = { token = it; own.token = it; source = FleetBearer.source(ctx) },
        label = { Text("Bearer token", fontSize = StoreDensity.T_CAPTION.sp) },
        textStyle = TextStyle(fontSize = StoreDensity.T_BODY.sp),
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth().padding(top = StoreDensity.dpValue(StoreDensity.S8).dp),
    )
}

// ── ADB Shell ─────────────────────────────────────────────────────────────────────────────────────────

/**
 * #1512 The shell the Store installs through is the SuperApp's broker, not a channel of its own: this page reports the broker's state
 * and opens the SuperApp's arm flow. Nothing to pair or reconnect here.
 */
@Composable
private fun AdbPage() {
    val ctx = LocalContext.current
    var status by remember { mutableStateOf<com.diegonmarcos.superapp.shellclient.ShellStatus?>(null) }
    var tick by remember { mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(tick) {
        status = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ShellClient.status(ctx) }
    }
    Head("Shell broker")
    ShellBrokerPage.lines(status).forEach { Body(it) }
    Button(onClick = { ShellClient.openArmPage(ctx); tick++ }, modifier = Modifier.testTag(ShellBrokerPage.ARM_TAG), content = { Text("Arm in Cloud C3 Webserver") })
    Button(onClick = { tick++ }, content = { Text("Re-check") })
    SilentUpdatesCard()
    Note("Silent installs and the self-update run through the ADB shell of Cloud C3 Webserver (the APK is streamed over its stdin door), under this app's own allowlist entry. The broker never keeps a connection open between calls.")
}

// ── AI ────────────────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun AiPage() {
    val ctx = LocalContext.current
    val chat = remember { AiFleetChat.chat(ctx, BuildConfig.AI_GATEWAY_URL) }
    Head("Agent and model")
    Note("The same pickers the AI chat uses: a choice here is the one the chat answers with.")
    AiSettingsPickers(chat, materialAiTheme())
    Head("Gateway")
    Body(BuildConfig.AI_GATEWAY_URL.ifBlank { "not configured" })
    Note("Answers go through the fleet AI gateway, which holds the provider (OpenRouter) key. No token is entered or stored on this phone.")
}

// ── Notifications ─────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun NotificationsPage() {
    val ctx = LocalContext.current
    UpdateChecksCard()   // #1078
    Head("Store notifications")
    Note("What Cloud Store tells you, and where. Each kind has a switch for the system notification and one for the in-app feed.")
    StoreNotifyPrefs.Kind.values().forEach { kind ->
        Body(kind.label)
        Note(kind.blurb)
        StoreNotifyPrefs.Channel.values().forEach { ch ->
            var on by remember(kind, ch) { mutableStateOf(StoreNotifyPrefs.allows(StoreNotifyPrefs.off(ctx), kind, ch)) }
            SwitchRow(ch.label, null, on, ConfigsPages.notifySwitch(kind, ch)) { on = it; StoreNotifyPrefs.set(ctx, kind, ch, it) }
        }
    }
}

// ── About ─────────────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun AboutPage() {
    val ctx = LocalContext.current
    val info = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }.getOrNull() }
    Head(ctx.getString(R.string.app_name))
    Body("Version ${info?.versionName ?: BuildConfig.VERSION_NAME}")
    Note("Build ${BuildConfig.GIT_SHORT_SHA}  ·  ${BuildConfig.BUILD_TIMESTAMP}")
    Note(ctx.packageName)
    Head("Navigation")
    com.diegonmarcos.superapp.navbartop2.NavModeConfigCard(MainActivity.NAV.sections)
    Head("Licence")
    Body("Part of the Cloud constellation (diegonmarcos/cloudy-u-android).")
    Note("The licence of every file and the attribution for the open-source components built in (Shizuku API, libadb-android, BouncyCastle, AndroidX, Compose) are in LICENSING.md and licenses/inventory.json in the repository.")
}

// ── Data (#1547) ─────────────────────────────────────────────────────────────

/** The fleet data this provider serves (authority cloud.store.data): what it holds, when it last synced, and the browser (tree, JSON viewer, sync, consumers, diff). */
@Composable
private fun DataPage() {
    val ctx = LocalContext.current
    val ep = remember { FleetDataEndpoint.of(ctx, FleetDataRouting.STORE) }
    val held = remember { ep.store.list().size }
    val st = remember { ep.status() }
    Head("Fleet data")
    Note("Cloud Store serves the engine defaults and reference data every fleet app reads at runtime. Nothing here is baked into an APK.")
    Body("$held datasets held" + if (st != null) ", ${st.failed.size} failed in the last sync" else ", not synced yet")
    Button(onClick = { FleetDataActivity.open(ctx, FleetDataRouting.STORE) }) { Text("Open the data browser") }
    Button(onClick = { FleetDataSchedule.syncNow(ctx, FleetDataRouting.STORE) }) { Text("Sync now") }
}
