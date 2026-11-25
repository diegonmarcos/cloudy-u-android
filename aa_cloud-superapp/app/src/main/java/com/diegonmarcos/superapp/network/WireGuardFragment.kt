package com.diegonmarcos.superapp.network

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import com.diegonmarcos.superapp.ShellActivity
import com.diegonmarcos.superapp.network.mesh.AndroidMeshPort
import com.diegonmarcos.superapp.network.mesh.MeshDecl
import com.diegonmarcos.superapp.network.mesh.MeshHost
import com.diegonmarcos.superapp.network.mesh.MeshScreen
import com.diegonmarcos.superapp.network.mesh.MeshStore
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.wireguard.config.Config
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Configs ▸ Cloud Mesh (#877) - the tunnel's one page, in Compose: a tab strip (libs:bottomnav's PageTabs)
 * over Status · Profile · Logs (#1074; the sections Peers, Routes, Profiles, Controls, Log live inside them), declared in `build.json::ui.mesh_page` and
 * drawn by network/mesh/. It replaces the View form that lived here; the routes to it did not move:
 * `page:config/wg`, `page:wg/config` and `section:wg` open the page on its declared first tab, and the
 * hidden `wg/status` page opens the SAME fragment on the Status tab ([newInstance] `page`).
 *
 * This class is only the Android HOST of the page: it owns the activity-result launchers (the .conf
 * import and export, the profile-folder export, the VPN consent) and the clipboard, which a composable
 * cannot open itself, and hands them to [MeshStore] as a [MeshHost]. Every control acts through the
 * existing engine paths via [AndroidMeshPort]; there is no second tunnel path here.
 */
class WireGuardFragment : KitComposeFragment() {

    private val prefs: WireGuardPrefs get() = WgState.prefs(requireContext())

    private val store: MeshStore by lazy {
        TunnelBinder.install(requireContext())   // #1900 probes take their network (tunnel / outside) from the binder
        MeshStore(AndroidMeshPort(requireContext()), MeshDecl.baked, host,
            journalFile = com.diegonmarcos.superapp.network.mesh.MeshEventLog.file(requireContext()),
            autoProbe = true).also { s ->
            arguments?.getString(ARG_PAGE)?.let { a -> s.open(a) }   // #1928 a tab, a subpage, a former page id or a section
        }
    }

    override fun palette() = LauncherPalette.kit(requireContext())

    @Composable
    override fun Content() = MeshScreen(store)

    /** Tell the page what happened outside a composable (a picker's result): the notice line and the journal. */
    private fun report(label: String, block: () -> String) = store.perform(label, block = block)

    /** Read an imported .conf into prefs (multi-peer aware). */
    private val importLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri ?: return@registerForActivityResult
            val resolver = requireContext().contentResolver
            report("import .conf") {
                val cfg = resolver.openInputStream(uri)?.use { Config.parse(BufferedReader(InputStreamReader(it))) }
                    ?: error("could not open the file")
                prefs.hydrateFromConfig(cfg)
                "imported ${cfg.peers.size} peer(s) - reconnect to apply"
            }
        }

    /** Save current state to a user-chosen .conf via Storage Access Framework. */
    private val exportLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            uri ?: return@registerForActivityResult
            val resolver = requireContext().contentResolver
            report("export .conf") {
                val cfg = prefs.toWgConfig()
                resolver.openOutputStream(uri)?.use { out -> out.write(cfg.toWgQuickString().toByteArray()) }
                    ?: error("could not write the file")
                "exported (this file carries the private key - keep it)"
            }
        }

    /** First-ever Connect needs Android's VPN-consent dialog, asked by the engine's consent activity. */
    private var afterConsent: (() -> Unit)? = null
    private val vpnConsentLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val then = afterConsent
            afterConsent = null
            if (result.resultCode == Activity.RESULT_OK) then?.invoke()
            else report("connect") { prefs.tunnelEnabled = false; error("VPN consent denied") }
        }

    /** #1153 Export all: the text waits here while the owner picks where the file goes. */
    private var pendingExport: String = ""
    private val exportAllLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            uri ?: return@registerForActivityResult
            val text = pendingExport; pendingExport = ""
            val resolver = requireContext().contentResolver
            report("export status") {
                resolver.openOutputStream(uri)?.use { out -> out.write(text.toByteArray()) } ?: error("could not write the file")
                "saved the whole status (no private keys)"
            }
        }

    /** #1845 Export to Md: the same hand-over with a Markdown mime type, so the file keeps its .md name. */
    private var pendingMarkdown: String = ""
    private val exportMdLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
            uri ?: return@registerForActivityResult
            val text = pendingMarkdown; pendingMarkdown = ""
            val resolver = requireContext().contentResolver
            report("export status (Markdown)") {
                resolver.openOutputStream(uri)?.use { out -> out.write(text.toByteArray()) } ?: error("could not write the file")
                "saved the whole status as Markdown (no private keys)"
            }
        }

    private val host = object : MeshHost {
        override fun exportText(name: String, text: String) { pendingExport = text; exportAllLauncher.launch(name) }
        override fun exportMarkdown(name: String, text: String) { pendingMarkdown = text; exportMdLauncher.launch(name) }
        override fun requestConsent(intent: Intent, then: () -> Unit) { afterConsent = then; vpnConsentLauncher.launch(intent) }

        override fun copy(label: String, text: String) {
            requireContext().getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
        }

        override fun paste(): String = requireContext().getSystemService(ClipboardManager::class.java)
            .primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(requireContext())?.toString().orEmpty()

        override fun openVpnSettings() = startActivity(Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        override fun openAccount() { (activity as? ShellActivity)?.nav?.openSectionPage("config", "profile") }
        override fun openConfigsDns() { (activity as? ShellActivity)?.nav?.openSectionPage("config", "dns") }
        /** A Custom Tab by its intent contract (the extra below), without the androidx.browser dependency. */
        override fun openCustomTab(url: String) {
            val i = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            i.putExtras(android.os.Bundle().apply { putBinder("android.support.customtabs.extra.SESSION", null) })
            startActivity(i)
        }
        override fun importConf() = importLauncher.launch("*/*")
        override fun exportConf() = exportLauncher.launch("${prefs.tunnelName.ifBlank { "wg" }}.conf")
        override fun exportProfiles() {
            if (WireGuardProfiles.all.isEmpty()) report("export profiles") { error("this build carries no profiles") }
            else profileFolderPicker.launch(null)
        }
    }

    // ── export · the declared profiles ───────────────────────────────────

    /**
     * Write the profiles into a folder the USER picks.
     *
     * A folder rather than several save dialogs, and SAF rather than a path: the
     * app writes only where it has just been handed permission, and nothing at
     * all until then.
     */
    private val profileFolderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
            tree ?: return@registerForActivityResult
            report("export profiles") { exportProfilesTo(tree) }
        }

    private fun exportProfilesTo(tree: android.net.Uri): String {
        val resolver = requireContext().contentResolver
        // Platform SAF, not androidx.documentfile — that artifact is not a
        // dependency of this module and one export is not worth adding one.
        val dirUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(
            tree, android.provider.DocumentsContract.getTreeDocumentId(tree))
        val written = mutableListOf<String>()
        val failed = mutableListOf<String>()
        WireGuardProfiles.all.forEach { profile ->
            val ok = runCatching {
                deleteExisting(dirUri, profile.fileName)
                val fileUri = android.provider.DocumentsContract.createDocument(
                    resolver, dirUri, "text/plain", profile.fileName,
                ) ?: error("could not create ${profile.fileName}")
                resolver.openOutputStream(fileUri)?.use {
                    it.write(WireGuardProfiles.render(profile).toByteArray())
                } ?: error("could not write ${profile.fileName}")
            }.isSuccess
            if (ok) written += profile.fileName else failed += profile.fileName
        }
        // Reported per file. "Exported" over a partial write is how a missing
        // profile gets discovered on the train instead of here.
        check(failed.isEmpty()) { "Exported ${written.size}, FAILED ${failed.size}: ${failed.joinToString()}" }
        return "Exported ${written.size} profiles — no private key included"
    }

    /**
     * Drop a same-named file before writing.
     *
     * SAF's `createDocument` RENAMES on collision rather than replacing, so a
     * re-export after the hub moves would leave "config-v4-split (1).conf"
     * next to the stale file the user already imported — and the stale one
     * keeps the name they would reach for. Best-effort.
     */
    private fun deleteExisting(dirUri: android.net.Uri, name: String) {
        val resolver = requireContext().contentResolver
        runCatching {
            val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(
                dirUri, android.provider.DocumentsContract.getDocumentId(dirUri))
            resolver.query(
                children,
                arrayOf(
                    android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                ),
                null, null, null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getString(1) != name) continue
                    android.provider.DocumentsContract.deleteDocument(
                        resolver,
                        android.provider.DocumentsContract.buildDocumentUriUsingTree(
                            dirUri, cursor.getString(0)),
                    )
                }
            }
        }
    }

    companion object {
        /** The tab to open on: one of `ui.mesh_page.pages[*].id`; absent = the declared default. */
        const val ARG_PAGE = "page"

        fun newInstance(page: String? = null): WireGuardFragment = WireGuardFragment().apply {
            if (page != null) arguments = Bundle().apply { putString(ARG_PAGE, page) }
        }
    }
}
