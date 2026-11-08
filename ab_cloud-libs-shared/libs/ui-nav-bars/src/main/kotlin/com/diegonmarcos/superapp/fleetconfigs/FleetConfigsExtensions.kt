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

    /** Test seam only: drop every registration. */
    public fun clearForTest() {
        tabs.clear()
    }
}
