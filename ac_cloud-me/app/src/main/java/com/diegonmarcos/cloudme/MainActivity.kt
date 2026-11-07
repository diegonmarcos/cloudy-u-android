package com.diegonmarcos.cloudme

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import com.diegonmarcos.superapp.themebg.themeBackground
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import com.diegonmarcos.superapp.health.HealthConnectGateway
import com.diegonmarcos.superapp.perms.PermAskLog
import com.diegonmarcos.superapp.perms.Perms
import com.diegonmarcos.superapp.perms.PermsSettings
import kotlinx.coroutines.launch
import com.diegonmarcos.superapp.bottomnav.FleetChrome
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitFragmentHostState
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.updater.UpdateProgress
import com.diegonmarcos.superapp.updater.Updater

/** Resolves a tile/link target the way [MainActivity.onTarget] does; provided by the shell to every page. */
val LocalMeTarget = staticCompositionLocalOf<(String) -> Unit> { {} }

/**
 * Single-activity shell: a top bar with the Profile and Configs entries, a
 * content host and the fleet's bottom-nav island — the SuperApp's shape, at a fraction of its
 * size, because Cloud Me has no launcher, no home index and no modes.
 *
 * Every menu is built from [Sections] rather than inflated from res/menu. That is
 * the point of the app: the bar, the Settings entries and the tab strips are one
 * JSON list rendered three ways, so they cannot disagree.
 *
 * 2026-10-16 owner decision: all apps must be Compose. The shell is a Compose screen under
 * CloudKitTheme (libs:ui-kit): [MeTopBar] over [MeBody] (content above the island), and
 * [SectionScreen] draws the section. Only the Health and Fin library pages are still
 * Fragments, hosted by KitFragmentHost.
 */
class MainActivity : AppCompatActivity() {

    /** Section currently on screen — the back handler's only state. */
    private var currentSection by mutableStateOf<String?>(null)

    /** The page a caller asked for inside [currentSection] (null = the section's first). */
    private var currentPage by mutableStateOf<String?>(null)

    /** Bumped on every open(), so reopening a section starts it afresh, as a new fragment did. */
    private var openSeq by mutableIntStateOf(0)

    /** Bumped when a permission answer may have changed (a dialog answered, or the app settings left). */
    private var permTick by mutableIntStateOf(0)

    /**
     * The ONE permission launcher, registered with the activity (not in a composable, which would register a new
     * key per composition and bloat the saved state). The Configs rows and the start-up prompt both go through it.
     */
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { answers ->
        PermAskLog.markAsked(this, answers.keys)
        permTick++
        walkNext()
    }

    /** Health Connect's own request screen (the Health rows, and the Health step of a walk). */
    private val healthRequest = registerForActivityResult(HealthConnectGateway.requestPermissionsContract()) {
        MePerms.noteHealthAsked(this)
        permTick++
        walkNext()
    }

    /** The steps of the walk in progress (start-up, Grant all, or one row); a settings step resumes it in onResume. */
    private val walk = ArrayDeque<PermStep>()
    private var awaitingSettings = false

    private fun walkStart(steps: List<PermStep>) {
        walk.clear(); walk.addAll(steps); awaitingSettings = false
        walkNext()
    }

    private fun walkNext() {
        val step = walk.removeFirstOrNull() ?: return
        when (step) {
            is PermStep.Runtime -> permissionRequest.launch(step.perms.toTypedArray())
            is PermStep.Health -> runCatching { healthRequest.launch(step.perms) }.onFailure { walkNext() }
            is PermStep.Settings -> {
                MePerms.noteSpecialOpened(this, step.id)
                val entry = Perms.catalogue(this).byId(step.id)
                if (entry != null && PermsSettings.open(this, entry)) awaitingSettings = true else walkNext()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Health and Fin are re-added by KitFragmentHost once Compose has a container for them.
        super.onCreate(KitFragmentHostState.withoutFragments(savedInstanceState))
        FleetChrome.apply(this)
        CloudMeApiData.sync(this) // cloud-me-api first (background); baked assets are the fallback

        if (savedInstanceState == null) {
            Sections.default()?.let { open(it.id) }
            // A caller that knows where it wants to land overrides that. Both
            // land in the same frame, so the default is never drawn.
            handleShortcutIntent(intent)
        } else {
            currentSection = savedInstanceState.getString(STATE_SECTION)?.let { LegacyRoutes.resolve(it, null).first }
        }

        setContent {
            val palette = KitPalette(
                surface = colorResource(R.color.me_surface),
                surfaceSelected = colorResource(R.color.me_surface_high),
                textPrimary = colorResource(R.color.me_text),
                textSecondary = colorResource(R.color.me_text_dim),
                accent = colorResource(R.color.me_primary),
                hairline = colorResource(R.color.me_outline),
                tileInk = colorResource(R.color.me_on_primary),
            )
            CloudKitTheme(palette) {
                val hooks = MePermissions(run = { walkStart(it) }, tick = permTick)
                // #1323 the Lnktree pages' WebViews and scroll states, alive as long as this activity's composition (build.json::ui.lnktree_cache).
                val lnktree = remember { LnktreeCacheConfig.baked.takeIf { it.enabled }?.let { LnktreeHolder(it.holderMaxWebViews) } }
                androidx.compose.runtime.DisposableEffect(lnktree) { onDispose { lnktree?.destroy() } }
                CompositionLocalProvider(
                    LocalMeTarget provides { onTarget(it) }, LocalMePermissions provides hooks, LocalLnktreeHolder provides lnktree,
                ) { Shell() }
            }
        }

        // On open: ask for what is missing (MePermPlan.startup: first launch, and whenever a needed one is missing,
        // never again at start for one denied for good). The banner and Configs > Perms > Grant all cover the rest.
        if (savedInstanceState == null) {
            lifecycleScope.launch {
                val (hcOk, hcGranted) = MePerms.health(this@MainActivity)
                walkStart(MePermPlan.startup(MePerms.rows(this@MainActivity, this@MainActivity, hcOk, hcGranted), MePerms.memory(this@MainActivity)))
            }
        }

        // Self-update from GHCR: the periodic check plus a one-shot shortly
        // after launch, because periodic alone defers the first pass by a full
        // interval on a fresh install.
        Updater.start(this)
        // #1323 pages with no valid cache entry are downloaded in the background (WorkManager, off the UI thread).
        LnktreePrefetchScheduler.schedule(this)
        UpdateProgress.setListener { state -> runOnUiThread { handleUpdateState(state) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShortcutIntent(intent)
    }

    /**
     * The one way INTO a specific page from outside the app.
     *
     * The SuperApp's Dashboard icons for Fin and Health are `extapp:cloud-me`
     * tiles that no longer have a page of their own to open, so they launch us
     * with the destination attached. The extra is `shortcut_action` carrying a
     * target string — the SAME name and the SAME grammar the SuperApp already
     * accepts on its own launcher intent (ShellActivity.handleShortcutIntent),
     * because a second convention for "open at this page" would be one more
     * thing for the two apps to disagree about.
     *
     * Consumed once: the extra is removed so a later resume of the same task
     * does not re-navigate away from wherever the user has since gone.
     */
    private fun handleShortcutIntent(intent: Intent?) {
        val target = intent?.getStringExtra(EXTRA_SHORTCUT_ACTION)?.takeIf { it.isNotBlank() }
        intent?.removeExtra(EXTRA_SHORTCUT_ACTION)
        target?.let { onTarget(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SECTION, currentSection)
    }

    override fun onResume() {
        super.onResume()
        permTick++ // back from the app settings: re-read what is granted
        if (awaitingSettings) { awaitingSettings = false; walkNext() }
    }

    override fun onDestroy() {
        super.onDestroy()
        UpdateProgress.setListener(null)
    }

    // ── the screen ───────────────────────────────────────────────────

    @Composable
    private fun Shell() {
        val home = Sections.default()?.id
        // Back from anywhere else lands on the first bar section before it leaves the app — the
        // same one-step-home rule the SuperApp uses, so Back never exits from deep inside.
        BackHandler(enabled = currentSection != null && currentSection != home && home != null) {
            home?.let { open(it) }
        }
        // The left menu opens by tapping the hamburger only; there is no edge-swipe handler anywhere in this
        // shell (ticket #1144: a small downward scroll on Profile used to open it). The page owns every touch.
        val health by produceState<Pair<Boolean, Set<String>?>>(false to null, permTick) { value = MePerms.health(this@MainActivity) }
        val rows = remember(permTick, health) { MePerms.rows(this, this, health.first, health.second) }
        val missing = MePermPlan.banner(rows)
        MeShell(
            title = Sections.byId(currentSection)?.label ?: stringResource(R.string.app_name),
            selectedSectionId = currentSection,
            onOpen = { open(it) },
            onTarget = { onTarget(it) },
            currentPageId = currentPage,
            onOpenPage = { section, page -> open(section, page) },
            modifier = Modifier.fillMaxSize().themeBackground(dark = true)
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
            banner = { PermissionBanner(missing) { walkStart(MePermPlan.grantAll(rows)) } },
        ) {
            currentSection?.let { id -> key(id, openSeq) { SectionScreen(id, currentPage) } }
        }
    }

    // ── navigation ───────────────────────────────────────────────────

    /** The single entry point for showing a section. Everything — bar, Settings
     *  entries, tile targets, restored state — comes through here. */
    fun open(sectionId: String, pageId: String? = null) {
        // An id of the retired layout (Work, House, Profile > Tasks, Buro's old pages) goes to where it moved.
        val (toSection, toPage) = LegacyRoutes.resolve(sectionId, pageId)
        val section = Sections.byId(toSection) ?: return
        currentSection = section.id
        currentPage = toPage
        openSeq++
    }

    /**
     * Resolves one tile/link target. Three grammars, matching the SuperApp's:
     * `page:<section>/<page>` inside this app, `extapp:<id>` to another
     * constellation member, anything starting http(s) to the browser.
     */
    fun onTarget(target: String) {
        when {
            target.startsWith("page:") -> {
                val path = target.removePrefix("page:")
                open(path.substringBefore('/'), path.substringAfter('/', "").ifBlank { null })
            }
            target.startsWith("section:") -> open(target.removePrefix("section:"))
            target.startsWith("extapp:") -> ExternalApps.launch(this, target)
            target.startsWith("http") -> runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
            }
            else -> Unit
        }
    }

    // ── self-update overlay ──────────────────────────────────────────

    private fun handleUpdateState(state: UpdateProgress.State) {
        val tag = "update_overlay"
        val frag = supportFragmentManager.findFragmentByTag(tag)
        // An unattended pass, or a manual one the user minimized, draws
        // nothing. Failed states are never suppressed.
        if (UpdateProgress.suppressed(this, state)) {
            frag?.let { supportFragmentManager.commit(allowStateLoss = true) { remove(it) } }
            return
        }
        when (state) {
            is UpdateProgress.State.Idle -> frag?.let { supportFragmentManager.commit { remove(it) } }
            else -> if (frag == null) {
                supportFragmentManager.commit {
                    add(android.R.id.content,
                        com.diegonmarcos.superapp.updater.UpdateOverlayFragment.newInstance(), tag)
                }
            } else {
                (frag as? com.diegonmarcos.superapp.updater.UpdateOverlayFragment)?.applyState(state)
            }
        }
    }

    private companion object {
        const val STATE_SECTION = "section"

        /** Cross-app "open at this target" extra. The name is the SuperApp's —
         *  it is the sending side and already reads this exact extra on its own
         *  launcher intent, so the constellation has one convention, not two. */
        const val EXTRA_SHORTCUT_ACTION = "shortcut_action"
    }
}
