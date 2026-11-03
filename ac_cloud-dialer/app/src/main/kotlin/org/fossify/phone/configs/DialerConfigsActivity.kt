package org.fossify.phone.configs

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.superapp.fleetconfigs.FleetAboutPage
import com.diegonmarcos.superapp.fleetconfigs.FleetConfigs
import com.diegonmarcos.superapp.fleetconfigs.FleetConfigsTags
import com.diegonmarcos.superapp.fleetconfigs.FleetGeneralPage
import com.diegonmarcos.superapp.navbartop2.NavBarTopSecondRow
import com.diegonmarcos.superapp.perms.AppPermsSection
import com.diegonmarcos.superapp.perms.PermsStyle
import com.diegonmarcos.superapp.themebg.ThemeBackground
import kotlinx.coroutines.delay
import org.fossify.phone.BuildConfig
import org.fossify.phone.activities.SettingsActivity
import org.fossify.phone.sim.SimCardsPage
import org.fossify.phone.sim.SimStatusPage
import org.fossify.phone.sim.SimUsagePage

/**
 * #1415 THE Configs of Cloud Dialer, the only one: the top bar's Configs icon opens it (MainActivity's onConfigs), nothing
 * else does. Its pages are tabs in the top nav bar (libs:ui-nav-bars NavBarTopSecondRow, as in every app), declared in
 * build.json::configs_pages.top_nav and baked as BuildConfig.CONFIGS_TOP_NAV: General | Perms | SIM | Status | Usage | About.
 * General and About are the shared Configs v2 pages, Perms is libs:perms' AppPermsSection, SIM / Status / Usage are
 * org.fossify.phone.sim's pages on the shared rows and tables. Back (the arrow or the system back) closes it.
 */
class DialerConfigsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val start = intent.getStringExtra(EXTRA_PAGE)
        setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                DialerConfigsScreen(dark, start, onBack = { finish() }, onAppSettings = { startActivity(Intent(this, SettingsActivity::class.java)) })
            }
        }
    }

    companion object {
        const val EXTRA_PAGE = "dialer_configs_page"
        const val TAG_SCREEN = "dialer_configs"
        const val TAG_BACK = "dialer_configs_back"

        fun intent(context: Context, page: String? = null): Intent =
            Intent(context, DialerConfigsActivity::class.java).apply { if (page != null) putExtra(EXTRA_PAGE, page) }
    }
}

/** build.json::configs_pages.top_nav, as baked: "id:Label:icon,...". */
object DialerConfigsPages {
    val ORDER: List<String> = listOf("general", "perms", "about", "sim", "status", "usage")

    fun parse(baked: String): List<NavPage> = baked.split(',').mapNotNull { e ->
        val p = e.split(':')
        if (p.size == 3 && p[0].isNotBlank()) NavPage(p[0], p[1], p[2]) else null
    }

    val pages: List<NavPage> by lazy { parse(BuildConfig.CONFIGS_TOP_NAV) }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DialerConfigsScreen(dark: Boolean, start: String?, onBack: () -> Unit, onAppSettings: () -> Unit) {
    val c = ThemeBackground.colors(dark)
    val pages = DialerConfigsPages.pages
    var selected by rememberSaveable { mutableStateOf(pages.firstOrNull { it.id == start }?.id ?: pages.first().id) }
    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(c.top, c.bottom)))
            .semantics { testTagsAsResourceId = true }.testTag(DialerConfigsActivity.TAG_SCREEN)
            .statusBarsPadding().navigationBarsPadding(),
    ) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onBack)
                    .semantics { contentDescription = "Back" }.testTag(DialerConfigsActivity.TAG_BACK),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = c.ink) }
            Text(
                "Configs ▸ " + (pages.firstOrNull { it.id == selected }?.label ?: ""),
                Modifier.padding(start = 4.dp).semantics { heading() },
                color = c.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
            )
        }
        NavBarTopSecondRow(
            pages = pages, selectedId = selected, onSelect = { selected = it.id }, dark = dark,
            underTopChrome = false, compact = false, hidden = false,
        )
        // The shared page tags (fleetcfg_general / fleetcfg_about) on those two tabs: the runtime nav walk checks them there.
        val pageTag = when (selected) { "general" -> FleetConfigsTags.GENERAL; "about" -> FleetConfigsTags.ABOUT; else -> "dialer_cfg_$selected" }
        Box(Modifier.fillMaxSize().testTag(pageTag)) {
            when (selected) {
                "general" -> FleetGeneralPage(FleetConfigs.current, dark, c, onAppConfigs = onAppSettings)
                "perms" -> PermsPage(c)
                "sim" -> SimCardsPage(c)
                "status" -> SimStatusPage(c)
                "usage" -> SimUsagePage(c)
                // About scrolls itself.
                "about" -> FleetAboutPage(FleetConfigs.current, c)
            }
        }
    }
}

@Composable
private fun PermsPage(c: com.diegonmarcos.superapp.themebg.ThemeColors) {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1500); tick++ } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).testTag("dialer_cfg_perms")) {
        AppPermsSection(tick, PermsStyle(ink = c.ink, dim = c.inkDim, accent = c.accent))
    }
}
