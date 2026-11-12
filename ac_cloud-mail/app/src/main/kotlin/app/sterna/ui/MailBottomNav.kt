package app.sterna.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import app.sterna.R
import com.diegonmarcos.superapp.bottomnav.BottomNavEntry
import com.diegonmarcos.superapp.bottomnav.navEntry
import com.diegonmarcos.superapp.bottomnav.rememberLeaveOnce
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import app.sterna.ui.theme.MailMetrics
import com.diegonmarcos.superapp.navbarbottom.NavBarBottomHost
import com.diegonmarcos.superapp.navbartop1.NavBarTopFirstRowScaffold
import com.diegonmarcos.superapp.navbartop1.rememberSuperAppAiLauncher
import com.diegonmarcos.superapp.navbartop1.titleHierarchy

/**
 * Mail's seven items as the island draws them, from the declaration ([mailNav], build.json::ui): the
 * ids, order and icons are the declared sections'. Labels are the localised accessible names in
 * strings.xml, keyed by section id, so a translated bar stays translated.
 */
@Composable
internal fun mailEntries(): List<BottomNavEntry> = mailNav.bottomSections().map {
    navEntry(it.id, stringResource(itemDescription(it.id)), it.icon)
}

/**
 * cloud-mail's bottom nav (#465): [bottomNavItems] on the shared host (libs:ui-nav-bars, #1154). DESTINATION items navigate
 * and move the selected pill. LAUNCH items hand off to another app and leave the pill where it is.
 */
@Composable
fun BottomNavBar(nav: NavController, currentRoute: String, content: @Composable () -> Unit) {
    val context = LocalContext.current
    // The sanctioned hand-off guard: a double tap while leaving must not fire the launch twice.
    val leaveOnce = rememberLeaveOnce()
    // Resolved here, in the composable's scope, and handed to the tap handler already resolved.
    val missing = bottomNavItems.associateWith { item -> context.getString(missingResource(item.id)) }
    val selected = bottomNavItems.firstOrNull {
        it.action == BottomNavAction.DESTINATION && it.route == currentRoute
    }?.id
    val tap: (BottomNavEntry) -> Unit = { entry ->
        val item = bottomNavItems.first { it.id == entry.id }
        onItemTap(context, nav, item, currentRoute, leaveOnce, missing[item] ?: "")
    }
    val openSection = mailNav.section(selected)
    val account = com.diegonmarcos.superapp.bottomnav.rememberAccountLauncher()
    val ai = rememberSuperAppAiLauncher()
    val entries = mailEntries()
    // #1154 the first row: AI hands over to the SuperApp, Profile is Cloud Account, Configs is Mail's settings.
    // #1697 Configs opens the fleet strip (General | Agents, Perms, Logcat, X-Ray App, About); Mail's own settings (the former target) is General > App.
    var fleetConfigsOpen by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    if (fleetConfigsOpen) com.diegonmarcos.superapp.fleetconfigs.FleetConfigsScreen(com.diegonmarcos.superapp.fleetconfigs.FleetConfigsPage.General, dark = app.sterna.ui.theme.LocalSternaDarkTheme.current, onDismiss = { fleetConfigsOpen = false },
        onAppConfigs = { if (currentRoute != "settings") nav.currentBackStackEntry?.navigateOnce { nav.navigate("settings") } })
    NavBarTopFirstRowScaffold(
        title = stringResource(itemDescription(selected ?: "home")),
        hierarchy = openSection?.let { sec -> titleHierarchy(sec, sec.pages.firstOrNull { it.id == currentRoute }?.id) }.orEmpty(),
        sections = mailNav.sections,  // #1483 every declared section, bar and off-bar
        onNavigate = { e -> entries.firstOrNull { it.id == e.sectionId }?.let(tap) },
        onAccount = account,
        onConfigs = { fleetConfigsOpen = true },
        onAi = ai,
        dark = app.sterna.ui.theme.LocalSternaDarkTheme.current,
        modifier = Modifier.statusBarsPadding(),
    ) {
        NavBarBottomHost(
            entries = entries,
            selectedId = selected,
            onSelect = tap,
            // Mail has a light mode: the shared page background follows it.
            dark = app.sterna.ui.theme.LocalSternaDarkTheme.current,
            content = content,
        )
    }
}

/** One item tapped. A destination navigates, and a launch hands off without moving the selection. */
private fun onItemTap(
    context: android.content.Context,
    nav: NavController,
    item: BottomNavItem,
    currentRoute: String,
    leaveOnce: (() -> Boolean) -> Unit,
    missingMessage: String,
) {
    when (item.action) {
        BottomNavAction.DESTINATION -> {
            val route = item.route ?: return
            // Re-tapping the screen already on top is a no-op, not a second copy on the back stack.
            if (route == currentRoute) return
            // Settled-only, like every other navigation action here: a double tap mid-slide lands once.
            val top = nav.currentBackStackEntry
            top?.navigateOnce { nav.navigate(route) }
        }
        BottomNavAction.LAUNCH -> item.packageName?.let {
            leaveOnce { launchInstalledApp(context, it, missingMessage) }
        }
    }
}

/**
 * Launch [packageName]'s front-door activity, reporting whether it exists on this device. The
 * launch intent is resolved against the device, so a device without the app shows the specific,
 * honest [missingMessage] instead of a bar that pretends.
 */
internal fun launchInstalledApp(
    context: android.content.Context,
    packageName: String,
    missingMessage: String,
): Boolean =
    try {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            Toast.makeText(context, missingMessage, Toast.LENGTH_SHORT).show()
            false
        } else {
            context.startActivity(intent)
            true
        }
    } catch (_: Exception) {
        false
    }

/** The "not installed" sentence a launch item shows when its app is absent. */
internal fun missingResource(id: String): Int = when (id) {
    "chat" -> R.string.nav_cloud_chat_not_installed
    "video" -> R.string.nav_video_not_installed
    "tasks" -> R.string.nav_tasks_not_installed
    "contacts" -> R.string.nav_contacts_not_installed
    else -> R.string.nav_launch_not_installed
}

/** A nav item's label, which is also its accessible name. */
internal fun itemDescription(id: String): Int = when (id) {
    "mail" -> R.string.nav_bar_mail
    "chat" -> R.string.nav_bar_chat
    "home" -> R.string.nav_bar_home
    "rss" -> R.string.nav_bar_rss
    "video" -> R.string.nav_bar_video
    "tasks" -> R.string.nav_bar_tasks
    "contacts" -> R.string.nav_bar_contacts
    else -> R.string.nav_bar_home
}
