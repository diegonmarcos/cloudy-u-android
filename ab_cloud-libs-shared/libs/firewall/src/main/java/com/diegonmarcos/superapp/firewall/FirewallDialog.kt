package com.diegonmarcos.superapp.firewall

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.DialogFragment
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitConfirmDialog
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.KitSwitchRow
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * Firewall control screen — opened from the About → Firewall gray button.
 *
 * A master switch enables/disables the no-root engine (requesting the
 * system VPN consent on first enable). Below it, a scrollable list of
 * launchable apps, each opening a per-app rule editor.
 *
 * 2026-10-16 owner decision: all apps must be Compose. The dialog is a ComposeView under
 * CloudKitTheme (libs:ui-kit) with its palette in the fw_* colour resources; the host contract
 * (DialogFragment, TAG) is unchanged.
 */
class FirewallDialog : DialogFragment() {

    private val vpnConsent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val ctx = context ?: return@registerForActivityResult
        if (result.resultCode == Activity.RESULT_OK) FirewallController.start(ctx)
        else FirewallController.stop(ctx)
        refreshHeader()
    }

    /** What the header and the master switch show; re-read by [refreshHeader]. */
    private var info by mutableStateOf<FirewallInfo.State?>(null)

    /** Bumped after a rule is saved so every row re-reads its summary. */
    private var ruleVersion by mutableIntStateOf(0)

    /** The app whose rule editor is open, if any. */
    private var editing by mutableStateOf<ApplicationInfo?>(null)
    private var usagePrompt by mutableStateOf(false)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        refreshHeader()
        return ComposeView(ctx).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val palette = KitPalette(
                    surface = colorResource(R.color.fw_card),
                    surfaceSelected = colorResource(R.color.fw_selected),
                    textPrimary = colorResource(R.color.fw_text),
                    textSecondary = colorResource(R.color.fw_text_dim),
                    accent = colorResource(R.color.fw_accent),
                    hairline = colorResource(R.color.fw_hairline),
                    tileInk = colorResource(R.color.fw_bg),
                )
                CloudKitTheme(palette) { Screen() }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.85f).toInt()
        )
    }

    @Composable
    private fun Screen() {
        val ctx = LocalContext.current
        val p = LocalKitPalette.current
        val s = info
        // Per-app block list (launchable apps only, alphabetical).
        val apps = remember {
            val pm = ctx.packageManager
            pm.getInstalledApplications(0)
                .filter { pm.getLaunchIntentForPackage(it.packageName) != null && it.packageName != ctx.packageName }
                .map { it to pm.getApplicationLabel(it).toString() }
                .sortedBy { it.second.lowercase() }
        }
        // #1719 NG Firewall's own sub pages are views of this one screen: status (header, master switch, explainer),
        // apps (the full per-app list) and rules (only the apps that carry a non-default rule). SECTION_ALL, the default,
        // is the whole screen exactly as before, so nothing that opened the dialog changes.
        val section = arguments?.getString(ARG_SECTION) ?: SECTION_ALL
        val showHeader = section == SECTION_ALL || section == SECTION_STATUS
        val showList = section != SECTION_STATUS
        val listed = if (section == SECTION_RULES) {
            apps.filter { !FirewallRules.rule(ctx, it.first.packageName).isDefault }
        } else apps
        Column(Modifier.fillMaxSize().background(colorResource(R.color.fw_bg)).padding(20.dp)) {
          if (showHeader) {
            Text("Firewall", color = p.textPrimary, fontSize = 20.sp)
            Text(
                if (s == null) "" else
                    "${FirewallInfo.fmtState(s)} · ${s.blockedCount} blocked · ${s.transport}" +
                        (if (s.systemVpnActive) " · VPN" else ""),
                color = p.textSecondary, fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))

            // Master enable row.
            KitSwitchRow("Firewall enabled", "", s?.enabled ?: false, onCheckedChange = { onMasterToggle(it) })

            Text(
                "Per-app rules apply while the firewall is on. Enabling takes " +
                    "the device VPN slot, so the WireGuard tunnel can't run at the " +
                    "same time (the staged firestack merge unifies them). The fleet's " +
                    "${FirewallVpnService.FLEET_ENGINES.size} engine APKs are never blocked: they " +
                    "carry no screen and work for the app that binds them. The notification names " +
                    "every app the firewall is blocking right now.",
                color = p.textSecondary.copy(alpha = 0.6f), fontSize = 11.sp,
                modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
          }
          if (showList) {
            if (listed.isEmpty()) {
                Text("No app has a custom rule yet: every app is allowed. Open Apps to set one.",
                    color = p.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(listed, key = { it.first.packageName }) { (app, label) ->
                    val summary = remember(ruleVersion, app.packageName) {
                        summarize(FirewallRules.rule(ctx, app.packageName))
                    }
                    Column(Modifier.fillMaxWidth().clickable { editing = app }.padding(vertical = 8.dp)) {
                        Text(label, color = p.textPrimary, fontSize = 14.sp)
                        Text(summary,
                            color = p.textSecondary, fontSize = 11.sp)
                    }
                }
            }
          }
        }

        editing?.let { app -> RuleEditor(app, apps.firstOrNull { it.first == app }?.second ?: app.packageName) }
        if (usagePrompt) {
            KitConfirmDialog(
                title = "Permissions needed",
                text = "\"No background data\" needs Usage access to detect which app is in the foreground.",
                confirmLabel = com.diegonmarcos.superapp.perms.PermsBanner.BUTTON, dismissLabel = "Later",
                onConfirm = {
                    usagePrompt = false
                    com.diegonmarcos.superapp.perms.PermsLink.open(requireContext(), FirewallConditions.PERMS_FEATURE, listOf("usage_access"))
                },
                onDismiss = { usagePrompt = false },
            )
        }
    }

    /** Compact human summary of an [AppRule] for the list row. */
    private fun summarize(r: AppRule): String {
        if (r.isDefault) return "Allowed"
        val parts = mutableListOf<String>()
        when (r.vpnMode) {
            VpnMode.WG0_ONLY -> parts += "wg0 VPN only"
            VpnMode.WG_PUBLIC_ONLY -> parts += "wg-public VPN only"
            VpnMode.NONE -> { // transport toggles only matter without the override
                if (!r.wifi) parts += "No Wi-Fi"
                if (!r.cellular) parts += "No cellular"
            }
        }
        if (!r.background) parts += "No background"
        when (r.direction) {
            Direction.ALL -> parts += "Block all"
            Direction.IN -> parts += "Block incoming"
            Direction.OUT -> parts += "Block outgoing"
            Direction.NONE -> {}
        }
        return parts.joinToString(" · ")
    }

    /**
     * Per-app rule editor — the parallel axes as independent controls:
     * data on Wi-Fi / Cellular / Cloud-VPN, background data, and a direction
     * selector. All combine (blocked if any axis blocks).
     */
    @Composable
    private fun RuleEditor(app: ApplicationInfo, label: String) {
        val ctx = LocalContext.current
        val p = LocalKitPalette.current
        val r = remember(app.packageName) { FirewallRules.rule(ctx, app.packageName) }
        var wifi by remember(app.packageName) { mutableStateOf(r.wifi) }
        var cell by remember(app.packageName) { mutableStateOf(r.cellular) }
        var bg by remember(app.packageName) { mutableStateOf(r.background) }
        var vpnMode by remember(app.packageName) { mutableStateOf(r.vpnMode) }
        var direction by remember(app.packageName) { mutableStateOf(r.direction) }
        val vpnModes = listOf(
            VpnMode.NONE to "None",
            VpnMode.WG0_ONLY to "wg0 VPN only",
            VpnMode.WG_PUBLIC_ONLY to "wg-public VPN only",
        )
        val dirs = listOf(
            Direction.NONE to "None", Direction.ALL to "Block all",
            Direction.IN to "Block incoming", Direction.OUT to "Block outgoing",
        )
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(label) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Data axes — turn OFF to block that path", color = p.textSecondary, fontSize = 11.sp,
                        modifier = Modifier.padding(bottom = 4.dp))
                    KitSwitchRow("Wi-Fi data", "", wifi, onCheckedChange = { wifi = it })
                    KitSwitchRow("Cellular data", "", cell, onCheckedChange = { cell = it })
                    KitSwitchRow("Background data", "", bg, onCheckedChange = { bg = it })

                    Text("Cloud VPN — a strong override: forces the app VPN-only " +
                        "(ignores Wi-Fi/Cellular; app talks ONLY through the tunnel)",
                        color = p.textSecondary, fontSize = 11.sp,
                        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                    vpnModes.forEach { (m, l) -> RadioRow(l, vpnMode == m) { vpnMode = m } }

                    Text("Direction", color = p.textSecondary, fontSize = 11.sp,
                        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                    dirs.forEach { (d, l) -> RadioRow(l, direction == d) { direction = d } }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val rule = AppRule(wifi, cell, bg, vpnMode, direction)
                    FirewallRules.setRule(ctx, app.packageName, rule)
                    FirewallController.refresh(ctx)
                    if (!rule.background && !FirewallConditions.hasUsageAccess(ctx)) usagePrompt = true
                    editing = null; ruleVersion++; refreshHeader()
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }

    @Composable
    private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
        Row(Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null)
            Text(label, color = LocalKitPalette.current.textPrimary, fontSize = 15.sp,
                modifier = Modifier.padding(start = 12.dp))
        }
    }

    private fun onMasterToggle(enable: Boolean) {
        val ctx = requireContext()
        if (enable) {
            val consent = FirewallController.consentIntent(ctx)
            if (consent != null) vpnConsent.launch(consent) else FirewallController.start(ctx)
        } else {
            FirewallController.stop(ctx)
        }
        refreshHeader()
    }

    private fun refreshHeader() {
        val ctx = context ?: return
        info = FirewallInfo.read(ctx)
    }

    companion object {
        const val TAG = "FirewallDialog"
        /** #1719 which view of the screen to draw (argument [ARG_SECTION]); absent = [SECTION_ALL]. */
        const val ARG_SECTION = "section"
        const val SECTION_ALL = "all"
        const val SECTION_STATUS = "status"
        const val SECTION_APPS = "apps"
        const val SECTION_RULES = "rules"
        fun newInstance(section: String): FirewallDialog = FirewallDialog().apply {
            arguments = Bundle().apply { putString(ARG_SECTION, section) }
        }
    }
}
