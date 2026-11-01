package org.fossify.phone.activities

import com.diegonmarcos.superapp.fleetconfigs.FleetConfigsEntries
import org.fossify.phone.sim.SimConfigsActivity
import android.view.Menu
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.os.Handler
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.viewpager.widget.ViewPager
import com.diegonmarcos.superapp.bottomnav.BottomNavCollapse
import com.diegonmarcos.superapp.bottomnav.navEntry
import com.diegonmarcos.superapp.bottomnav.FleetChrome
import com.diegonmarcos.superapp.bottomnav.LocalNavChromeCollapse
import com.diegonmarcos.superapp.bottomnav.TopBarDrawer
import com.diegonmarcos.superapp.bottomnav.rememberAccountLauncher
import com.diegonmarcos.superapp.navbarbottom.NavBarBottomHost
import com.diegonmarcos.superapp.navbartop1.HierarchyEntry
import com.diegonmarcos.superapp.navbartop1.NavBarTopFirstRowScaffold
import com.diegonmarcos.superapp.navbartop1.rememberSuperAppAiLauncher
import com.google.android.material.snackbar.Snackbar
import org.fossify.commons.dialogs.ChangeViewTypeDialog
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.dialogs.PermissionRequiredDialog
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.*
import org.fossify.commons.models.RadioItem
import org.fossify.commons.models.contacts.Contact
import org.fossify.phone.BuildConfig
import org.fossify.phone.R
import org.fossify.phone.adapters.ViewPagerAdapter
import org.fossify.phone.databinding.ActivityMainBinding
import org.fossify.phone.dialogs.ChangeSortingDialog
import org.fossify.phone.dialogs.FilterContactSourcesDialog
import org.fossify.phone.extensions.clearMissedCalls
import org.fossify.phone.extensions.config
import org.fossify.phone.extensions.handleFullScreenNotificationsPermission
import org.fossify.phone.extensions.launchCreateNewContactIntent
import org.fossify.phone.fragments.ContactsFragment
import org.fossify.phone.fragments.HomeFragment
import org.fossify.phone.fragments.FavoritesFragment
import org.fossify.phone.fragments.MyViewPagerFragment
import org.fossify.phone.fragments.RecentsFragment
import org.fossify.phone.fragments.SmsFragment
import org.fossify.phone.nav.ChromeScrollHooks
import org.fossify.phone.nav.DialerNav
import org.fossify.phone.helpers.HOME_TAB_INDEX
import org.fossify.phone.helpers.OPEN_DIAL_PAD_AT_LAUNCH
import org.fossify.phone.helpers.RecentsHelper
import org.fossify.phone.helpers.visibleDialerTabs
import org.fossify.phone.models.Events
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

/**
 * Cloud Dialer's main screen on the shared nav pattern (#1164): the first row (Profile top-right opens Cloud Account), the
 * bottom nav (sections from build.json::ui), the page background and the scroll-collapse are the shared libs'
 * ([NavBarTopFirstRowScaffold] around [NavBarBottomHost]); the Fossify View pages (search menu, pager, dialpad button) are hosted
 * inside it and their scroll feeds the one collapse connection ([ChromeScrollHooks]).
 */
class MainActivity : SimpleActivity() {
    override var isSearchBarEnabled = true

    /** The section the pager shows, and the sections whose page is on screen (Settings > Manage shown tabs). */
    private val selectedSection = mutableStateOf("")
    private val shownSections = mutableStateOf<List<String>>(emptyList())

    /** The shared chrome's collapse connection, set by the composition; the View pages' scroll ends in it. */
    @Volatile private var collapseTarget: BottomNavCollapse? = null

    private val binding by viewBinding(ActivityMainBinding::inflate)

    private var launchedDialer = false
    private var storedShowTabs = 0
    private var storedFontSize = 0
    private var storedStartNameWithSurname = false
    var cachedContacts = ArrayList<Contact>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // #868 the window every app of the fleet wears (edge-to-edge, transparent bars), then the layout.
        FleetChrome.apply(this)
        setContent { DialerShell() }
        appLaunched(BuildConfig.APPLICATION_ID)
        org.fossify.phone.updater.Updater.start(this)
        setupOptionsMenu()
        refreshMenuItems()
        setupEdgeToEdge(padBottomImeAndSystem = emptyList())

        EventBus.getDefault().register(this)
        launchedDialer = savedInstanceState?.getBoolean(OPEN_DIAL_PAD_AT_LAUNCH) ?: false

        if (isDefaultDialer()) {
            checkContactPermissions()

            if (!config.wasOverlaySnackbarConfirmed && !Settings.canDrawOverlays(this)) {
                val snackbar = Snackbar.make(
                    binding.mainHolder,
                    R.string.allow_displaying_over_other_apps,
                    Snackbar.LENGTH_INDEFINITE
                ).setAction(R.string.ok) {
                    config.wasOverlaySnackbarConfirmed = true
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                }

                snackbar.setBackgroundTint(getProperBackgroundColor().darkenColor())
                snackbar.setTextColor(getProperTextColor())
                snackbar.setActionTextColor(getProperTextColor())
                snackbar.show()
            }

            handleFullScreenNotificationsPermission { granted ->
                if (!granted) {
                    toast(org.fossify.commons.R.string.notifications_disabled)
                }
            }
        } else {
            launchSetDefaultDialerIntent()
        }

        if (isQPlus() && (config.blockUnknownNumbers || config.blockHiddenNumbers)) {
            setDefaultCallerIdApp()
        }

        setupTabs()
        Contact.sorting = config.sorting
    }

    override fun onResume() {
        super.onResume()
        if (storedShowTabs != config.showTabs) {
            config.lastUsedViewPagerPage = 0
            System.exit(0)
            return
        }

        updateMenuColors()
        val properPrimaryColor = getProperPrimaryColor()
        val dialpadIcon = resources.getColoredDrawableWithColor(R.drawable.ic_dialpad_vector, properPrimaryColor.getContrastColor())
        binding.mainDialpadButton.setImageDrawable(dialpadIcon)

        updateTextColors(binding.mainHolder)

        getAllFragments().forEach {
            it?.setupColors(getProperTextColor(), getProperPrimaryColor(), getProperPrimaryColor())
        }

        val configStartNameWithSurname = config.startNameWithSurname
        if (storedStartNameWithSurname != configStartNameWithSurname) {
            getContactsFragment()?.startNameWithSurnameChanged(configStartNameWithSurname)
            getFavoritesFragment()?.startNameWithSurnameChanged(configStartNameWithSurname)
            storedStartNameWithSurname = config.startNameWithSurname
        }

        if (!binding.mainMenu.isSearchOpen) {
            refreshItems()
        }

        val configFontSize = config.fontSize
        if (storedFontSize != configFontSize) {
            getAllFragments().forEach {
                it?.fontSizeChanged()
            }
        }

        checkShortcuts()
        Handler().postDelayed({
            getRecentsFragment()?.refreshItems()
        }, 2000)
    }

    override fun onPause() {
        super.onPause()
        storedShowTabs = config.showTabs
        storedStartNameWithSurname = config.startNameWithSurname
        config.lastUsedViewPagerPage = binding.viewPager.currentItem
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)
        // we don't really care about the result, the app can work without being the default Dialer too
        if (requestCode == REQUEST_CODE_SET_DEFAULT_DIALER) {
            checkContactPermissions()
        } else if (requestCode == REQUEST_CODE_SET_DEFAULT_CALLER_ID && resultCode != Activity.RESULT_OK) {
            toast(R.string.must_make_default_caller_id_app, length = Toast.LENGTH_LONG)
            baseConfig.blockUnknownNumbers = false
            baseConfig.blockHiddenNumbers = false
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(OPEN_DIAL_PAD_AT_LAUNCH, launchedDialer)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshItems()
    }

    override fun onBackPressedCompat(): Boolean {
        return if (binding.mainMenu.isSearchOpen) {
            binding.mainMenu.closeSearch()
            true
        } else {
            false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        EventBus.getDefault().unregister(this)
    }

    private fun refreshMenuItems() {
        val currentFragment = getCurrentFragment()
        val isListOfContacts = currentFragment is ContactsFragment || currentFragment is FavoritesFragment
        binding.mainMenu.requireToolbar().menu.apply {
            findItem(R.id.clear_call_history).isVisible = currentFragment == getRecentsFragment()
            findItem(R.id.sort).isVisible = isListOfContacts
            findItem(R.id.filter).isVisible = isListOfContacts
            findItem(R.id.create_new_contact).isVisible = currentFragment == getContactsFragment()
            findItem(R.id.change_view_type).isVisible = currentFragment == getFavoritesFragment()
            findItem(R.id.column_count).isVisible = currentFragment == getFavoritesFragment() && config.viewType == VIEW_TYPE_GRID
        }
        // The SMS page has its own new-message button; the dialpad button belongs to the call pages.
        binding.mainDialpadButton.isVisible = currentFragment !is SmsFragment
    }

    private fun setupOptionsMenu() {
        binding.mainMenu.apply {
            requireToolbar().inflateMenu(R.menu.menu)
            toggleHideOnScroll(false)
            setupMenu()

            onSearchClosedListener = {
                getAllFragments().forEach {
                    it?.onSearchQueryChanged("")
                }
            }

            onSearchTextChangedListener = { text ->
                getCurrentFragment()?.onSearchQueryChanged(text)
            }

            // #1411 Configs on the fleet pattern: General | About (libs:ui-nav-bars, Perms inside General) + SIM | Status.
            FleetConfigsEntries.addTo(requireToolbar().menu)
            if (requireToolbar().menu.findItem(ID_CONFIGS_SIM) == null) {
                requireToolbar().menu.add(Menu.NONE, ID_CONFIGS_SIM, Menu.NONE, "Configs ▸ SIM")
                requireToolbar().menu.add(Menu.NONE, ID_CONFIGS_STATUS, Menu.NONE, "Configs ▸ Status")
                requireToolbar().menu.add(Menu.NONE, ID_CONFIGS_USAGE, Menu.NONE, "Configs ▸ Usage")
            }
            requireToolbar().setOnMenuItemClickListener { menuItem ->
                if (FleetConfigsEntries.handle(this@MainActivity, menuItem)) return@setOnMenuItemClickListener true
                when (menuItem.itemId) {
                    ID_CONFIGS_SIM -> startActivity(SimConfigsActivity.intent(this@MainActivity, SimConfigsActivity.PAGE_SIM))
                    ID_CONFIGS_STATUS -> startActivity(SimConfigsActivity.intent(this@MainActivity, SimConfigsActivity.PAGE_STATUS))
                    ID_CONFIGS_USAGE -> startActivity(SimConfigsActivity.intent(this@MainActivity, SimConfigsActivity.PAGE_USAGE))
                    R.id.clear_call_history -> clearCallHistory()
                    R.id.create_new_contact -> launchCreateNewContactIntent()
                    R.id.sort -> showSortingDialog(showCustomSorting = getCurrentFragment() is FavoritesFragment)
                    R.id.filter -> showFilterDialog()
                    R.id.settings -> launchSettings()
                    R.id.change_view_type -> changeViewType()
                    R.id.column_count -> changeColumnCount()
                    R.id.about -> launchAbout()
                    else -> return@setOnMenuItemClickListener false
                }
                return@setOnMenuItemClickListener true
            }
        }
    }

    private fun changeColumnCount() {
        val items = ArrayList<RadioItem>()
        for (i in 1..CONTACTS_GRID_MAX_COLUMNS_COUNT) {
            items.add(RadioItem(i, resources.getQuantityString(R.plurals.column_counts, i, i)))
        }

        val currentColumnCount = config.contactsGridColumnCount
        RadioGroupDialog(this, ArrayList(items), currentColumnCount) {
            val newColumnCount = it as Int
            if (currentColumnCount != newColumnCount) {
                config.contactsGridColumnCount = newColumnCount
                getFavoritesFragment()?.columnCountChanged()
            }
        }
    }

    private fun changeViewType() {
        ChangeViewTypeDialog(this) {
            refreshMenuItems()
            getFavoritesFragment()?.refreshItems()
        }
    }

    private fun updateMenuColors() {
        binding.mainMenu.updateColors()
    }

    private fun checkContactPermissions() {
        handlePermission(PERMISSION_READ_CONTACTS) {
            initFragments()
        }
    }

    private fun clearCallHistory() {
        val confirmationText = "${getString(R.string.clear_history_confirmation)}\n\n${getString(R.string.cannot_be_undone)}"
        ConfirmationDialog(this, confirmationText) {
            RecentsHelper(this).removeAllRecentCalls(this) {
                runOnUiThread {
                    getRecentsFragment()?.refreshItems(invalidate = true)
                }
            }
        }
    }

    @SuppressLint("NewApi")
    private fun checkShortcuts() {
        val appIconColor = config.appIconColor
        if (isNougatMR1Plus() && config.lastHandledShortcutColor != appIconColor) {
            val launchDialpad = getLaunchDialpadShortcut(appIconColor)

            try {
                shortcutManager.dynamicShortcuts = listOf(launchDialpad)
                config.lastHandledShortcutColor = appIconColor
            } catch (ignored: Exception) {
            }
        }
    }

    @SuppressLint("NewApi")
    private fun getLaunchDialpadShortcut(appIconColor: Int): ShortcutInfo {
        val newEvent = getString(R.string.dialpad)
        val drawable = resources.getDrawable(R.drawable.shortcut_dialpad)
        (drawable as LayerDrawable).findDrawableByLayerId(R.id.shortcut_dialpad_background).applyColorFilter(appIconColor)
        val bmp = drawable.convertToBitmap()

        val intent = Intent(this, DialpadActivity::class.java)
        intent.action = Intent.ACTION_VIEW
        return ShortcutInfo.Builder(this, "launch_dialpad")
            .setShortLabel(newEvent)
            .setLongLabel(newEvent)
            .setIcon(Icon.createWithBitmap(bmp))
            .setIntent(intent)
            .build()
    }

    private fun visibleTabs() = visibleDialerTabs(config.showTabs)

    private fun initFragments() {
        binding.viewPager.offscreenPageLimit = 2
        binding.viewPager.addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
            override fun onPageScrollStateChanged(state: Int) {}

            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {}

            override fun onPageSelected(position: Int) {
                onTabSelected(position)
            }
        })

        // selecting the proper tab sometimes glitches, add an extra selector to make sure we have it right
        binding.viewPager.post {
            Handler().postDelayed({
                var wantedTab = getDefaultTab()

                // open the Recents tab if we got here by clicking a missed call notification
                if (intent.action == Intent.ACTION_VIEW && config.showTabs and TAB_CALL_HISTORY > 0) {
                    wantedTab = visibleTabs().indexOfFirst { it.visibilityMask == TAB_CALL_HISTORY }.coerceAtLeast(wantedTab)
                }

                binding.viewPager.currentItem = wantedTab
                onTabSelected(wantedTab)
            }, 100L)
        }

        binding.mainDialpadButton.setOnClickListener {
            launchDialpad()
        }

        binding.viewPager.onGlobalLayout {
            refreshMenuItems()
        }

        if (config.openDialPadAtLaunch && !launchedDialer) {
            launchDialpad()
            launchedDialer = true
        }
    }

    /** The pager landed on [position]: move the island's pill there and do what opening that page means. */
    private fun onTabSelected(position: Int) {
        val tab = visibleTabs().getOrNull(position) ?: return
        selectedSection.value = tab.sectionId
        getAllFragments().forEach {
            it?.finishActMode()
        }
        getCurrentFragment()?.onSearchQueryChanged(binding.mainMenu.getCurrentQuery())

        // Opening the call history is the owner acknowledging the misses
        // it lists, which is what dismisses the system's notification.
        if (tab.visibilityMask == TAB_CALL_HISTORY) {
            clearMissedCalls()
        }
        refreshMenuItems()
    }

    @Composable
    private fun DialerShell() {
        val sectionId = selectedSection.value
        val shown = DialerNav.shown(shownSections.value)
        val dark = ColorUtils.calculateLuminance(getProperBackgroundColor()) < 0.5
        NavBarTopFirstRowScaffold(
            title = DialerNav.decl.section(sectionId)?.label ?: getString(R.string.app_launcher_name),
            // The title island lists the sections on screen (each a single page), so a pick shows that page.
            hierarchy = shown.map { HierarchyEntry(it.id, it.label, 0, it.id, selected = it.id == sectionId) },
            onNavigate = { e -> selectSection(e.sectionId) },
            onAccount = rememberAccountLauncher(),
            onConfigs = { launchSettings() },
            onAi = rememberSuperAppAiLauncher(),
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            dark = dark,
        ) {
            TopBarDrawer { close ->
                shown.forEach { sec ->
                    Text(
                        sec.label,
                        Modifier.fillMaxWidth().clickable { selectSection(sec.id); close() }.padding(horizontal = 20.dp, vertical = 14.dp),
                    )
                }
            }
            NavBarBottomHost(
                entries = shown.map { navEntry(it.id, it.label, it.icon) },
                selectedId = sectionId,
                onSelect = { selectSection(it.id) },
                dark = dark,
            ) {
                val collapse = LocalNavChromeCollapse.current
                SideEffect { collapseTarget = collapse }
                AndroidView(
                    factory = {
                        // The shell's chrome owns the window insets (status bar above, nav bar below), so the Fossify pages get none.
                        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, _ -> WindowInsetsCompat.CONSUMED }
                        ChromeScrollHooks.install(binding.root) { delta -> collapseTarget?.onScrollDelta(delta) }
                        binding.root
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    /** A tap on the bottom nav: show that section's page. */
    private fun selectSection(sectionId: String) {
        val index = visibleTabs().indexOfFirst { it.sectionId == sectionId }
        if (index >= 0) {
            binding.viewPager.currentItem = index
        }
    }

    private fun setupTabs() {
        binding.viewPager.adapter = null
        val tabs = visibleTabs()
        shownSections.value = DialerNav.shown(tabs.map { it.sectionId }).map { it.id }
        selectedSection.value = tabs.getOrNull(binding.viewPager.currentItem)?.sectionId ?: tabs.firstOrNull()?.sectionId.orEmpty()

        storedShowTabs = config.showTabs
        storedStartNameWithSurname = config.startNameWithSurname
    }

    private fun refreshItems() {
        if (isDestroyed || isFinishing) {
            return
        }

        binding.apply {
            if (viewPager.adapter == null) {
                viewPager.adapter = ViewPagerAdapter(this@MainActivity)
                viewPager.currentItem = getDefaultTab()
                viewPager.onGlobalLayout {
                    refreshFragments()
                }
            } else {
                refreshFragments()
            }
        }
    }

    /**
     * Cloud Dialer: Home's "see all" and its missed-calls card both land on the
     * call history, which is the page that holds the rows they summarise.
     */
    fun openCallHistoryTab() {
        val callHistoryIndex = visibleTabs().indexOfFirst { it.visibilityMask == TAB_CALL_HISTORY }
        if (callHistoryIndex >= 0) {
            binding.viewPager.currentItem = callHistoryIndex
        }
    }

    private fun launchDialpad() {
        Intent(applicationContext, DialpadActivity::class.java).apply {
            startActivity(this)
        }
    }

    fun refreshFragments() {
        cacheContacts()
        getHomeFragment()?.refreshItems()
        getContactsFragment()?.refreshItems()
        getFavoritesFragment()?.refreshItems()
        getRecentsFragment()?.refreshItems()
        getSmsFragment()?.refreshItems()
    }

    private fun getAllFragments(): List<MyViewPagerFragment<*>?> = visibleTabs().map { tab ->
        when {
            tab.visibilityMask == TAB_CONTACTS -> getContactsFragment()
            tab.visibilityMask == TAB_FAVORITES -> getFavoritesFragment()
            tab.visibilityMask == TAB_CALL_HISTORY -> getRecentsFragment()
            tab.layoutResourceId == R.layout.fragment_sms -> getSmsFragment()
            else -> getHomeFragment()
        }
    }

    private fun getCurrentFragment(): MyViewPagerFragment<*>? = getAllFragments().getOrNull(binding.viewPager.currentItem)

    private fun getHomeFragment(): HomeFragment? = findViewById(R.id.home_fragment)

    private fun getContactsFragment(): ContactsFragment? = findViewById(R.id.contacts_fragment)

    private fun getFavoritesFragment(): FavoritesFragment? = findViewById(R.id.favorites_fragment)

    private fun getRecentsFragment(): RecentsFragment? = findViewById(R.id.recents_fragment)

    private fun getSmsFragment(): SmsFragment? = findViewById(R.id.sms_fragment)

    /**
     * Which page the app lands on. Home unless the owner explicitly chose one
     * of the other three in Settings, because a page whose whole job is to
     * summarise his calling life is worth nothing behind a swipe.
     *
     * commons' TAB_LAST_USED is the shipping default of that setting and has no
     * spare bit of its own, so this fork reads it as "Home" and Settings labels
     * it that way — see SettingsActivity.setupDefaultTab.
     */
    private fun getDefaultTab(): Int = visibleTabs()
        .indexOfFirst { it.visibilityMask == config.defaultTab }
        .coerceAtLeast(HOME_TAB_INDEX)

    private fun launchSettings() {
        hideKeyboard()
        startActivity(Intent(applicationContext, SettingsActivity::class.java))
    }

    private fun launchAbout() {
        startActivity(Intent(applicationContext, AboutActivity::class.java))
    }

    private fun showSortingDialog(showCustomSorting: Boolean) {
        ChangeSortingDialog(this, showCustomSorting) {
            getFavoritesFragment()?.refreshItems {
                if (binding.mainMenu.isSearchOpen) {
                    getCurrentFragment()?.onSearchQueryChanged(binding.mainMenu.getCurrentQuery())
                }
            }

            getContactsFragment()?.refreshItems {
                if (binding.mainMenu.isSearchOpen) {
                    getCurrentFragment()?.onSearchQueryChanged(binding.mainMenu.getCurrentQuery())
                }
            }
        }
    }

    private fun showFilterDialog() {
        FilterContactSourcesDialog(this) {
            getFavoritesFragment()?.refreshItems {
                if (binding.mainMenu.isSearchOpen) {
                    getCurrentFragment()?.onSearchQueryChanged(binding.mainMenu.getCurrentQuery())
                }
            }

            getContactsFragment()?.refreshItems {
                if (binding.mainMenu.isSearchOpen) {
                    getCurrentFragment()?.onSearchQueryChanged(binding.mainMenu.getCurrentQuery())
                }
            }

            getRecentsFragment()?.refreshItems {
                if (binding.mainMenu.isSearchOpen) {
                    getCurrentFragment()?.onSearchQueryChanged(binding.mainMenu.getCurrentQuery())
                }
            }
        }
    }

    fun cacheContacts() {
        val privateCursor = getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
        ContactsHelper(this).getContacts(getAll = true, showOnlyContactsWithNumbers = true) { contacts ->
            if (SMT_PRIVATE !in config.ignoredContactSources) {
                val privateContacts = MyContactsContentProvider.getContacts(this, privateCursor)
                if (privateContacts.isNotEmpty()) {
                    contacts.addAll(privateContacts)
                    contacts.sort()
                }
            }

            try {
                cachedContacts.clear()
                cachedContacts.addAll(contacts)
            } catch (ignored: Exception) {
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun refreshCallLog(event: Events.RefreshCallLog) {
        getRecentsFragment()?.refreshItems()
    }
}


private const val ID_CONFIGS_SIM = 0x0F1C0101
private const val ID_CONFIGS_STATUS = 0x0F1C0102
private const val ID_CONFIGS_USAGE = 0x0F1C0103
