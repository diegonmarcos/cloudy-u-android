package com.diegonmarcos.superapp.apps

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment

/**
 * #865 The Store is its own app now: Cloud Store (ac_cloud-store), built from
 * the same libs:appstore this app hosts.
 *
 * Once Cloud Store is installed, SuperApp hands it two things:
 *  - the unattended fleet pass (periodic check, Wi-Fi trigger, auto-update
 *    chain), through AppStoreHost.runsFleetPass, so the two apps never race
 *    the same downloads and install sessions;
 *  - the Store pages, which become a button that opens Cloud Store on the
 *    matching tab.
 *
 * #894 Until it is installed, SuperApp shows one thing on those pages: an Install
 * Cloud Store button (the same fleet install the Store tile uses). No Store, no
 * Store notification, no recurring alert comes from SuperApp.
 */
object CloudStoreHandoff {
    const val PKG = "com.diegonmarcos.cloudstore"
    private const val ACTION_OPEN = "com.diegonmarcos.cloudstore.OPEN"
    private const val EXTRA_TAB = "tab"
    private const val EXTRA_PAGE = "page"

    /** Store page id (SectionPages) → Cloud Store tab. */
    val TAB_OF_PAGE = mapOf("store-cloud" to "cloud", "store-phone" to "phone", "apps-mesh" to "mesh")

    /** What a route to a Store page does (#894). */
    enum class Route {
        /** Cloud Store is installed and opened on the matching tab: the route is consumed. */
        OPENED_CLOUD_STORE,
        /** Cloud Store is installed but will not start: the page shows the hand-off placeholder. */
        PLACEHOLDER,
        /** Cloud Store is absent (or not a Store page): the embedded Store, as before. */
        EMBEDDED,
    }

    /** The Cloud Store tab for a navigation target, null when it is not a Store page. */
    fun tabFor(sectionId: String, pageId: String): String? =
        if (sectionId == "config") TAB_OF_PAGE[pageId] else null

    /** Pure routing decision; [open] is only called when [installed]. */
    fun decide(installed: Boolean, open: () -> Boolean): Route = when {
        !installed -> Route.EMBEDDED
        open() -> Route.OPENED_CLOUD_STORE
        else -> Route.PLACEHOLDER
    }

    /**
     * #894 Routing layer (LauncherNavController.openSectionPage): every route to
     * a Store page - notification, shortcut, tile - opens Cloud Store directly
     * when it is installed. True when the route was consumed.
     */
    fun intercept(ctx: Context, sectionId: String, pageId: String): Boolean {
        val tab = tabFor(sectionId, pageId) ?: return false
        return decide(installed(ctx)) { open(ctx, tab) } == Route.OPENED_CLOUD_STORE
    }

    /**
     * #894 Cloud Store owns every install and update on this phone once it is installed (owner
     * decision 2026-10-07; it has its own shell channel). SuperApp then runs no fleet pass, no
     * Update-all and no self-update check of its own: it hands the user to Cloud Store instead,
     * and Cloud Store updates SuperApp with the rest of the fleet.
     */
    fun ownsInstalls(ctx: Context): Boolean = installed(ctx)

    fun installed(ctx: Context): Boolean = runCatching {
        ctx.packageManager.getApplicationInfo(PKG, 0).enabled
    }.getOrDefault(false)   // NameNotFoundException = not installed

    /** Opens Cloud Store on [tab]. False when it is not installed or will not start. */
    fun open(ctx: Context, tab: String, page: String? = null): Boolean = runCatching {
        ctx.startActivity(Intent(ACTION_OPEN).setPackage(PKG).putExtra(EXTRA_TAB, tab).apply { if (page != null) putExtra(EXTRA_PAGE, page) }
            // #570 Account ▸ Fleet ▸ Apps ▸ Apply list to Store, when Cloud Store (not the embedded page) is the Store.
            .putExtra(com.diegonmarcos.superapp.appstore.StoreImport.EXTRA_IMPORT, com.diegonmarcos.superapp.appstore.StoreImport.takePending())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    /**
     * The fragment SectionPages puts where a Store page was. It decides when
     * it is shown, not when the page list is built (no Context there): Cloud
     * Store installed -> a button that opens it on the same tab; otherwise the
     * one Install Cloud Store button.
     */
    fun page(pageId: String): Fragment = Page().apply {
        arguments = Bundle().apply { putString(ARG_PAGE, pageId) }
    }

    /**
     * Store page id -> the Store fragment that page used to embed while Cloud Store was
     * not installed. #894 SuperApp no longer shows them (that was the Store, with its
     * notifications, inside SuperApp); the map stays as the page -> shelf record StoreShelvesTest reads.
     */
    val EMBEDDED: Map<String, Class<out Fragment>> = mapOf(
        "store-cloud" to com.diegonmarcos.superapp.appstore.StoreCloudFragment::class.java,
        "store-phone" to com.diegonmarcos.superapp.appstore.StorePhoneFragment::class.java,
        "apps-mesh"   to com.diegonmarcos.superapp.appstore.AppsMeshFragment::class.java,
    )

    /** The Store page id a [Page] was built for. */
    fun pageIdOf(f: Fragment): String? = (f as? Page)?.arguments?.getString(ARG_PAGE)

    private const val ARG_PAGE = "page"

    class Page : Fragment() {
        override fun onCreate(state: Bundle?) {
            super.onCreate(state)
            // A page saved by an older build restores its embedded Store child, which would
            // look for a container the button layout does not have: it goes before any view exists.
            if (childFragmentManager.fragments.isNotEmpty())
                childFragmentManager.beginTransaction()
                    .apply { childFragmentManager.fragments.forEach(::remove) }.commitNow()
        }

        override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup?, state: Bundle?): View {
            val pageId = requireArguments().getString(ARG_PAGE) ?: "store-cloud"
            val tab = TAB_OF_PAGE[pageId] ?: "cloud"
            val installed = installed(requireContext())
            return androidx.compose.ui.platform.ComposeView(requireContext()).apply {
                setContent {
                    com.diegonmarcos.superapp.uikit.KitDenseMaterial(androidx.compose.material3.darkColorScheme()) {
                        androidx.compose.material3.Surface {
                            // #894 The Store is Cloud Store's alone: installed -> a button that opens it;
                            // absent -> the one entry point SuperApp keeps, Install Cloud Store. The Store
                            // pages are no longer embedded here, so SuperApp has no Store notification to post.
                            if (installed) OpenCloudStore(tab) else InstallCloudStore()
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun InstallCloudStore() {
        val ctx = LocalContext.current
        var message by remember { mutableStateOf<String?>(null) }
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "The Store is its own app now: Cloud Store.\nDownloads, updates and install prompts for the whole fleet, SuperApp included, are Cloud Store's.",
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            com.diegonmarcos.superapp.uikit.KitButton(onClick = {
                message = com.diegonmarcos.superapp.launcher.AppInstall.start(ctx, PKG, "Cloud Store").message
            }) { Text("Install Cloud Store") }
            message?.let { Text(it, Modifier.padding(top = 12.dp), textAlign = TextAlign.Center) }
        }
    }

    @Composable
    private fun OpenCloudStore(tab: String) {
        val ctx = LocalContext.current
        var failed by remember { mutableStateOf(false) }
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "The Store is its own app now: Cloud Store.\nDownloads, updates and auto-update run there, SuperApp included.",
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            com.diegonmarcos.superapp.uikit.KitButton(onClick = { failed = !open(ctx, tab) }) { Text("Open Cloud Store") }
            if (failed) Text("Cloud Store did not open - is it still installed?", Modifier.padding(top = 12.dp))
        }
    }
}
