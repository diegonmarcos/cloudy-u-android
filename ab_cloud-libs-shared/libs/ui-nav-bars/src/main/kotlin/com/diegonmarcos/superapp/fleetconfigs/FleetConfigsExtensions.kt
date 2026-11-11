package com.diegonmarcos.superapp.fleetconfigs

import androidx.compose.runtime.Composable
import java.util.concurrent.CopyOnWriteArrayList

/**
 * #1443 Opt-in Configs tabs beyond the three the lib draws for every app (General | Perms | About).
 *
 * The shared Configs screen draws General | Perms | About from [FleetConfigsPage] for EVERY app that reaches it, with no
 * per-app wiring. Two further tabs — Logcat and X-Ray — are not wanted in every app's APK (they pull a log reader and an
 * on-device profiler), so they are NOT entries of [FleetConfigsPage]; an app opts in by linking the shared lib that carries
 * the tab (libs:fleetconfigs-logcat, libs:fleetconfigs-xray). Each such lib registers its tab here from a ContentProvider at
 * process start, exactly as libs:ui-nav-bars publishes its launcher shortcuts, so the app writes nothing in Kotlin.
 *
 * An opted-in tab is drawn on the shared Configs strip BETWEEN Perms and About, giving the full order
 * General | Perms | <opted-in tabs, in registration order> | About. An app that links neither lib shows the three base tabs
 * unchanged, so this never changes what any other app ships — which is why the per-app guard (configs-pages.json::tabs.first)
 * stays General | Perms | About and the fleet is untouched.
 */
public data class FleetConfigsTab(
    /** Stable tab id, distinct from every [FleetConfigsPage] id (e.g. "logcat", "xray"). */
    val id: String,
    /** Strip label. */
    val label: String,
    /** libs:ui-icons / libs:bottomnav icon key (e.g. "code", "bug"). */
    val icon: String,
    /** The page drawn when the tab is selected; [dark] is the current theme. */
    val body: @Composable (dark: Boolean) -> Unit,
)

/** The registry of opted-in Configs tabs. Thread-safe: libs register off the main thread from their init providers. */
public object FleetConfigsExtensions {
    private val tabs = CopyOnWriteArrayList<FleetConfigsTab>()

    /** Register [tab], or replace a tab already registered under the same id (idempotent across repeated process inits). */
    public fun register(tab: FleetConfigsTab) {
        if (FleetConfigsPage.entries.any { it.id == tab.id }) return  // never shadow a base page
        tabs.removeAll { it.id == tab.id }
        tabs.add(tab)
    }

    /** The opted-in tabs, in registration order (the order they are drawn between Perms and About). */
    public fun all(): List<FleetConfigsTab> = tabs.toList()

    /** The opted-in tab with [id], or null. */
    public fun byId(id: String): FleetConfigsTab? = tabs.firstOrNull { it.id == id }

    /** #1630 The fleet-default tab ids, in the order they sit AFTER the `|`: Agents, Perms, Logcat, X-Ray App, About. */
    public val TAIL_IDS: List<String> = listOf("agents", "perms", "logcat", "xray", "about")

    public const val AGENTS_ID: String = "agents"
    public const val XRAY_ID: String = "xray"

    @Volatile private var customXray: (@Composable (dark: Boolean) -> Unit)? = null

    /**
     * #1630 CUSTOM X-RAY HOOK: an app supplies its OWN page for the "X-Ray App" tab instead of the generic profiler (e.g. Hardtech CAD
     * and CG Omniverse draw render diagnostics). Call once from Application.onCreate (or any init). The tab keeps its place, label and
     * icon in the fixed tail; when libs:fleetconfigs-xray is not linked the hook registers the tab itself, so it always shows.
     */
    public fun registerXrayPage(body: @Composable (dark: Boolean) -> Unit) {
        customXray = body
        if (byId(XRAY_ID) == null) register(FleetConfigsTab(XRAY_ID, "X-Ray App", "insights") { })
    }

    /** Draws [tab]'s page; the X-Ray tab draws the app's custom page when [registerXrayPage] was called. */
    @Composable
    public fun Body(tab: FleetConfigsTab, dark: Boolean) {
        val custom = if (tab.id == XRAY_ID) customXray else null
        if (custom != null) custom(dark) else tab.body(dark)
    }

    /**
     * #1630 THE order of the Configs strip: [General + the app's own tabs] | Agents, Perms, Logcat, X-Ray App, About. A tail tab that is
     * not registered (Agents, Logcat, X-Ray App come from opt-in libs) is simply absent; Perms and About are always there. Any other
     * registered tab is an app-own tab and sits before the `|`. The first tail page carries `separatorBefore`, the `|` character.
     */
    public fun stripOrder(extras: List<FleetConfigsTab> = all()): List<com.diegonmarcos.superapp.bottomnav.NavPage> {
        fun page(id: String, label: String, icon: String) = com.diegonmarcos.superapp.bottomnav.NavPage(id, label, icon)
        val own = extras.filter { it.id !in TAIL_IDS }.map { page(it.id, it.label, it.icon) }
        val tail = TAIL_IDS.mapNotNull { id ->
            when (id) {
                "perms" -> page(FleetConfigsPage.Perms.id, FleetConfigsPage.Perms.label, FleetConfigsPage.Perms.icon)
                "about" -> page(FleetConfigsPage.About.id, FleetConfigsPage.About.label, FleetConfigsPage.About.icon)
                else -> extras.firstOrNull { it.id == id }?.let { page(it.id, it.label, it.icon) }
            }
        }
        val head = listOf(page(FleetConfigsPage.General.id, FleetConfigsPage.General.label, FleetConfigsPage.General.icon)) + own
        return head + tail.mapIndexed { i, p -> if (i == 0) p.copy(separatorBefore = true) else p }
    }

    /** Test seam only: drop every registration. */
    public fun clearForTest() {
        tabs.clear()
        customXray = null
    }
}
