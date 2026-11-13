package app.sterna.ui.settings

import com.diegonmarcos.superapp.perms.rememberPermsResumes
import com.diegonmarcos.superapp.perms.PermsLink
import app.sterna.BuildConfig
import app.sterna.contacts.AndroidContacts
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.text.format.DateUtils
import app.sterna.core.data.account.StoredAccount
import app.sterna.pgp.rememberPgpInteractionLauncher
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Info
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.layout.size
import app.sterna.ui.components.AccountPalette
import app.sterna.ui.components.accountColorOf
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BeachAccess
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import app.sterna.ui.components.Icon
import app.sterna.ui.components.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import android.content.Context
import org.openintents.openpgp.util.OpenPgpProviderUtil
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.account.StoredIdentity
import app.sterna.ui.browser.InAppBrowser
import app.sterna.core.data.account.SyncWindow
import app.sterna.core.data.account.syncWindowChoices
import app.sterna.core.data.filter.VacationFilterLine
import app.sterna.core.data.filter.vacationFilterLine
import app.sterna.core.data.settings.ListDensity
import app.sterna.core.data.settings.MessageTextSize
import app.sterna.core.data.settings.PreviewLines
import app.sterna.core.data.settings.SwipeAction
import app.sterna.core.data.settings.AppStyle
import app.sterna.core.data.settings.ThemeMode
import app.sterna.core.data.settings.DeliveryMode
import app.sterna.core.data.settings.NotificationContent
import app.sterna.push.PushController
import app.sterna.push.PushStatus
import app.sterna.ui.SCREEN_SLIDE_MS
import app.sterna.ui.appLabelOf
import app.sterna.ui.navigateOnce
import app.sterna.ui.rememberLeaveOnce
import app.sterna.ui.rememberMotionEnabled
import app.sterna.R
import app.sterna.fleet.CloudSecrets
import app.sterna.ui.CloudSecretField
import app.sterna.ui.connect.ConnectScreen
import app.sterna.ui.components.AppPasswordHelpLink
import app.sterna.ui.components.LoadingRing
import app.sterna.ui.components.PendingImportAccountsSection
import app.sterna.core.data.account.AuthType
import app.sterna.core.data.mail.OAuthProvider
import app.sterna.ui.theme.MailMetrics
import android.widget.Toast
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Settings hub (DESIGN.md → "Settings & secondary screens"). The hub stays short and scannable;
 *  depth lives in detail screens reached via an internal nav graph. */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onAccountsChanged: () -> Unit = {},
    /** When set, deep-link straight to this account's detail screen (drawer → tap account). */
    initialAccountId: String? = null,
    viewModel: SettingsViewModel = viewModel(),
    accountsViewModel: AccountsViewModel = viewModel(),
) {
    val nav = rememberNavController()
    // The system "Remove animations" setting switches these slides off (#100). Captured here
    // because rememberMotionEnabled() is @Composable and the transition lambdas are not.
    val motionEnabled = rememberMotionEnabled()
    // Deep-link from the drawer's account row (#34): the inner graph starts ON the account detail
    // so the hub never renders, and Back falls through to the caller rather than popping it.
    // The slides are opaque and cover the viewport at every frame, so a fast double-back cannot
    // expose a blank window-background frame. No fade here; ≤ 250 ms cap in DESIGN.md (#100).
    // Every action below goes through [navigateOnce]: taps landing during the slide otherwise
    // stack several copies of the same page (#106).
    // [settingsRouteFor] decides which screen the deep link opens, here as at the accounts list
    // below: a shared mailbox would otherwise land on the login editor, whose password field writes
    // under the LOGIN's key.
    // The [remember] freezes the route at the first composition: NavHost keys its graph on
    // (route, startDestination, builder), so a bare expression rebuilds it under a standing user.
    // PINNED BY SharedAccountScreenTest — the start destination, statement by statement
    val startDestination = remember(initialAccountId) {
        if (initialAccountId != null) {
            settingsRouteFor(initialAccountId, accountsViewModel.accounts.value)
        } else {
            "hub"
        }
    }
    NavHost(
        navController = nav,
        startDestination = startDestination,
        enterTransition = {
            if (!motionEnabled) EnterTransition.None else slideInHorizontally(tween(SCREEN_SLIDE_MS)) { it }
        },
        exitTransition = {
            if (!motionEnabled) ExitTransition.None else slideOutHorizontally(tween(SCREEN_SLIDE_MS)) { -it / 4 }
        },
        popEnterTransition = {
            if (!motionEnabled) EnterTransition.None else slideInHorizontally(tween(SCREEN_SLIDE_MS)) { -it / 4 }
        },
        popExitTransition = {
            if (!motionEnabled) ExitTransition.None else slideOutHorizontally(tween(SCREEN_SLIDE_MS)) { it }
        },
    ) {
        composable("hub") { entry ->
            val accounts by accountsViewModel.accounts.collectAsStateWithLifecycle()
            val currentId by accountsViewModel.currentId.collectAsStateWithLifecycle()
            val currentLabel = accounts.firstOrNull { it.id == currentId }?.label().orEmpty()
            val context = LocalContext.current
            // The About rows leave the app: one opener for all, so two rows tapped together open
            // once (#106).
            val leaveOnce = rememberLeaveOnce(entry)
            SettingsHub(
                onBack = onBack,
                onOpenAccounts = { entry.navigateOnce { nav.navigate("accounts") } },
                onOpenAppearance = { entry.navigateOnce { nav.navigate("appearance") } },
                onOpenReading = { entry.navigateOnce { nav.navigate("reading") } },
                onOpenNotifications = { entry.navigateOnce { nav.navigate("notifications") } },
                onOpenVacation = { entry.navigateOnce { nav.navigate("vacation") } },
                onOpenFilters = { entry.navigateOnce { nav.navigate("filters") } },
                onOpenAlertAccounts = { entry.navigateOnce { nav.navigate("alertAccounts") } },
                onOpenPrivacy = { entry.navigateOnce { nav.navigate("privacy") } },
                onOpenStorage = { entry.navigateOnce { nav.navigate("storage") } },
                onOpenBackup = { entry.navigateOnce { nav.navigate("backup") } },
                onOpenUpdate = { entry.navigateOnce { nav.navigate("update") } },
                onOpenUrl = { url -> leaveOnce { openUrl(context, url) } },
                // The Text rows are pages of THIS app now, so they navigate like every other row
                // in the hub rather than leaving for the keyboard. navigateOnce, not leaveOnce:
                // the guard that matters is the one against a tap landing mid-slide and stacking
                // two copies of the page, which is the guard the in-app rows all use.
                onOpenTextTool = { textEntry -> entry.navigateOnce { nav.navigate(textEntry.route) } },
                currentAccountLabel = currentLabel,
            )
        }
        // Configs ▸ Text. Four pages of THIS app, over this app's own store — they used to be
        // intents into the Cloud Keyboard's settings, which is why nothing on them could be
        // edited from here. The routes are declared once, in TEXT_TOOLS_ENTRIES, and the test
        // holds this graph against that list so a row cannot point at a destination that is not
        // registered.
        composable("textAiRouting") { entry ->
            MailAiRoutingScreen(onBack = { entry.navigateOnce { nav.popBackStack() } })
        }
        composable("textEnhance") { entry ->
            MailTextEnhanceScreen(onBack = { entry.navigateOnce { nav.popBackStack() } })
        }
        composable("textResume") { entry ->
            MailTextResumeScreen(onBack = { entry.navigateOnce { nav.popBackStack() } })
        }
        composable("textAnswer") { entry ->
            MailAnswerPredictionScreen(onBack = { entry.navigateOnce { nav.popBackStack() } })
        }
        composable("textTranslation") { entry ->
            MailTranslationScreen(onBack = { entry.navigateOnce { nav.popBackStack() } })
        }
        composable("accounts") { entry ->
            AccountsScreen(
                viewModel = accountsViewModel,
                onBack = { entry.navigateOnce { nav.popBackStack() } },
                onOpenAccount = { id -> entry.navigateOnce { nav.navigate(settingsRouteFor(id, accountsViewModel.accounts.value)) } },
                onAddAccount = { entry.navigateOnce { nav.navigate("addAccount") } },
                onAccountsChanged = onAccountsChanged,
            )
        }
        composable("addAccount") { entry ->
            ConnectScreen(
                onConnected = {
                    accountsViewModel.refresh()
                    onAccountsChanged()
                    // unguarded: this is not a tap. It fires from ConnectScreen's LaunchedEffect
                    // when the sign-in state flips — on a coroutine, on the app's schedule, which
                    // may well be while the app is backgrounded. An entry's lifecycle is capped by
                    // the host's, so NO entry is RESUMED then and the guard would silently swallow
                    // the pop, stranding the user on a Connect form for an account that has already
                    // been added. Same family as the mailto:/notification navigations: a single
                    // consumption whose loss is not a no-op.
                    // Unguarded: not a tap but a LaunchedEffect, possibly firing while
                    // backgrounded with no entry RESUMED, where the guard would swallow the pop.
                    nav.popBackStack()
                },
            )
        }
        // The belt behind [settingsRouteFor]. A route is just a string: anything navigating to
        // "account/<id>" arrives with nothing between the id and the login editor's password field,
        // which writes under [StoredAccount.loginKey].
        // PINNED BY SharedAccountScreenTest — the account route, statement by statement
        composable("account/{id}") { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            val accounts by accountsViewModel.accounts.collectAsStateWithLifecycle()
            if (sharedAccountFor(id, accounts) != null) {
                // A shared mailbox reaching this route gets its own screen, never the login editor.
                SharedAccountScreen(
                    accountId = id,
                    viewModel = accountsViewModel,
                    onBack = { entry.navigateOnce { if (!nav.popBackStack()) onBack() } },
                    onAccountsChanged = onAccountsChanged,
                )
            } else {
                AccountDetailScreen(
                    accountId = id,
                    viewModel = accountsViewModel,
                    // As the only inner destination, Back falls through to the caller (#34).
                    // Inside the guard: an ignored re-entrant tap must not read as "nothing left".
                    onBack = { entry.navigateOnce { if (!nav.popBackStack()) onBack() } },
                    // Guarded, unlike onConnected above: a tap, run inline from a dialog button.
                    onSignedOut = {
                        onAccountsChanged()
                        entry.navigateOnce { nav.popBackStack() }
                    },
                    onAccountsChanged = onAccountsChanged,
                )
            }
        }
        // The two settings a delegated account genuinely owns (#31). Its own destination rather
        // than a mode of "account/{id}", which edits the borrowed LOGIN's credential.
        // PINNED BY SharedAccountScreenTest — the shared route, statement by statement
        composable("sharedAccount/{id}") { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            SharedAccountScreen(
                accountId = id,
                viewModel = accountsViewModel,
                // Same fall-through as the account screen above, and for the same reason (#34).
                onBack = { entry.navigateOnce { if (!nav.popBackStack()) onBack() } },
                onAccountsChanged = onAccountsChanged,
            )
        }
        composable("appearance") { entry ->
            AppearanceScreen(viewModel = viewModel, onBack = { entry.navigateOnce { nav.popBackStack() } })
        }
        composable("reading") { entry ->
            ReadingScreen(viewModel = viewModel, onBack = { entry.navigateOnce { nav.popBackStack() } })
        }
        composable("notifications") { entry ->
            NotificationsScreen(viewModel = viewModel, onBack = { entry.navigateOnce { nav.popBackStack() } })
        }
        composable("vacation") { entry ->
            VacationScreen(onBack = { entry.navigateOnce { nav.popBackStack() } })
        }
        composable("filters") { entry ->
            FiltersScreen(onBack = { entry.navigateOnce { nav.popBackStack() } })
        }
        // #1624 Configs ▸ Alert (RSS) accounts: the account type behind the Alerts view. The default
        // c3-notify account ships out of the box; the user adds more RSS sources here.
        composable("alertAccounts") { entry ->
            AlertAccountsScreen(onBack = { entry.navigateOnce { nav.popBackStack() } })
        }
        composable("privacy") { entry ->
            PrivacySecurityScreen(viewModel = viewModel, onBack = { entry.navigateOnce { nav.popBackStack() } })
        }
        composable("storage") { entry ->
            StorageScreen(onBack = { entry.navigateOnce { nav.popBackStack() } })
        }
        composable("update") { entry ->
            // The link rows leave the app. Same opener and same double-tap guard the About
            // rows use (#106), handed in as a lambda so the page itself holds no Context and
            // cannot grow a seventh ad-hoc startActivity(ACTION_VIEW) of its own (#156).
            val context = LocalContext.current
            val leaveOnce = rememberLeaveOnce(entry)
            UpdateScreen(
                onBack = { entry.navigateOnce { nav.popBackStack() } },
                onOpenUrl = { url -> leaveOnce { openUrl(context, url) } },
            )
        }
        composable("backup") { entry ->
            BackupScreen(
                viewModel = viewModel,
                onAccountsImported = { accountsViewModel.refresh(); onAccountsChanged() },
                onBack = { entry.navigateOnce { nav.popBackStack() } },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsHub(
    onBack: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenReading: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenVacation: () -> Unit,
    onOpenFilters: () -> Unit,
    onOpenAlertAccounts: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenStorage: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenUpdate: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenTextTool: (TextToolsEntry) -> Unit,
    currentAccountLabel: String,
) {
    DetailScaffold(title = stringResource(R.string.settings_hub_title), onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            SettingsCategoryRow(
                Icons.Filled.Person,
                stringResource(R.string.settings_accounts_title),
                stringResource(R.string.settings_accounts_summary),
                onOpenAccounts,
            )
            SettingsCategoryRow(
                Icons.Filled.RssFeed,
                stringResource(R.string.settings_alert_accounts_title),
                stringResource(R.string.settings_alert_accounts_summary),
                onOpenAlertAccounts,
            )
            // Data, not hand-written rows: every row here opens one of THIS app's own Text
            // pages, over this app's own store. They used to open the Cloud Keyboard's pages,
            // which is why nothing on them could be edited from here. See TextToolsSection.
            SettingsSection(stringResource(R.string.settings_text_section)) {
                TEXT_TOOLS_ENTRIES.forEach { entry ->
                    SettingsCategoryRow(
                        entry.icon,
                        stringResource(entry.title),
                        stringResource(entry.summary),
                    ) { onOpenTextTool(entry) }
                }
            }
            SettingsSection(stringResource(R.string.settings_group_app)) {
                SettingsCategoryRow(Icons.Filled.Palette, stringResource(R.string.settings_appearance_title), stringResource(R.string.settings_appearance_summary), onOpenAppearance)
                SettingsCategoryRow(Icons.AutoMirrored.Filled.List, stringResource(R.string.settings_reading_title), stringResource(R.string.settings_reading_summary), onOpenReading)
                SettingsCategoryRow(Icons.Filled.Notifications, stringResource(R.string.settings_notifications_title), stringResource(R.string.settings_notifications_summary), onOpenNotifications)
                SettingsCategoryRow(Icons.Filled.Lock, stringResource(R.string.settings_privacy_title), stringResource(R.string.settings_privacy_summary), onOpenPrivacy)
                SettingsCategoryRow(Icons.Filled.Storage, stringResource(R.string.settings_storage_title), stringResource(R.string.settings_storage_summary), onOpenStorage)
                SettingsCategoryRow(Icons.Filled.Backup, stringResource(R.string.settings_backup_title), stringResource(R.string.settings_backup_summary), onOpenBackup)
                // The only row in this group that can replace the app it is part of.
                // Its page is the app's self-update, driven by the constellation's
                // shared updater against the same GHCR image the Constellation store
                // publishes for Cloud Mail - one update path, reached from here or
                // from the store, never two.
                // Refresh, not SystemUpdate: this tree cannot be compiled here, and Refresh
                // is in the CORE material-icons set that ships with material3, so it resolves
                // whether or not material-icons-extended is on the classpath. An icon name
                // that turns out not to exist is a build failure, and this app has already
                // spent a day not compiling.
                SettingsCategoryRow(Icons.Filled.Refresh, stringResource(R.string.settings_update_title), stringResource(R.string.settings_update_summary), onOpenUpdate)
            }
            // How the Mail section's folders and labels are navigated: the side menu (default), one row or two rows (#1189).
            SettingsSection(stringResource(R.string.settings_navigation_title)) {
                Box(Modifier.padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s8)) {
                    com.diegonmarcos.superapp.navbartop2.NavModeConfigCard(
                        sections = app.sterna.ui.mailNav.sections,
                        dark = app.sterna.ui.theme.LocalSternaDarkTheme.current,
                        dynamic = setOf("mail"),
                    )
                }
            }
            // Server-side settings that apply to the current account only; the header names it.
            val accountGroupTitle = if (currentAccountLabel.isNotBlank()) {
                stringResource(R.string.settings_group_current_account, currentAccountLabel)
            } else {
                stringResource(R.string.settings_group_current_account_generic)
            }
            SettingsSection(accountGroupTitle) {
                SettingsCategoryRow(Icons.Filled.BeachAccess, stringResource(R.string.settings_vacation_title), stringResource(R.string.settings_vacation_summary), onOpenVacation)
                SettingsCategoryRow(Icons.Filled.FilterAlt, stringResource(R.string.settings_filters_title), stringResource(R.string.settings_filters_summary), onOpenFilters)
            }
            // These four rows hand a URL to a browser; [onOpenUrl] carries the double-tap guard.
            // No Context is taken here, so a row added later cannot fire an intent of its own.
            SettingsSection(stringResource(R.string.settings_about_section)) {
                SettingsCategoryRow(
                    Icons.Filled.Info,
                    stringResource(R.string.settings_about_version),
                    "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.VERSION_DATE}",
                ) { onOpenUrl("$REPO_URL/releases") }
                SettingsCategoryRow(
                    Icons.Filled.Code,
                    stringResource(R.string.settings_about_source),
                    REPO_URL.removePrefix("https://"),
                ) { onOpenUrl(REPO_URL) }
                SettingsCategoryRow(
                    Icons.Filled.Description,
                    stringResource(R.string.settings_about_license),
                    "GPL-3.0-only",
                ) { onOpenUrl("$REPO_URL/src/branch/main/LICENSE") }
                SettingsCategoryRow(
                    Icons.Filled.Person,
                    stringResource(R.string.settings_about_author),
                    "emon",
                ) { onOpenUrl("https://codeberg.org/emon") }
            }
        }
    }
}

/** Where Sterna Mail lives; the About section links here (repo, releases, license). */
private const val REPO_URL = "https://codeberg.org/emon/sterna-mail"

/** Opens a URL in the app's own browser, and says whether anything took it. Call it through the
 *  opener from [rememberLeaveOnce]: it has no re-entrancy protection of its own (#106).
 *
 *  These are reference pages — the repo, the release notes, a licence, F-Droid — and reading one
 *  used to mean leaving the app (#307). The OAuth hand-offs below deliberately do NOT come through
 *  here: a sign-in belongs in the browser that already holds the session, and its redirect comes
 *  back on a scheme the built-in browser refuses to navigate. */
private fun openUrl(context: android.content.Context, url: String): Boolean =
    InAppBrowser.openLink(context, Uri.parse(url))

@Composable
private fun AppearanceScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val appStyle by viewModel.appStyle.collectAsStateWithLifecycle()
    val dynamicColor by viewModel.dynamicColor.collectAsStateWithLifecycle()
    val density by viewModel.listDensity.collectAsStateWithLifecycle()
    val previewLines by viewModel.previewLines.collectAsStateWithLifecycle()
    val pureBlack by viewModel.pureBlack.collectAsStateWithLifecycle()
    val listMonogram by viewModel.listMonogram.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var language by remember { mutableStateOf(currentAppLanguage()) }
    DetailScaffold(title = stringResource(R.string.settings_appearance_screen_title), onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            SettingsSection(stringResource(R.string.settings_language_section)) {
                SettingChoiceRow(
                    title = stringResource(R.string.settings_language_title),
                    options = AppLanguage.entries,
                    selected = language,
                    optionLabel = { languageLabel(context, it) },
                    onSelect = {
                        language = it
                        applyAppLanguage(it)
                    },
                )
            }
            SettingsSection(stringResource(R.string.settings_theme_section)) {
                SettingChoiceRow(
                    title = stringResource(R.string.settings_app_style_title),
                    options = AppStyle.entries,
                    selected = appStyle,
                    optionLabel = { appStyleLabel(context, it) },
                    onSelect = viewModel::setAppStyle,
                )
                SettingChoiceRow(
                    title = stringResource(R.string.settings_theme_title),
                    options = listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK),
                    selected = themeMode,
                    optionLabel = { themeLabel(context, it) },
                    onSelect = viewModel::setThemeMode,
                )
                // Material You is opt-in; Sterna's brand palette is the default. Android 12+ only.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    SettingSwitch(
                        title = stringResource(R.string.settings_dynamic_color_title),
                        subtitle = stringResource(R.string.settings_dynamic_color_subtitle),
                        checked = dynamicColor,
                        onCheckedChange = viewModel::setDynamicColor,
                    )
                }
                // Outside the Android 12 guard, which is about dynamic colour. Visible in light
                // theme too, where it is inert and says so.
                SettingSwitch(
                    title = stringResource(R.string.settings_pure_black_title),
                    subtitle = stringResource(R.string.settings_pure_black_subtitle),
                    checked = pureBlack,
                    onCheckedChange = viewModel::setPureBlack,
                )
                Text(
                    text = stringResource(R.string.settings_theme_sterna_caption),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s8),
                )
            }
            SettingsSection(stringResource(R.string.settings_message_list_section)) {
                SettingChoiceRow(
                    title = stringResource(R.string.settings_density_title),
                    options = listOf(ListDensity.COMPACT, ListDensity.NORMAL, ListDensity.SPACED),
                    selected = density,
                    optionLabel = { densityLabel(context, it) },
                    onSelect = viewModel::setListDensity,
                )
                SettingChoiceRow(
                    title = stringResource(R.string.settings_preview_title),
                    options = listOf(PreviewLines.NONE, PreviewLines.ONE, PreviewLines.THREE, PreviewLines.FIVE),
                    selected = previewLines,
                    optionLabel = { previewLabel(context, it) },
                    onSelect = viewModel::setPreviewLines,
                )
                SettingSwitch(
                    title = stringResource(R.string.settings_list_monogram_title),
                    subtitle = stringResource(R.string.settings_list_monogram_subtitle),
                    checked = listMonogram,
                    onCheckedChange = viewModel::setListMonogram,
                )
            }
        }
    }
}

private fun languageLabel(context: Context, language: AppLanguage): String = when (language) {
    AppLanguage.SYSTEM -> context.getString(R.string.settings_language_system)
    AppLanguage.ENGLISH -> context.getString(R.string.settings_language_english)
    AppLanguage.FRENCH -> context.getString(R.string.settings_language_french)
    AppLanguage.SPANISH -> context.getString(R.string.settings_language_spanish)
    AppLanguage.GERMAN -> context.getString(R.string.settings_language_german)
    AppLanguage.ITALIAN -> context.getString(R.string.settings_language_italian)
    AppLanguage.PORTUGUESE -> context.getString(R.string.settings_language_portuguese)
    AppLanguage.DUTCH -> context.getString(R.string.settings_language_dutch)
    AppLanguage.RUSSIAN -> context.getString(R.string.settings_language_russian)
    AppLanguage.POLISH -> context.getString(R.string.settings_language_polish)
}

private fun appStyleLabel(context: Context, style: AppStyle): String = when (style) {
    AppStyle.CLOUD_DARK -> context.getString(R.string.settings_app_style_cloud_dark)
    AppStyle.GMAIL_DARK -> context.getString(R.string.settings_app_style_gmail_dark)
    AppStyle.CLOUD_EINK -> context.getString(R.string.settings_app_style_cloud_eink)
}

private fun themeLabel(context: Context, mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> context.getString(R.string.settings_theme_auto)
    ThemeMode.LIGHT -> context.getString(R.string.settings_theme_light)
    ThemeMode.DARK -> context.getString(R.string.settings_theme_dark)
}

private fun densityLabel(context: Context, density: ListDensity): String = when (density) {
    ListDensity.COMPACT -> context.getString(R.string.settings_density_compact)
    ListDensity.NORMAL -> context.getString(R.string.settings_density_normal)
    ListDensity.SPACED -> context.getString(R.string.settings_density_spaced)
}

private fun previewLabel(context: Context, preview: PreviewLines): String = when (preview) {
    PreviewLines.NONE -> context.getString(R.string.settings_preview_subject_only)
    PreviewLines.ONE -> context.getString(R.string.settings_preview_one_line)
    PreviewLines.THREE -> context.getString(R.string.settings_preview_three_lines)
    PreviewLines.FIVE -> context.getString(R.string.settings_preview_five_lines)
}

@Composable
private fun ReadingScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val swipeRight by viewModel.swipeRight.collectAsStateWithLifecycle()
    val swipeLeft by viewModel.swipeLeft.collectAsStateWithLifecycle()
    val conversationView by viewModel.conversationView.collectAsStateWithLifecycle()
    val messageTextSize by viewModel.messageTextSize.collectAsStateWithLifecycle()
    val markReadOnDelete by viewModel.markReadOnDelete.collectAsStateWithLifecycle()
    val markReadOnArchive by viewModel.markReadOnArchive.collectAsStateWithLifecycle()
    val markReadOnMove by viewModel.markReadOnMove.collectAsStateWithLifecycle()
    val unarchiveOnReply by viewModel.unarchiveOnReply.collectAsStateWithLifecycle()
    val signatureOnReplies by viewModel.signatureOnReplies.collectAsStateWithLifecycle()
    val signatureBelowQuote by viewModel.signatureBelowQuote.collectAsStateWithLifecycle()
    val signatureDelimiter by viewModel.signatureDelimiter.collectAsStateWithLifecycle()
    val replyBar by viewModel.replyBar.collectAsStateWithLifecycle()
    val options = listOf(
        SwipeAction.TOGGLE_READ, SwipeAction.DELETE, SwipeAction.ARCHIVE, SwipeAction.FLAG, SwipeAction.NONE,
    )
    val context = LocalContext.current
    DetailScaffold(title = stringResource(R.string.settings_reading_screen_title), onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            // The first switch applies to both protocols; the second (unarchive on reply) is JMAP
            // only. Neither fact is a condition on the accounts: that truth belongs in the
            // permanent subtitle (SettingsScreenHonestyTest).
            SettingsSection(stringResource(R.string.settings_conversation_section)) {
                SettingSwitch(
                    title = stringResource(R.string.settings_conversation_title),
                    subtitle = stringResource(R.string.settings_conversation_subtitle),
                    checked = conversationView,
                    onCheckedChange = viewModel::setConversationView,
                )
                SettingSwitch(
                    title = stringResource(R.string.settings_unarchive_on_reply_title),
                    subtitle = stringResource(R.string.settings_unarchive_on_reply_subtitle),
                    checked = unarchiveOnReply,
                    onCheckedChange = viewModel::setUnarchiveOnReply,
                )
            }
            OfflineSection(viewModel)
            SettingsSection(stringResource(R.string.settings_message_section)) {
                SettingChoiceRow(
                    title = stringResource(R.string.settings_text_size_title),
                    options = listOf(
                        MessageTextSize.SMALL, MessageTextSize.NORMAL,
                        MessageTextSize.LARGE, MessageTextSize.HUGE,
                    ),
                    selected = messageTextSize,
                    optionLabel = { textSizeLabel(context, it) },
                    onSelect = viewModel::setMessageTextSize,
                )
                SettingMultiChoiceRow(
                    title = stringResource(R.string.settings_mark_read_title),
                    options = listOf(MarkReadOn.MOVE, MarkReadOn.DELETE, MarkReadOn.ARCHIVE),
                    checked = buildSet {
                        if (markReadOnDelete) add(MarkReadOn.DELETE)
                        if (markReadOnArchive) add(MarkReadOn.ARCHIVE)
                        if (markReadOnMove) add(MarkReadOn.MOVE)
                    },
                    optionLabel = { markReadLabel(context, it) },
                    noneLabel = stringResource(R.string.settings_mark_read_never),
                    onCheckedChange = { option, on ->
                        when (option) {
                            MarkReadOn.DELETE -> viewModel.setMarkReadOnDelete(on)
                            MarkReadOn.ARCHIVE -> viewModel.setMarkReadOnArchive(on)
                            MarkReadOn.MOVE -> viewModel.setMarkReadOnMove(on)
                        }
                    },
                )
                // On by default. The subtitle says where the two actions live without it: Reply has
                // its own icon in the top bar, Reply all and Forward head the menu beside it (#63).
                SettingSwitch(
                    title = stringResource(R.string.settings_reply_bar_title),
                    subtitle = stringResource(R.string.settings_reply_bar_subtitle),
                    checked = replyBar,
                    onCheckedChange = viewModel::setReplyBar,
                )
            }
            SettingsSection(stringResource(R.string.settings_swipe_actions_section)) {
                SettingChoiceRow(
                    title = stringResource(R.string.settings_swipe_right_title),
                    options = options,
                    selected = swipeRight,
                    optionLabel = { swipeLabel(context, it) },
                    onSelect = viewModel::setSwipeRight,
                )
                SettingChoiceRow(
                    title = stringResource(R.string.settings_swipe_left_title),
                    options = options,
                    selected = swipeLeft,
                    optionLabel = { swipeLabel(context, it) },
                    onSelect = viewModel::setSwipeLeft,
                )
            }
            // When the signature is inserted and what the block looks like; what it says is per
            // identity (Accounts → identity).
            SettingsSection(stringResource(R.string.settings_signature_section)) {
                SettingSwitch(
                    title = stringResource(R.string.settings_signature_on_replies_title),
                    subtitle = stringResource(R.string.settings_signature_on_replies_subtitle),
                    checked = signatureOnReplies,
                    onCheckedChange = viewModel::setSignatureOnReplies,
                )
                SettingSwitch(
                    title = stringResource(R.string.settings_signature_below_quote_title),
                    subtitle = stringResource(R.string.settings_signature_below_quote_subtitle),
                    checked = signatureBelowQuote,
                    onCheckedChange = viewModel::setSignatureBelowQuote,
                )
                // On by default; the "-- " line is what other mail apps recognise a signature by.
                // Off, the signature field holds exactly what is sent (#90).
                SettingSwitch(
                    title = stringResource(R.string.settings_signature_delimiter_title),
                    subtitle = stringResource(R.string.settings_signature_delimiter_subtitle),
                    checked = signatureDelimiter,
                    onCheckedChange = viewModel::setSignatureDelimiter,
                )
            }
        }
    }
}

private fun swipeLabel(context: Context, action: SwipeAction): String = when (action) {
    SwipeAction.NONE -> context.getString(R.string.settings_swipe_nothing)
    SwipeAction.TOGGLE_READ -> context.getString(R.string.settings_swipe_toggle_read)
    SwipeAction.DELETE -> context.getString(R.string.settings_swipe_delete)
    SwipeAction.ARCHIVE -> context.getString(R.string.settings_swipe_archive)
    SwipeAction.FLAG -> context.getString(R.string.settings_swipe_flag)
}

/** The three independent cases of the "Mark as read when" group, none constraining another (#67). */
private enum class MarkReadOn { DELETE, ARCHIVE, MOVE }

private fun markReadLabel(context: Context, on: MarkReadOn): String = when (on) {
    MarkReadOn.DELETE -> context.getString(R.string.settings_mark_read_deleting)
    MarkReadOn.ARCHIVE -> context.getString(R.string.settings_mark_read_archiving)
    MarkReadOn.MOVE -> context.getString(R.string.settings_mark_read_moving)
}

private fun textSizeLabel(context: Context, size: MessageTextSize): String = when (size) {
    MessageTextSize.SMALL -> context.getString(R.string.settings_text_size_small)
    MessageTextSize.NORMAL -> context.getString(R.string.settings_text_size_normal)
    MessageTextSize.LARGE -> context.getString(R.string.settings_text_size_large)
    MessageTextSize.HUGE -> context.getString(R.string.settings_text_size_huge)
}

@Composable
private fun NotificationsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val pushAll by viewModel.pushAllAccounts.collectAsStateWithLifecycle()
    val notifSettings by viewModel.notificationSettings.collectAsStateWithLifecycle()
    // Hoisted above the branch so the call site is the same whichever arm the screen opens on:
    // remembered state is keyed by position in the composition.
    val scroll = rememberScrollState()
    DetailScaffold(title = stringResource(R.string.settings_notifications_screen_title), onBack = onBack) { padding ->
        val state = notifSettings
        if (state == null) {
            // Nothing stored has been read yet (#57): showing the radios now would draw the
            // defaults over the reader's choice and accept a tap that writes them. A spinner
            // filling the viewport, never an empty Box: the screen slides in opaque.
            Box(Modifier.fillMaxSize().padding(padding)) {
                LoadingRing(Modifier.align(Alignment.Center))
            }
        } else {
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(scroll),
            ) {
                SettingsSection(stringResource(R.string.settings_delivery_section)) {
                    DeliveryModeOption(
                        title = stringResource(R.string.settings_delivery_instant),
                        subtitle = stringResource(R.string.settings_delivery_instant_desc),
                        selected = state.deliveryMode == DeliveryMode.INSTANT,
                        onClick = { viewModel.setDeliveryMode(DeliveryMode.INSTANT) },
                    )
                    DeliveryModeOption(
                        title = stringResource(R.string.settings_delivery_saver),
                        subtitle = stringResource(R.string.settings_delivery_saver_desc),
                        selected = state.deliveryMode == DeliveryMode.BATTERY_SAVER,
                        onClick = { viewModel.setDeliveryMode(DeliveryMode.BATTERY_SAVER) },
                    )
                }
                SettingsSection(stringResource(R.string.settings_new_mail_section)) {
                    SettingSwitch(
                        title = stringResource(R.string.settings_push_all_title),
                        subtitle = stringResource(R.string.settings_push_all_subtitle),
                        checked = pushAll,
                        onCheckedChange = viewModel::setPushAllAccounts,
                    )
                }
                SettingsSection(stringResource(R.string.settings_notif_content_section)) {
                    // Most talkative first, most discreet last: the order is itself the information.
                    DeliveryModeOption(
                        title = stringResource(R.string.settings_notif_content_preview),
                        subtitle = stringResource(R.string.settings_notif_content_preview_desc),
                        selected = state.notificationContent == NotificationContent.BODY_PREVIEW,
                        onClick = { viewModel.setNotificationContent(NotificationContent.BODY_PREVIEW) },
                    )
                    DeliveryModeOption(
                        title = stringResource(R.string.settings_notif_content_full),
                        subtitle = stringResource(R.string.settings_notif_content_full_desc),
                        selected = state.notificationContent == NotificationContent.SENDER_AND_SUBJECT,
                        onClick = { viewModel.setNotificationContent(NotificationContent.SENDER_AND_SUBJECT) },
                    )
                    DeliveryModeOption(
                        title = stringResource(R.string.settings_notif_content_sender),
                        subtitle = stringResource(R.string.settings_notif_content_sender_desc),
                        selected = state.notificationContent == NotificationContent.SENDER_ONLY,
                        onClick = { viewModel.setNotificationContent(NotificationContent.SENDER_ONLY) },
                    )
                    DeliveryModeOption(
                        title = stringResource(R.string.settings_notif_content_none),
                        subtitle = stringResource(R.string.settings_notif_content_none_desc),
                        selected = state.notificationContent == NotificationContent.NONE,
                        onClick = { viewModel.setNotificationContent(NotificationContent.NONE) },
                    )
                }
                SettingsSection(stringResource(R.string.settings_remind_section)) {
                    RemindDelayChoices()
                }
                SettingsSection(stringResource(R.string.settings_quiet_hours_section)) {
                    SettingSwitch(
                        title = stringResource(R.string.settings_quiet_hours_title),
                        subtitle = stringResource(R.string.settings_quiet_hours_subtitle),
                        checked = state.quietHoursEnabled,
                        onCheckedChange = viewModel::setQuietHoursEnabled,
                    )
                    if (state.quietHoursEnabled) {
                        TimePickerRow(
                            label = stringResource(R.string.settings_quiet_hours_start),
                            minutes = state.quietHoursStart,
                            onChange = viewModel::setQuietHoursStart,
                        )
                        TimePickerRow(
                            label = stringResource(R.string.settings_quiet_hours_end),
                            minutes = state.quietHoursEnd,
                            onChange = viewModel::setQuietHoursEnd,
                        )
                    }
                }
            }
        }
    }
}

private fun formatMinutes(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerRow(label: String, minutes: Int, onChange: (Int) -> Unit) {
    var show by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { show = true }
            .padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            formatMinutes(minutes),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    if (show) {
        val state = rememberTimePickerState(
            initialHour = minutes / 60,
            initialMinute = minutes % 60,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { show = false },
            confirmButton = {
                TextButton(onClick = {
                    onChange(state.hour * 60 + state.minute)
                    show = false
                }) { Text(stringResource(R.string.settings_save)) }
            },
            dismissButton = {
                TextButton(onClick = { show = false }) { Text(stringResource(R.string.settings_cancel)) }
            },
            text = { TimePicker(state = state) },
        )
    }
}

@Composable
private fun BackupScreen(
    viewModel: SettingsViewModel,
    onAccountsImported: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun toast(text: String) = scope.launch { snackbarHostState.showSnackbar(text) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            viewModel.exportSettings(uri) { ok ->
                toast(context.getString(
                    if (ok) R.string.settings_backup_exported else R.string.settings_backup_failed,
                ))
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.importSettings(
                uri,
                onResult = { ok, accountsAdded ->
                    toast(context.getString(
                        when {
                            !ok -> R.string.settings_backup_failed
                            accountsAdded > 0 -> R.string.settings_backup_imported_accounts
                            else -> R.string.settings_backup_imported
                        },
                    ))
                    if (ok && accountsAdded > 0) onAccountsImported()
                },
                onLanguageChanged = { applyAppLanguage(it) },
            )
        }
    }

    DetailScaffold(title = stringResource(R.string.settings_backup_screen_title), onBack = onBack) { padding ->
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
            ) {
                SettingsSection(stringResource(R.string.settings_backup_section)) {
                    Text(
                        stringResource(R.string.settings_backup_explainer),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s8),
                    )
                    Button(
                        onClick = { exportLauncher.launch("sterna-settings.json") },
                        modifier = Modifier.padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s4),
                    ) { Text(stringResource(R.string.settings_backup_export)) }
                    OutlinedButton(
                        onClick = {
                            importLauncher.launch(
                                arrayOf("application/json", "application/octet-stream", "text/plain"),
                            )
                        },
                        modifier = Modifier.padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s4),
                    ) { Text(stringResource(R.string.settings_backup_import)) }
                }
            }
            SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter).padding(padding))
        }
    }
}

@Composable
private fun PrivacySecurityScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val appLock by viewModel.appLockEnabled.collectAsStateWithLifecycle()
    val appLockUnavailable by viewModel.appLockUnavailable.collectAsStateWithLifecycle()
    val contactSuggestions by viewModel.contactSuggestions.collectAsStateWithLifecycle()
    val stripTracking by viewModel.stripTrackingParams.collectAsStateWithLifecycle()
    val confirmLinks by viewModel.confirmLinks.collectAsStateWithLifecycle()
    val askReadReceipt by viewModel.askReadReceipt.collectAsStateWithLifecycle()
    val quotedDatesUtc by viewModel.quotedDatesUtc.collectAsStateWithLifecycle()
    val imageAllowlist by viewModel.imageAllowlist.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // #1515 READ_CONTACTS is granted in Configs > Perms (libs:perms); the outcome is read when the screen resumes.
    var contactsAsked by rememberSaveable { mutableStateOf(false) }
    val contactsResumes = rememberPermsResumes()
    LaunchedEffect(contactsResumes) {
        if (contactsAsked) { contactsAsked = false; viewModel.setContactSuggestions(AndroidContacts.hasPermission(context)) }
    }
    DetailScaffold(title = stringResource(R.string.settings_privacy_screen_title), onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            SettingsSection(stringResource(R.string.settings_security_section)) {
                SettingSwitch(
                    title = stringResource(R.string.settings_app_lock_title),
                    subtitle = stringResource(R.string.settings_app_lock_subtitle),
                    checked = appLock,
                    onCheckedChange = viewModel::setAppLock,
                )
                if (appLockUnavailable) {
                    Text(
                        stringResource(R.string.settings_app_lock_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = MailMetrics.s16),
                    )
                }
            }
            SettingsSection(stringResource(R.string.settings_links_section)) {
                SettingSwitch(
                    title = stringResource(R.string.settings_strip_tracking_title),
                    subtitle = stringResource(R.string.settings_strip_tracking_subtitle),
                    checked = stripTracking,
                    onCheckedChange = viewModel::setStripTrackingParams,
                )
                SettingSwitch(
                    title = stringResource(R.string.settings_confirm_links_title),
                    subtitle = stringResource(R.string.settings_confirm_links_subtitle),
                    checked = confirmLinks,
                    onCheckedChange = viewModel::setConfirmLinks,
                )
            }
            // Next to the remote content it belongs with: both are "what does this message get to
            // learn about me?". One switch, and it asks — no third position that sends on its own.
            SettingsSection(stringResource(R.string.settings_read_receipt_section)) {
                SettingSwitch(
                    title = stringResource(R.string.settings_read_receipt_title),
                    subtitle = stringResource(R.string.settings_read_receipt_subtitle),
                    checked = askReadReceipt,
                    onCheckedChange = viewModel::setAskReadReceipt,
                )
            }
            // The dates the composer writes into outgoing text (#120). The subtitle says only
            // what changes and makes no privacy promise: the message keeps other traces of origin.
            SettingsSection(stringResource(R.string.settings_quoted_dates_utc_section)) {
                SettingSwitch(
                    title = stringResource(R.string.settings_quoted_dates_utc_title),
                    subtitle = stringResource(R.string.settings_quoted_dates_utc_subtitle),
                    checked = quotedDatesUtc,
                    onCheckedChange = viewModel::setQuotedDatesUtc,
                )
            }
            SettingsSection(stringResource(R.string.settings_image_allowlist_section)) {
                var showAddSender by remember { mutableStateOf(false) }
                if (imageAllowlist.isEmpty()) {
                    Text(
                        stringResource(R.string.settings_image_allowlist_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s8),
                    )
                }
                imageAllowlist.sorted().forEach { sender ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = MailMetrics.s16, end = MailMetrics.s4, top = MailMetrics.s8, bottom = MailMetrics.s8),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            sender,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { viewModel.setImageAllowed(sender, false) }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.settings_image_allowlist_remove))
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = MailMetrics.s8),
                    horizontalArrangement = Arrangement.spacedBy(MailMetrics.s8),
                ) {
                    TextButton(onClick = { showAddSender = true }) {
                        Text(stringResource(R.string.settings_image_allowlist_add))
                    }
                    if (imageAllowlist.isNotEmpty()) {
                        TextButton(onClick = viewModel::clearImageAllowlist) {
                            Text(stringResource(R.string.settings_image_allowlist_clear))
                        }
                    }
                }
                if (showAddSender) {
                    var sender by remember { mutableStateOf("") }
                    val focusRequester = remember { FocusRequester() }
                    LaunchedEffect(Unit) { focusRequester.requestFocus() }
                    fun submit() {
                        if (sender.isNotBlank()) {
                            viewModel.setImageAllowed(sender, true)
                            showAddSender = false
                        }
                    }
                    AlertDialog(
                        onDismissRequest = { showAddSender = false },
                        title = { Text(stringResource(R.string.settings_image_allowlist_add)) },
                        text = {
                            OutlinedTextField(
                                value = sender,
                                onValueChange = { sender = it },
                                singleLine = true,
                                placeholder = { Text(stringResource(R.string.settings_image_allowlist_hint)) },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { submit() }),
                                modifier = Modifier.focusRequester(focusRequester),
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = { submit() },
                                enabled = sender.isNotBlank(),
                            ) { Text(stringResource(R.string.settings_save)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showAddSender = false }) {
                                Text(stringResource(R.string.settings_cancel))
                            }
                        },
                    )
                }
            }
            SettingsSection(stringResource(R.string.settings_recipient_suggestions_section)) {
                SettingSwitch(
                    title = stringResource(R.string.settings_suggest_contacts_title),
                    subtitle = stringResource(R.string.settings_suggest_contacts_subtitle),
                    checked = contactSuggestions,
                    onCheckedChange = { wantOn ->
                        when {
                            !wantOn -> viewModel.setContactSuggestions(false)
                            AndroidContacts.hasPermission(context) -> viewModel.setContactSuggestions(true)
                            else -> { contactsAsked = true; PermsLink.open(context, "mail", listOf("contacts_read")) }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun StorageScreen(
    onBack: () -> Unit,
    viewModel: StorageViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val clearing by viewModel.clearing.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    DetailScaffold(title = stringResource(R.string.settings_storage_screen_title), onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            SettingsSection(stringResource(R.string.settings_on_device_usage_section)) {
                StorageStatRow(stringResource(R.string.settings_storage_total), formatBytes(state.totalBytes))
                StorageStatRow(stringResource(R.string.settings_storage_messages_db), formatBytes(state.databaseBytes))
                StorageStatRow(stringResource(R.string.settings_storage_attachments), formatBytes(state.attachmentBytes))
            }
            if (state.perAccount.isNotEmpty()) {
                SettingsSection(stringResource(R.string.settings_cached_per_account_section)) {
                    state.perAccount.forEach { account ->
                        StorageStatRow(account.label, "${account.messageCount}")
                    }
                }
            }
            if (state.quotas.isNotEmpty()) {
                SettingsSection(stringResource(R.string.settings_mailbox_quota_section)) {
                    state.quotas.forEach { QuotaRow(it) }
                }
            }
            SettingsSection(stringResource(R.string.settings_maintenance_section)) {
                Text(
                    stringResource(R.string.settings_clear_cache_help),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s4),
                )
                Button(
                    onClick = { confirm = true },
                    enabled = !clearing,
                    modifier = Modifier.padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s8),
                ) {
                    Text(
                        if (clearing) {
                            stringResource(R.string.settings_clearing)
                        } else {
                            stringResource(R.string.settings_clear_cache)
                        },
                    )
                }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.settings_clear_cache_dialog_title)) },
            text = {
                Text(stringResource(R.string.settings_clear_cache_dialog_body))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = false
                        viewModel.clearCache()
                    },
                ) { Text(stringResource(R.string.settings_clear), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.settings_cancel)) }
            },
        )
    }
}

@Composable
private fun QuotaRow(quota: QuotaUi) {
    val isStorage = quota.resourceType == "octets"
    val label = stringResource(
        if (isStorage) R.string.settings_quota_storage else R.string.settings_quota_messages,
    )
    fun fmt(value: Long) = if (isStorage) formatBytes(value) else value.toString()
    val limit = quota.limit?.takeIf { it > 0 }
    Column(Modifier.fillMaxWidth().padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s12)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(MailMetrics.s16))
            Text(
                if (limit != null) {
                    stringResource(R.string.settings_quota_used_of, fmt(quota.used), fmt(limit))
                } else {
                    fmt(quota.used)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (limit != null) {
            Spacer(Modifier.height(MailMetrics.s8))
            LinearProgressIndicator(
                progress = { (quota.used.toFloat() / limit).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun StorageStatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(MailMetrics.s16))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Every window has a label, including the ones the picker no longer offers: an account can still
 *  carry a retired window (nothing migrates it), and the row has to say which one. */
private fun syncWindowLabel(context: Context, window: SyncWindow): String = when (window) {
    SyncWindow.DAYS_30 -> context.getString(R.string.settings_sync_last_30_days)
    SyncWindow.DAYS_90 -> context.getString(R.string.settings_sync_last_90_days)
    SyncWindow.YEAR_1 -> context.getString(R.string.settings_sync_last_year)
    SyncWindow.COUNT_100 -> context.getString(R.string.settings_sync_100_messages)
    SyncWindow.COUNT_1000 -> context.getString(R.string.settings_sync_1000_messages)
    SyncWindow.COUNT_10000 -> context.getString(R.string.settings_sync_10000_messages)
    SyncWindow.COUNT_50 -> context.getString(R.string.settings_sync_50_messages)
    SyncWindow.COUNT_200 -> context.getString(R.string.settings_sync_200_messages)
    SyncWindow.COUNT_500 -> context.getString(R.string.settings_sync_500_messages)
    // WYSIWYG: "Everything" caches 10 000 messages and no more, so it is labelled as such —
    // otherwise an account left on this window promises a whole mailbox offline.
    SyncWindow.ALL -> context.getString(R.string.settings_sync_10000_messages)
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.0f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}

@Composable
private fun AccountsScreen(
    viewModel: AccountsViewModel,
    onBack: () -> Unit,
    onOpenAccount: (String) -> Unit,
    onAddAccount: () -> Unit,
    onAccountsChanged: () -> Unit,
) {
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val currentId by viewModel.currentId.collectAsStateWithLifecycle()
    val pending by viewModel.pendingImportAccounts.collectAsStateWithLifecycle()
    // #1090 the route each fleet account's mail is on follows the router's verdicts live.
    val routes by com.diegonmarcos.superapp.fleetendpoints.FleetTransport.router.status.collectAsStateWithLifecycle()
    val endpointVersion by viewModel.endpointVersion.collectAsStateWithLifecycle()
    val endpointStates = remember(accounts, routes, endpointVersion) { accounts.associate { it.id to viewModel.endpointState(it.id, routes) } }
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun dismissWithUndo(account: StoredAccount) {
        viewModel.dismissImport(account.id)
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                context.getString(R.string.import_pending_dismissed),
                actionLabel = context.getString(R.string.inbox_undo),
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) viewModel.restoreImport(account)
        }
    }
    DetailScaffold(title = stringResource(R.string.settings_accounts_screen_title), onBack = onBack) { padding ->
      Box(Modifier.fillMaxSize().padding(padding)) {
        LazyColumn(Modifier.fillMaxSize()) {
            if (pending.isNotEmpty()) {
                item(key = "pending-import") {
                    PendingImportAccountsSection(
                        accounts = pending,
                        onSignIn = { onOpenAccount(it.id) },
                        onDismiss = { dismissWithUndo(it) },
                    )
                    HorizontalDivider()
                }
            }
            // Only signed-in accounts; [StoredAccount.accountsScreenRows] filters the inert ones out
            // and decides the order. A delegated mailbox is indented under the login whose
            // credential it borrows (#31), and is never offered a sign-out it cannot honour.
            // PINNED BY SharedAccountScreenTest — the accounts list, statement by statement
            items(StoredAccount.accountsScreenRows(accounts), key = { it.account.id }) { row ->
                val account = row.account
                AccountRow(
                    // A shared mailbox's colour seed is its own label, as in the drawer: its
                    // [username] is the LOGIN's address. An ordinary row keeps `username`, whose
                    // colour is already on screen and must not move.
                    seed = if (account.isShared) account.label() else account.username,
                    label = account.label(),
                    // The second line says what the row is: `username` under a shared mailbox is
                    // the LOGIN's address, printing it twice on two rows. As in the drawer (#31).
                    email = if (account.isShared) stringResource(R.string.account_shared) else account.username,
                    isCurrent = account.id == currentId,
                    color = accountColorOf(account.color),
                    indented = row.underLogin,
                    gateway = if (account.isShared || account.authType == AuthType.OAUTH) null else viewModel.gatewayStatus(account.id),
                    endpoint = if (account.isShared || account.authType == AuthType.OAUTH) null else endpointStates[account.id],
                    onClick = {
                        // Never switch to an inert (not-yet-signed-in) account: no credentials,
                        // and it would break the current-account inbox. Still opened, so it can be
                        // signed in from its detail screen.
                        if (account.id != currentId && viewModel.isSignedIn(account.id)) {
                            viewModel.switchTo(account.id)
                            onAccountsChanged()
                        }
                        onOpenAccount(account.id)
                    },
                )
            }
            item {
                Column(Modifier.fillMaxWidth().padding(MailMetrics.s16)) {
                    Button(onClick = onAddAccount, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(MailMetrics.s8))
                        Text(stringResource(R.string.settings_add_account))
                    }
                }
            }
        }
        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
      }
    }
}

@Composable
private fun AccountDetailScreen(
    accountId: String,
    viewModel: AccountsViewModel,
    onBack: () -> Unit,
    onSignedOut: () -> Unit,
    onAccountsChanged: () -> Unit,
) {
    // From the live accounts flow, so signing in an inert account or switching it OAUTH→BASIC
    // re-renders with the new authType. The editor field states below stay keyed by accountId
    // only, so a re-fetch of the same account never resets the user's edits.
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    // What the composer does with the "-- " line, so the preview below shows the real thing (#90).
    val signatureDelimiter by viewModel.signatureDelimiter.collectAsStateWithLifecycle()
    val snapshot = remember(accountId) { viewModel.account(accountId) }
    val account = accounts.firstOrNull { it.id == accountId } ?: snapshot
    if (account == null) {
        DetailScaffold(title = stringResource(R.string.settings_account_screen_title), onBack = onBack) { padding ->
            Box(Modifier.fillMaxSize().padding(padding))
        }
        return
    }

    val isImap = account.protocol == MailProtocol.IMAP
    var accountName by remember(accountId) { mutableStateOf(account.accountName) }
    var server by remember(accountId) { mutableStateOf(account.server) }
    var username by remember(accountId) { mutableStateOf(account.username) }
    var password by remember(accountId) { mutableStateOf("") }
    var saved by remember(accountId) { mutableStateOf(false) }
    // Distinct from [saved]: [dirty] starts false and flips only on an actual edit, so the
    // "unsaved changes" cue and the confirm-on-back do not fire on first open.
    var dirty by remember(accountId) { mutableStateOf(false) }
    fun markEdited() { saved = false; dirty = true }
    // Manual identities only, never the server-merged list: Save writes exactly this list back,
    // so server aliases keep re-merging live and self-correct when the server drops one.
    var identities by remember(accountId) {
        mutableStateOf(
            // Heal pollution from the old merge-on-save fold, and split a legacy signature holding
            // raw HTML in the plain-text field; the HTML is kept in signatureHtml and still sent.
            StoredAccount.normalizeManualIdentities(account.identities, account.serverIdentities)
                .map { it.withSplitSignature() }
                .ifEmpty {
                    if (account.serverIdentities.isEmpty()) {
                        listOf(
                            StoredIdentity(
                                id = java.util.UUID.randomUUID().toString(),
                                name = account.accountName, email = account.username, signature = account.signature,
                            ).withSplitSignature(),
                        )
                    } else {
                        emptyList()
                    }
                },
        )
    }
    var defaultIdentityId by remember(accountId) { mutableStateOf(account.defaultIdentityId) }
    var syncWindow by remember(accountId) { mutableStateOf(account.syncWindow) }
    var colorArgb by remember(accountId) { mutableStateOf(account.color) }
    var notificationsEnabled by remember(accountId) { mutableStateOf(account.notificationsEnabled) }
    var uploadSentCopy by remember(accountId) { mutableStateOf(account.uploadSentCopy) }
    var showOnlySubscribedFolders by remember(accountId) { mutableStateOf(account.showOnlySubscribedFolders) }
    var imapHost by remember(accountId) { mutableStateOf(account.imapHost) }
    var imapPort by remember(accountId) { mutableStateOf(account.imapPort.toString()) }
    var imapSecurity by remember(accountId) { mutableStateOf(account.imapSecurity) }
    var smtpHost by remember(accountId) { mutableStateOf(account.smtpHost) }
    var smtpPort by remember(accountId) { mutableStateOf(account.smtpPort.toString()) }
    var smtpSecurity by remember(accountId) { mutableStateOf(account.smtpSecurity) }
    val cacheCount by viewModel.cacheCount.collectAsStateWithLifecycle()
    val connTest by viewModel.connTest.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Re-read on each recomposition so the sign-in card disappears once a credential is stored.
    val isInert = !viewModel.isSignedIn(accountId)
    val accountSignIn by viewModel.accountSignIn.collectAsStateWithLifecycle()
    // Identities the last Save could not create on the server (#172). Drawn above the Save button
    // that asked, not in the Identities section, which is off screen when the answer lands.
    val identitiesNotCreated by viewModel.identitiesNotCreated.collectAsStateWithLifecycle()
    LaunchedEffect(accountId) {
        viewModel.loadCacheCount(accountId)
        viewModel.clearConnTest()
        viewModel.resetAccountSignIn()
    }
    // On a successful OAuth sign-in, return to the previous screen (the Backup list drops this row).
    LaunchedEffect(accountSignIn) {
        if (accountSignIn is AccountsViewModel.AccountSignIn.Success) {
            onAccountsChanged()
            onBack()
        }
    }
    androidx.compose.runtime.DisposableEffect(accountId) {
        onDispose { viewModel.resetAccountSignIn() }
    }

    val canSave = canSaveAccount(
        username = username, isImap = isImap, server = server,
        imapHost = imapHost, imapPort = imapPort, smtpHost = smtpHost, smtpPort = smtpPort,
    )

    // The screen's one Save action: the bottom button and the exit dialog (#34) both call it, so
    // saving on the way out writes exactly what the button would have written.
    fun saveAccountEdits() {
        val fields = accountSaveFields(identities, account.serverIdentities, defaultIdentityId)
        if (isImap) {
            viewModel.save(
                accountId, accountName, server, username, password,
                signature = fields.signature,
                identities = fields.identities,
                defaultIdentityId = fields.defaultIdentityId,
                imapHost = imapHost, imapPort = imapPort.toIntOrNull(), imapSecurity = imapSecurity,
                smtpHost = smtpHost, smtpPort = smtpPort.toIntOrNull(), smtpSecurity = smtpSecurity,
            )
        } else {
            viewModel.save(
                accountId, accountName, server, username, password,
                signature = fields.signature,
                identities = fields.identities,
                defaultIdentityId = fields.defaultIdentityId,
            )
        }
        password = ""
        saved = true
        dirty = false
        onAccountsChanged()
    }

    // Confirm-on-back so unsaved identity/name/server edits are not silently discarded (#9).
    var confirmExit by remember(accountId) { mutableStateOf(false) }
    fun leaveOrConfirm() { if (dirty) confirmExit = true else onBack() }
    BackHandler(enabled = dirty) { confirmExit = true }
    if (confirmExit) {
        SaveChangesDialog(
            message = stringResource(R.string.settings_save_changes_message),
            canSave = canSave,
            onCancel = { confirmExit = false },
            onDiscard = { confirmExit = false; onBack() },
            onSave = { confirmExit = false; saveAccountEdits(); onBack() },
        )
    }

    DetailScaffold(title = account.label(), onBack = { leaveOrConfirm() }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                // Keeps the form scrollable above the keyboard, so the focused field stays visible
                // while typing (#52).
                .imePadding()
                .verticalScroll(rememberScrollState()),
        ) {
            // An imported account has no stored credential yet. OAuth accounts run a browser
            // device flow here; BASIC accounts use the password field + Save below.
            if (isInert) {
                val oauthProvider = if (account.authType == AuthType.OAUTH) {
                    OAuthProvider.forImapHost(account.imapHost)
                } else {
                    null
                }
                Column(
                    Modifier.fillMaxWidth().padding(MailMetrics.s16),
                    verticalArrangement = Arrangement.spacedBy(MailMetrics.s8),
                ) {
                    Text(
                        stringResource(R.string.account_signin_required),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (account.authType == AuthType.OAUTH && oauthProvider != null) {
                        when (val s = accountSignIn) {
                            AccountsViewModel.AccountSignIn.Idle,
                            is AccountsViewModel.AccountSignIn.Failed -> {
                                if (s is AccountsViewModel.AccountSignIn.Failed) {
                                    Text(
                                        s.message,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                    if (s.offerAppPassword) {
                                        Text(
                                            stringResource(R.string.connect_import_app_password_note),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                } else {
                                    Text(
                                        stringResource(R.string.connect_import_oauth_explainer),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Button(
                                    onClick = { viewModel.startAccountOAuth(accountId) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(stringResource(R.string.connect_import_signin_microsoft)) }
                                if (s is AccountsViewModel.AccountSignIn.Failed && s.offerAppPassword) {
                                    OutlinedButton(
                                        onClick = { viewModel.switchAccountToAppPassword(accountId) },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) { Text(stringResource(R.string.connect_import_use_app_password)) }
                                    AppPasswordHelpLink()
                                }
                            }
                            AccountsViewModel.AccountSignIn.Starting,
                            AccountsViewModel.AccountSignIn.Connecting ->
                                CircularProgressIndicator(Modifier.size(MailMetrics.s24), strokeWidth = MailMetrics.s2)
                            is AccountsViewModel.AccountSignIn.Approval ->
                                InlineDeviceApproval(
                                    s.userCode, s.verificationUri, s.verificationUriComplete,
                                    onCancel = { viewModel.cancelAccountOAuth() },
                                )
                            // Success is handled by the LaunchedEffect above (navigates back).
                            AccountsViewModel.AccountSignIn.Success -> Unit
                        }
                    }
                }
                HorizontalDivider()
            }
            SettingsSection(stringResource(R.string.settings_account_section)) {
                SettingTextField(
                    label = stringResource(R.string.settings_display_name_label),
                    value = accountName,
                    onValueChange = { accountName = it; markEdited() },
                )
            }
            SettingsSection(stringResource(R.string.settings_account_colour_section)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s8),
                    horizontalArrangement = Arrangement.spacedBy(MailMetrics.s12),
                ) {
                    ColourSwatch(color = null, selected = colorArgb == null) {
                        colorArgb = null; viewModel.setColor(accountId, null); onAccountsChanged()
                    }
                    AccountPalette.colors.forEach { swatch ->
                        val argb = swatch.toArgb()
                        ColourSwatch(color = swatch, selected = colorArgb == argb) {
                            colorArgb = argb; viewModel.setColor(accountId, argb); onAccountsChanged()
                        }
                    }
                }
            }
            SettingsSection(stringResource(R.string.settings_account_notifications_section)) {
                // An account neither current nor covered by "Push for all accounts" is watched by
                // nothing. The note appears only where it is exact — this account's own flag on,
                // push-all the single thing missing — else it promises one action for two.
                SettingSwitch(
                    title = stringResource(R.string.settings_account_notifications_title),
                    subtitle = stringResource(R.string.settings_account_notifications_subtitle),
                    checked = notificationsEnabled,
                    onCheckedChange = {
                        notificationsEnabled = it
                        viewModel.setNotificationsEnabled(accountId, it)
                    },
                )
                // isWatched is re-read each recomposition (no remember): neither the current
                // account nor the push-all flag is observable.
                if (PushController.shouldShowUnwatchedNote(account.isLinked, viewModel.isWatched(accountId), notificationsEnabled)) {
                    Text(
                        stringResource(R.string.settings_account_notifications_unwatched_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s4),
                    )
                }
                // Read-only delivery status (#17) — transparency, never a control.
                if (notificationsEnabled) {
                    val appContext = LocalContext.current
                    // statusFor is a plain snapshot, but re-enabling notifications re-arms push
                    // asynchronously: a one-shot read taken during the toggle's recomposition still
                    // sees the old transport (#53), so it is re-read while the line is visible.
                    var status by remember(accountId) {
                        mutableStateOf(PushController.statusFor(appContext, accountId))
                    }
                    LaunchedEffect(accountId, notificationsEnabled) {
                        while (true) {
                            status = PushController.statusFor(appContext, accountId)
                            delay(500)
                        }
                    }
                    val statusText = when (val s = status) {
                        is PushStatus.ViaUnifiedPush ->
                            stringResource(R.string.settings_push_status_up, appLabelOf(appContext, s.distributorPackage))
                        // A date, not a verdict: nothing here can tell a dead relay from one with
                        // nothing to announce (#177). DateUtils localises the span, so the nine
                        // translations carry no units of time of their own.
                        is PushStatus.ViaRelay -> stringResource(
                            R.string.settings_push_status_relay,
                            DateUtils.getRelativeTimeSpanString(s.lastDeliveryMillis),
                        )
                        PushStatus.Direct -> stringResource(R.string.settings_push_status_direct)
                        PushStatus.Connecting -> stringResource(R.string.settings_push_status_connecting)
                        PushStatus.Periodic -> stringResource(R.string.settings_push_status_periodic)
                        PushStatus.NotWatched -> stringResource(R.string.settings_push_status_not_watched)
                    }
                    Text(
                        statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s4),
                    )
                    // The relay block (#177) belongs inside both guards. notificationsEnabled: an
                    // address on an account Sterna is told not to fetch is a trap, since the relay
                    // posts and the wake is ignored. isImap: a JMAP account holds a real
                    // PushSubscription and has no use for a relay.
                    if (isImap) {
                        RelayAddressSection(accountId = accountId, viewModel = viewModel)
                    }
                }
            }
            SettingsSection(stringResource(R.string.settings_server_settings_section)) {
                if (isImap) {
                    SettingTextField(
                        label = stringResource(R.string.settings_imap_server_label),
                        value = imapHost,
                        onValueChange = { imapHost = it; markEdited(); viewModel.clearConnTest() },
                        keyboardType = KeyboardType.Uri,
                    )
                    SettingTextField(
                        label = stringResource(R.string.settings_imap_port_label),
                        value = imapPort,
                        onValueChange = { imapPort = it.filter(Char::isDigit); markEdited() },
                        keyboardType = KeyboardType.Number,
                    )
                    SettingChoiceRow(
                        title = stringResource(R.string.settings_imap_security_title),
                        options = listOf(ConnectionSecurity.TLS, ConnectionSecurity.STARTTLS, ConnectionSecurity.NONE),
                        selected = imapSecurity,
                        optionLabel = { securityLabel(context, it) },
                        onSelect = { imapSecurity = it; markEdited() },
                    )
                    PlaintextSecurityWarning(imapSecurity)
                    SettingTextField(
                        label = stringResource(R.string.settings_smtp_server_label),
                        value = smtpHost,
                        onValueChange = { smtpHost = it; markEdited() },
                        keyboardType = KeyboardType.Uri,
                    )
                    SettingTextField(
                        label = stringResource(R.string.settings_smtp_port_label),
                        value = smtpPort,
                        onValueChange = { smtpPort = it.filter(Char::isDigit); markEdited() },
                        keyboardType = KeyboardType.Number,
                    )
                    SettingChoiceRow(
                        title = stringResource(R.string.settings_smtp_security_title),
                        options = listOf(ConnectionSecurity.TLS, ConnectionSecurity.STARTTLS, ConnectionSecurity.NONE),
                        selected = smtpSecurity,
                        optionLabel = { securityLabel(context, it) },
                        onSelect = { smtpSecurity = it; markEdited() },
                    )
                    PlaintextSecurityWarning(smtpSecurity)
                    // IMAP only: this switches off the APPEND into Sent for a server that files
                    // the copy itself. JMAP has no second write to cut. Written at the toggle.
                    SettingSwitch(
                        title = stringResource(R.string.settings_upload_sent_title),
                        subtitle = stringResource(R.string.settings_upload_sent_subtitle),
                        checked = uploadSentCopy,
                        onCheckedChange = {
                            uploadSentCopy = it
                            viewModel.setUploadSentCopy(accountId, it)
                        },
                    )
                } else {
                    SettingTextField(
                        label = stringResource(R.string.settings_server_url_label),
                        value = server,
                        onValueChange = { server = it; markEdited(); viewModel.clearConnTest() },
                        keyboardType = KeyboardType.Uri,
                    )
                }
                SettingTextField(
                    label = stringResource(R.string.settings_username_label),
                    value = username,
                    onValueChange = { username = it; markEdited() },
                    keyboardType = KeyboardType.Email,
                )
                // OAuth accounts have no password field: their encrypted slot holds the refresh
                // token, which save() would overwrite with whatever is typed here, killing the
                // account. (API-token accounts do type their token into this slot.)
                if (account.authType != AuthType.OAUTH) {
                    // #1693 the password / API token is entered once in Cloud Account (App credentials) and taken from there.
                    val secretLabel = stringResource(
                        if (account.authType == AuthType.API_TOKEN) R.string.settings_api_token_label else R.string.settings_password_label,
                    )
                    CloudSecretField(
                        label = secretLabel,
                        idOrNull = if (username.isBlank()) null else if (account.authType == AuthType.API_TOKEN) CloudSecrets.tokenId(username) else CloudSecrets.passwordId(username),
                        filled = password.isNotBlank(),
                        onTaken = { password = it; markEdited(); viewModel.clearConnTest() },
                        modifier = Modifier.padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s4),
                    )
                }
            }
            if (!isImap && account.authType != AuthType.OAUTH) {
                val gatewayVersion by viewModel.gatewayVersion.collectAsStateWithLifecycle()
                var signInOpen by remember(accountId) { mutableStateOf(false) }
                SettingsSection(stringResource(R.string.gw_title)) {
                    // gatewayVersion is read so the section follows sign-in, sign-out and a test result.
                    key(gatewayVersion) {
                        AccountAuthSection(
                            mode = viewModel.gatewayMode(accountId),
                            status = viewModel.gatewayStatus(accountId),
                            signedInAt = viewModel.gatewayAuth(accountId).signedInAt,
                            onMode = { viewModel.setGatewayMode(accountId, it) },
                            onSignIn = { signInOpen = true },
                            onSignOut = { clearAutheliaWebSession(); viewModel.autheliaSignOut(accountId) },
                            fleetToken = viewModel.gatewayAuth(accountId).fleetToken,
                            onFleetToken = { viewModel.setFleetToken(accountId, it) },
                        )
                    }
                }
                if (signInOpen) {
                    AutheliaSignInDialog(
                        onDismiss = { signInOpen = false },
                        onCaptured = { cookie -> signInOpen = false; viewModel.autheliaSignedIn(accountId, cookie) },
                    )
                }
                // #1090 the endpoints Cloud Account synced for this account (mesh, public, bearer masked) and its policy.
                val endpointVersion by viewModel.endpointVersion.collectAsStateWithLifecycle()
                val syncing by viewModel.endpointSyncing.collectAsStateWithLifecycle()
                val syncMessage by viewModel.endpointMessage.collectAsStateWithLifecycle()
                val routes by com.diegonmarcos.superapp.fleetendpoints.FleetTransport.router.status.collectAsStateWithLifecycle()
                SettingsSection(stringResource(R.string.ep_title)) {
                    key(endpointVersion, routes) {
                        AccountEndpointsSection(
                            endpoint = viewModel.endpointOf(accountId),
                            state = viewModel.endpointState(accountId, routes) ?: com.diegonmarcos.superapp.fleetendpoints.EndpointState.UNKNOWN,
                            syncing = syncing,
                            message = syncMessage,
                            onPolicy = { viewModel.setEndpointPolicy(accountId, it) },
                            onSync = { viewModel.syncEndpoints() },
                        )
                    }
                }
            }
            SettingsSection(stringResource(R.string.settings_protocol_section)) {
                Text(
                    text = if (isImap) {
                        stringResource(R.string.settings_protocol_imap_smtp)
                    } else {
                        stringResource(R.string.settings_protocol_jmap)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s8),
                )
            }
            SettingsSection(stringResource(R.string.settings_identities_section)) {
                Text(
                    stringResource(R.string.settings_identities_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = MailMetrics.s16),
                )
                // Two groups: server identities read-only, manual ones editable and the only ones
                // written back on Save. Never seed the editable list from the merged
                // resolvedIdentities(), which freezes server aliases into the manual field.
                val serverIdentities = StoredAccount.distinctServerIdentities(account.serverIdentities)
                val serverEmails = remember(serverIdentities) {
                    serverIdentities.map { it.email.trim().lowercase() }.toSet()
                }
                // Purely-manual identities: editable entries whose email is not a server address.
                // Entries whose email is one are overrides, surfaced in the server group only.
                val purelyManual = identities.filter { it.email.trim().lowercase() !in serverEmails }
                val totalIdentities = serverIdentities.size + purelyManual.size

                // Only the default sender opens; display only, never saved, so it is re-derived.
                var expandedRows by remember(accountId) {
                    val rows = serverIdentities.map { server ->
                        val emailKey = server.email.trim().lowercase()
                        val overrideId = identities
                            .firstOrNull { it.email.trim().lowercase() == emailKey }?.id
                        IdentityRowRef(overrideId ?: server.id, listOf(server.id))
                    } + purelyManual.map { IdentityRowRef(it.id) }
                    mutableStateOf(setOfNotNull(initialExpandedIdentityId(rows, defaultIdentityId)))
                }

                // Edit a server identity by upserting a manual override for its email, reusing the
                // server id so the #78 default keeps resolving; resolvedIdentities() is
                // manual-first, so the override wins (#79).
                fun overrideServer(server: StoredIdentity, transform: (StoredIdentity) -> StoredIdentity) {
                    val emailKey = server.email.trim().lowercase()
                    val idx = identities.indexOfFirst { it.email.trim().lowercase() == emailKey }
                    val base = (if (idx >= 0) identities[idx] else server).withSplitSignature()
                    val updated = transform(base)
                    identities = if (idx >= 0) {
                        identities.mapIndexed { i, e -> if (i == idx) updated else e }
                    } else {
                        identities + updated
                    }
                    markEdited()
                }

                // --- From your server (email read-only; name + signature editable, #79) ---
                if (serverIdentities.isNotEmpty()) {
                    Text(
                        stringResource(R.string.settings_identities_server_group),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s8),
                    )
                    serverIdentities.forEach { server ->
                        val emailKey = server.email.trim().lowercase()
                        // The manual override's values if one exists, else the server's own, split
                        // so a signature the server sends as HTML is shown as text.
                        val override = identities.firstOrNull { it.email.trim().lowercase() == emailKey }
                        val shown = (override ?: server).withSplitSignature()
                        val name = shown.name
                        val rowId = override?.id ?: server.id
                        val isDefault = defaultIdentityId == rowId || defaultIdentityId == server.id
                        val expanded = rowId in expandedRows
                        Column(Modifier.padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s8)) {
                            // The address heads the row, folded or not: it says whose fields these are.
                            IdentityRowHeader(
                                email = server.email,
                                isDefault = isDefault,
                                signature = signatureStateOf(shown),
                                expanded = expanded,
                                onToggle = { expandedRows = expandedRows.toggleIdentityRow(rowId) },
                            )
                            if (expanded) {
                                // Says why there is no address field here: the server owns it.
                                Text(
                                    stringResource(R.string.settings_identity_from_server),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                // Default is settable on a server identity too (#78). Above the
                                // fields, so it does not read as a property of the signature.
                                DefaultIdentityRadioRow(
                                    selected = isDefault,
                                    onSelect = { defaultIdentityId = rowId; markEdited() },
                                )
                                OutlinedTextField(
                                    value = name,
                                    onValueChange = { v -> overrideServer(server) { it.copy(name = v) } },
                                    label = { Text(stringResource(R.string.settings_display_name_label)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().padding(top = MailMetrics.s8),
                                )
                                // The identity's NAMED signatures (#206), migrated on read from the
                                // single pre-#206 one, so an override that has never been touched
                                // still offers exactly the signature the owner already had.
                                SignatureListEditor(
                                    signatures = shown.resolvedSignatures(),
                                    defaultSignatureId = shown.defaultSignatureId,
                                    delimiter = signatureDelimiter,
                                    onChange = { list ->
                                        overrideServer(server) { it.copy(signatures = list) }
                                    },
                                    onDefaultChange = { id ->
                                        overrideServer(server) { it.copy(defaultSignatureId = id) }
                                    },
                                    newId = { java.util.UUID.randomUUID().toString() },
                                )
                            }
                            HorizontalDivider(Modifier.padding(top = MailMetrics.s12))
                        }
                    }
                }

                // --- Your identities (purely-manual, fully editable) ---
                Text(
                    stringResource(R.string.settings_identities_manual_group),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s8),
                )
                identities.forEachIndexed { index, identity ->
                    // Server-email overrides are rendered in the server group above, not here.
                    if (identity.email.trim().lowercase() in serverEmails) return@forEachIndexed
                    fun update(transform: (StoredIdentity) -> StoredIdentity) {
                        identities = identities.mapIndexed { i, id -> if (i == index) transform(id) else id }
                        markEdited()
                    }
                    val trimmedEmail = identity.email.trim()
                    // Local-only checks: malformed address (blocking) and a soft hint when the
                    // server did not advertise it.
                    val emailInvalid = trimmedEmail.isNotBlank() &&
                        !android.util.Patterns.EMAIL_ADDRESS.matcher(trimmedEmail).matches()
                    val notOnServer = trimmedEmail.isNotBlank() && !emailInvalid &&
                        serverEmails.isNotEmpty() && trimmedEmail.lowercase() !in serverEmails
                    val expanded = identity.id in expandedRows
                    Column(Modifier.padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s8)) {
                        IdentityRowHeader(
                            email = trimmedEmail,
                            isDefault = identity.id == defaultIdentityId,
                            signature = signatureStateOf(identity),
                            expanded = expanded,
                            onToggle = { expandedRows = expandedRows.toggleIdentityRow(identity.id) },
                        )
                        if (expanded) {
                            // Default-identity picker: one radio per account, keyed by the stable id
                            // so it survives server-driven list reordering.
                            DefaultIdentityRadioRow(
                                selected = identity.id == defaultIdentityId,
                                onSelect = { defaultIdentityId = identity.id; markEdited() },
                            )
                            OutlinedTextField(
                                value = identity.name,
                                onValueChange = { v -> update { it.copy(name = v) } },
                                label = { Text(stringResource(R.string.settings_display_name_label)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(top = MailMetrics.s8),
                            )
                            OutlinedTextField(
                                value = identity.email,
                                onValueChange = { v -> update { it.copy(email = v) } },
                                label = { Text(stringResource(R.string.settings_email_address_label)) },
                                singleLine = true,
                                isError = emailInvalid,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                                supportingText = {
                                    Text(
                                        when {
                                            emailInvalid -> stringResource(R.string.settings_email_invalid)
                                            notOnServer -> stringResource(R.string.settings_email_not_server)
                                            else -> stringResource(R.string.settings_email_supporting)
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (emailInvalid) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                },
                                modifier = Modifier.fillMaxWidth().padding(top = MailMetrics.s4),
                            )
                            // The identity's NAMED signatures (#206); see the server group above.
                            SignatureListEditor(
                                signatures = identity.resolvedSignatures(),
                                defaultSignatureId = identity.defaultSignatureId,
                                delimiter = signatureDelimiter,
                                onChange = { list -> update { it.copy(signatures = list) } },
                                onDefaultChange = { id -> update { it.copy(defaultSignatureId = id) } },
                                newId = { java.util.UUID.randomUUID().toString() },
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = MailMetrics.s12),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // Remove is gated so one identity remains across BOTH groups.
                                if (totalIdentities > 1) {
                                    Spacer(Modifier.weight(1f))
                                    TextButton(onClick = {
                                        // Removing the default identity clears the stored choice.
                                        if (identity.id == defaultIdentityId) defaultIdentityId = null
                                        identities = identities.filterIndexed { i, _ -> i != index }
                                        markEdited()
                                    }) {
                                        Text(
                                            stringResource(R.string.settings_remove),
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        }
                        HorizontalDivider(Modifier.padding(top = MailMetrics.s12))
                    }
                }
                if (totalIdentities <= 1) {
                    Text(
                        stringResource(R.string.settings_identities_min_one),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s4),
                    )
                }
                OutlinedButton(
                    onClick = {
                        val added = StoredIdentity(
                            id = java.util.UUID.randomUUID().toString(), name = "", email = "", signature = "",
                        )
                        identities = identities + added
                        // A row you just asked for opens: a new folded blank line would be a dead end.
                        expandedRows = expandedRows + added.id
                        markEdited()
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s8),
                ) {
                    Text(stringResource(R.string.settings_add_identity))
                }
            }
            SettingsSection(stringResource(R.string.settings_sync_section)) {
                SettingChoiceRow(
                    title = stringResource(R.string.settings_messages_to_sync_title),
                    // From `syncWindowChoices()`, never spelled out here: the retired windows
                    // (50 / 200 / 500 / "Everything") still decode, and a hand-written list is how
                    // one of them gets offered again.
                    options = syncWindowChoices(),
                    // The window the account carries, never one picked out of the offer:
                    // substituting the nearest offered value would make the row state a number the
                    // cache does not hold. A retired window shows no ticked circle in the dialog.
                    selected = syncWindow,
                    optionLabel = { syncWindowLabel(context, it) },
                    onSelect = {
                        syncWindow = it
                        viewModel.setSyncWindow(accountId, it)
                        onAccountsChanged()
                    },
                )
                // Here, not in the `if (isImap)` block above: both protocols carry the
                // subscription (IMAP LSUB, JMAP Mailbox.isSubscribed), so inside that branch this
                // would be invisible on every JMAP account (#174).
                SettingSwitch(
                    title = stringResource(R.string.settings_subscribed_folders_title),
                    subtitle = stringResource(R.string.settings_subscribed_folders_subtitle),
                    checked = showOnlySubscribedFolders,
                    onCheckedChange = {
                        showOnlySubscribedFolders = it
                        viewModel.setShowOnlySubscribedFolders(accountId, it)
                    },
                )
            }
            SettingsSection(stringResource(R.string.settings_pgp_section)) {
                PgpAccountSection(accountId = accountId, account = account, viewModel = viewModel)
            }
            SettingsSection(stringResource(R.string.settings_storage_section)) {
                StorageStatRow(stringResource(R.string.settings_cached_messages), "$cacheCount")
                // Before the button: the attachment cache is one flat directory shared by every
                // account, there is no confirmation dialog, and a warning placed after the button
                // is reached too late. `TranslationParityTest` pins this order.
                Text(
                    stringResource(R.string.settings_clear_account_cache_help),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s4),
                )
                OutlinedButton(
                    onClick = { viewModel.clearAccountCache(accountId) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s8),
                ) {
                    Text(stringResource(R.string.settings_clear_account_cache))
                }
            }
            Column(Modifier.fillMaxWidth().padding(MailMetrics.s16), verticalArrangement = Arrangement.spacedBy(MailMetrics.s8)) {
                OutlinedButton(
                    onClick = {
                        viewModel.testConnection(
                            accountId, server, username, password, isImap,
                            imapHost, imapPort.toIntOrNull(), imapSecurity,
                            smtpHost, smtpPort.toIntOrNull(), smtpSecurity,
                        )
                    },
                    enabled = canSave && connTest != AccountsViewModel.ConnTest.Testing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.settings_test_connection))
                }
                when (val t = connTest) {
                    AccountsViewModel.ConnTest.Testing -> Row(
                        modifier = Modifier.padding(vertical = MailMetrics.s4),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MailMetrics.s8),
                    ) {
                        CircularProgressIndicator(Modifier.size(MailMetrics.s18), strokeWidth = MailMetrics.s2)
                        Text(stringResource(R.string.settings_test_connecting), style = MaterialTheme.typography.bodyMedium)
                    }
                    AccountsViewModel.ConnTest.Ok -> Text(
                        stringResource(R.string.settings_test_ok),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = MailMetrics.s4),
                    )
                    is AccountsViewModel.ConnTest.Failed -> Text(
                        stringResource(R.string.settings_test_failed, t.message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = MailMetrics.s4),
                    )
                    else -> Unit
                }
                // Identity/name/server edits persist only on Save, unlike colour and sync (#9).
                if (dirty) {
                    Text(
                        stringResource(R.string.settings_unsaved_changes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(vertical = MailMetrics.s4),
                    )
                }
                // Says what this button owns: four sections above write on tap and leave it nothing
                // to do, so it stays grey, which otherwise reads as a refusal. Above the button:
                // below it, the equal gap to "Sign out" makes it qualify that instead.
                Text(
                    stringResource(R.string.settings_save_scope),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Live only when there is something to write: a Save offered over an untouched form
                // promises an effect it cannot have (#34). The last Save's answer, filtered on
                // this screen's account: one view model serves them all, and another account's
                // refusal names an address absent here.
                identitiesNotCreated.filter { it.accountId == accountId }.forEach { failure ->
                    Text(
                        stringResource(R.string.settings_identity_not_created, failure.email, failure.detail),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = MailMetrics.s4),
                    )
                }
                Button(
                    onClick = { saveAccountEdits() },
                    enabled = canSave && dirty,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (saved) {
                            stringResource(R.string.settings_saved)
                        } else {
                            stringResource(R.string.settings_save)
                        },
                    )
                }
                var confirmSignOut by remember(accountId) { mutableStateOf(false) }
                OutlinedButton(
                    onClick = { confirmSignOut = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.settings_sign_out), color = MaterialTheme.colorScheme.error)
                }
                if (confirmSignOut) {
                    AlertDialog(
                        onDismissRequest = { confirmSignOut = false },
                        title = { Text(stringResource(R.string.settings_sign_out_title)) },
                        text = { Text(stringResource(R.string.settings_sign_out_message)) },
                        confirmButton = {
                            TextButton(onClick = {
                                confirmSignOut = false
                                viewModel.signOut(accountId)
                                onSignedOut()
                            }) {
                                Text(
                                    stringResource(R.string.settings_sign_out),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmSignOut = false }) {
                                Text(stringResource(R.string.settings_cancel))
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * The relay push address block (#177). Sterna runs no relay: it publishes an address the user hands
 */
@Composable
private fun RelayAddressSection(accountId: String, viewModel: AccountsViewModel) {
    // Read once per visit, never on the tick below: it is a binder call
    // (packageManager.queryBroadcastReceivers) for an answer that cannot change while on top.
    val distributorInstalled = remember(accountId) { viewModel.distributorInstalled() }
    // The rest re-reads on the same 500 ms tick as the status line above, since the distributor
    // answers asynchronously. SharedPreferences only; no DataStore, runBlocking or PackageManager
    // may join it.
    var relay by remember(accountId) { mutableStateOf(viewModel.relayAddress(accountId)) }
    LaunchedEffect(accountId) {
        while (true) {
            delay(500)
            relay = viewModel.relayAddress(accountId)
        }
    }
    val title = stringResource(R.string.settings_push_relay_title)
    val endpoint = relay.endpoint
    if (!distributorInstalled) {
        RelayAddressNote(title, stringResource(R.string.settings_push_relay_no_distributor))
    } else if (endpoint != null) {
        SettingCopyableRow(
            label = title,
            value = endpoint,
            copiedMessage = stringResource(R.string.settings_push_relay_copied),
        )
        // The same sentence as the empty state: the address is about to leave for another app.
        Text(
            stringResource(R.string.settings_push_relay_explain),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s4),
        )
        TextButton(
            onClick = {
                viewModel.dropRelayAddress(accountId)
                relay = viewModel.relayAddress(accountId)
            },
            modifier = Modifier.padding(horizontal = MailMetrics.s8),
        ) { Text(stringResource(R.string.settings_push_relay_remove)) }
    } else if (relay.requested) {
        RelayAddressNote(title, stringResource(R.string.settings_push_relay_waiting))
        // This state can be final, so it carries the way out: for an account outside the watched
        // set the tap above is the only ensureRegistered of its life.
        TextButton(
            onClick = {
                viewModel.dropRelayAddress(accountId)
                relay = viewModel.relayAddress(accountId)
            },
            modifier = Modifier.padding(horizontal = MailMetrics.s8),
        ) { Text(stringResource(R.string.settings_push_relay_remove)) }
    } else {
        RelayAddressNote(title, stringResource(R.string.settings_push_relay_explain))
        TextButton(
            onClick = {
                viewModel.requestRelayAddress(accountId)
                relay = viewModel.relayAddress(accountId)
            },
            modifier = Modifier.padding(horizontal = MailMetrics.s8),
        ) { Text(stringResource(R.string.settings_push_relay_get)) }
    }
}

/** The block's heading and its one sentence, for the three states that have no address to show. */
@Composable
private fun RelayAddressNote(title: String, note: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = MailMetrics.s16, end = MailMetrics.s16, top = MailMetrics.s12),
    )
    Text(
        note,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s4),
    )
}

/** Inline device-approval panel for the sign-in card, mirroring ConnectScreen's private copy. */
@Composable
private fun InlineDeviceApproval(
    userCode: String,
    verificationUri: String,
    verificationUriComplete: String?,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val copiedMsg = stringResource(R.string.connect_oauth_code_copied)
    // As in the connect screen's copy: two approval windows for one sign-in leaves the user
    // completing one while the other waits for something that will never come.
    val leaveOnce = rememberLeaveOnce()
    Text(stringResource(R.string.connect_oauth_step1), style = MaterialTheme.typography.bodyMedium)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                clipboard.setText(AnnotatedString(userCode))
                Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
            },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(userCode, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.width(MailMetrics.s8))
        Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.connect_oauth_copy_code))
    }
    Button(
        onClick = {
            val target = verificationUriComplete ?: verificationUri
            leaveOnce {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }.isSuccess
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.connect_oauth_open_browser)) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MailMetrics.s12)) {
        CircularProgressIndicator(modifier = Modifier.size(MailMetrics.s20), strokeWidth = MailMetrics.s2)
        TextButton(onClick = onCancel) { Text(stringResource(R.string.connect_oauth_cancel)) }
    }
}

/** Per-account OpenPGP configuration. With no provider installed the switches degrade to
 *  turn-off-only, so they never become stuck on after the provider is uninstalled (#35). */
@Composable
private fun PgpAccountSection(
    accountId: String,
    account: StoredAccount,
    viewModel: AccountsViewModel,
) {
    val context = LocalContext.current
    // The F-Droid install link below leaves for a store page; opening it twice stacks two.
    val leaveOnce = rememberLeaveOnce()
    val pgpAvailable by viewModel.pgpAvailable.collectAsStateWithLifecycle()
    val pgpSetup by viewModel.pgpSetup.collectAsStateWithLifecycle()
    val pgpProviders by viewModel.pgpProviders.collectAsStateWithLifecycle()
    val pgpProviderInUse by viewModel.pgpProviderInUse.collectAsStateWithLifecycle()
    // The live account, so the switches reflect the async key chooser and setPgp ([account] is a
    // one-shot snapshot).
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val liveAccount = accounts.firstOrNull { it.id == accountId } ?: account
    LaunchedEffect(accountId) { viewModel.refreshPgpAvailable() }

    // OpenKeychain's key chooser / permission dialog round-trip.
    val interactionLauncher = rememberPgpInteractionLauncher { data ->
        if (data != null) viewModel.choosePgpKey(accountId, data) else viewModel.clearPgpSetup()
    }
    LaunchedEffect(pgpSetup) {
        (pgpSetup as? AccountsViewModel.PgpSetup.NeedsInteraction)?.let {
            viewModel.clearPgpSetup()
            interactionLauncher(it.pendingIntent)
        }
    }

    if (!pgpAvailable) {
        Text(
            stringResource(R.string.settings_pgp_missing_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = MailMetrics.s16),
        )
        OutlinedButton(
            onClick = { leaveOnce { openUrl(context, OPENKEYCHAIN_FDROID_URL) } },
            modifier = Modifier.fillMaxWidth().padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s8),
        ) {
            Text(stringResource(R.string.settings_pgp_install))
        }
        // No early return: the switches below must stay reachable so a configuration left on
        // before the provider was uninstalled can still be turned off (#35).
    }

    // Only when there is something to pick (#151). No fallback for a null provider-in-use:
    // `?: pgpProviders.first()` would tick a provider chosen by the sort order while the engine
    // binds another.
    val pgpInUse = pgpProviderInUse
    if (pgpProviders.size >= 2 && pgpInUse != null) {
        SettingChoiceRow(
            title = stringResource(R.string.settings_pgp_provider_title),
            options = pgpProviders,
            selected = pgpInUse,
            optionLabel = { pkg -> pgpProviderLabel(context, pkg) },
            onSelect = { viewModel.setPgpProvider(it) },
        )
    }

    // Remembered on the package: reading a provider's own name is a package-manager query plus
    // loading a third-party APK's resources, and bare it would run on every recomposition.
    val pgpInUseLabel = remember(pgpInUse) { pgpInUse?.let { pgpProviderLabel(context, it) } }

    SettingSwitch(
        title = stringResource(R.string.settings_pgp_enable_title),
        // With a single installed app the provider row is hidden, so this subtitle is the only place
        // the app in use is named (#151).
        subtitle = if (pgpAvailable) {
            stringResource(
                R.string.settings_pgp_enable_subtitle,
                pgpSubtitleProviderName(pgpInUseLabel),
            )
        } else {
            stringResource(R.string.settings_pgp_provider_required)
        },
        checked = liveAccount.pgpEnabled,
        enabled = pgpAvailable || liveAccount.pgpEnabled,
        onCheckedChange = { enabled ->
            if (enabled && liveAccount.pgpSignKeyId == 0L) {
                // First enable: pick the signing key (enables on success).
                viewModel.choosePgpKey(accountId)
            } else {
                viewModel.setPgp(accountId, enabled, liveAccount.pgpEncryptByDefault)
            }
        },
    )
    if (liveAccount.pgpEnabled) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.settings_pgp_key_title),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = if (liveAccount.pgpSignKeyId != 0L) {
                        "0x%016X".format(liveAccount.pgpSignKeyId)
                    } else {
                        stringResource(R.string.settings_pgp_key_none)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(
                onClick = { viewModel.choosePgpKey(accountId) },
                enabled = pgpAvailable,
            ) {
                Text(stringResource(R.string.settings_pgp_choose_key))
            }
        }
        SettingSwitch(
            title = stringResource(R.string.settings_pgp_encrypt_default_title),
            subtitle = stringResource(
                if (pgpAvailable) {
                    R.string.settings_pgp_encrypt_default_subtitle
                } else {
                    R.string.settings_pgp_provider_required
                },
            ),
            checked = liveAccount.pgpEncryptByDefault,
            enabled = pgpAvailable || liveAccount.pgpEncryptByDefault,
            // Persisting the flag is a pure store write; it needs no provider.
            onCheckedChange = { viewModel.setPgp(accountId, true, it) },
        )
    }
    (pgpSetup as? AccountsViewModel.PgpSetup.Failed)?.let { failed ->
        Text(
            failed.message ?: stringResource(R.string.settings_pgp_error),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s4),
        )
    }
}

/** The OpenPGP app's own name ([pgpProviderDisplayName]). A package-manager round trip: remember it. */
private fun pgpProviderLabel(context: Context, packageName: String): String =
    pgpProviderDisplayName(
        OpenPgpProviderUtil.getOpenPgpProviderName(context.packageManager, packageName),
        packageName,
    )

/**
 * What an OpenPGP app is called on screen when it declares no name of its own: its package name.
 */
internal fun pgpProviderDisplayName(declaredName: String?, packageName: String): String =
    declaredName ?: packageName

/** What the "Use OpenPGP" subtitle says mail is signed, encrypted and read with — with a single
 * installed app, the only place on screen that names the app in use (#151). [label] is never
 *  null in production; the fallback readers actually meet is [pgpProviderDisplayName]'s. */
internal fun pgpSubtitleProviderName(label: String?): String = label ?: "OpenPGP"

/** Held together with `settings_pgp_install`, which names OpenKeychain in all nine languages: the
 *  button may name one app precisely because it opens that app's page. */
internal const val OPENKEYCHAIN_FDROID_URL =
    "https://f-droid.org/packages/org.sufficientlysecure.keychain/"

/** What "None" costs, shown under the security selector while it is the choice. The option stays: a
 *  loopback proxy on 127.0.0.1 is a legitimate cleartext target. No dialogue, no confirmation. */
@Composable
private fun PlaintextSecurityWarning(security: ConnectionSecurity) {
    if (security != ConnectionSecurity.NONE) return
    Text(
        stringResource(R.string.settings_security_none_warning),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s4),
    )
}

private fun securityLabel(context: Context, security: ConnectionSecurity): String = when (security) {
    ConnectionSecurity.TLS -> context.getString(R.string.settings_security_tls)
    ConnectionSecurity.STARTTLS -> context.getString(R.string.settings_security_starttls)
    ConnectionSecurity.NONE -> context.getString(R.string.settings_security_none)
}

/** Vacation responder: a server-side auto-reply (JMAP VacationResponse) for the current account.
 *  Network-backed, so the screen carries loading / saving / error states and an explicit Save. */
@Composable
private fun VacationScreen(
    onBack: () -> Unit,
    viewModel: VacationViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Confirm-on-back (#34); both doors go through it, the scaffold's arrow and the system gesture.
    var confirmExit by remember { mutableStateOf(false) }
    // Saving from the dialog is a network round-trip, so the screen leaves only once the server
    // has taken it; a refusal keeps the screen and its error in front of the user.
    var leaveAfterSave by remember { mutableStateOf(false) }
    LaunchedEffect(leaveAfterSave, state.saving, state.dirty, state.errorKind) {
        if (!leaveAfterSave) return@LaunchedEffect
        when (pendingExitStep(state.saving, state.dirty, failed = state.errorKind != null)) {
            PendingExit.LEAVE -> { leaveAfterSave = false; onBack() }
            PendingExit.STAY -> leaveAfterSave = false
            PendingExit.WAIT -> Unit
        }
    }
    BackHandler(enabled = state.dirty) { confirmExit = true }
    if (confirmExit) {
        SaveChangesDialog(
            message = stringResource(R.string.settings_save_changes_message_generic),
            canSave = !state.saving,
            onCancel = { confirmExit = false },
            onDiscard = { confirmExit = false; onBack() },
            onSave = { confirmExit = false; leaveAfterSave = true; viewModel.save() },
        )
    }

    DetailScaffold(
        title = stringResource(R.string.settings_vacation_screen_title),
        onBack = { if (state.dirty) confirmExit = true else onBack() },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> LoadingRing(Modifier.align(Alignment.Center))
                state.noAccount -> VacationNote(stringResource(R.string.settings_vacation_no_account))
                !state.supported -> VacationNote(stringResource(R.string.settings_vacation_unsupported))
                state.errorKind == VacationError.LOAD -> VacationNote(
                    stringResource(R.string.settings_vacation_load_error, state.errorDetail),
                    onRetry = viewModel::load,
                )
                else -> VacationForm(state, viewModel)
            }
        }
    }
}

@Composable
private fun BoxScope.VacationNote(text: String, onRetry: (() -> Unit)? = null) {
    Column(
        modifier = Modifier.align(Alignment.Center).padding(MailMetrics.s32),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onRetry != null) {
            // Filled Button to match every other error-recovery "Retry" (inbox, message, filters).
            Button(onClick = onRetry, modifier = Modifier.padding(top = MailMetrics.s16)) {
                Text(stringResource(R.string.settings_vacation_retry))
            }
        }
    }
}

@Composable
private fun VacationForm(state: VacationUiState, viewModel: VacationViewModel) {
    // imePadding before verticalScroll: the keyboard shrinks the scrolling viewport instead of
    // covering it, so the focused subject/message field stays visible while typing (#52).
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
        if (state.accountLabel.isNotBlank()) {
            Text(
                stringResource(R.string.settings_vacation_account, state.accountLabel),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s8),
            )
        }
        // In the head of the screen, not above Save: it is about the state the account is in, not
        // about the write being prepared. vacationFilterLine() picks which of the three sentences.
        vacationFilterLine(state.filterWarning, responderEnabled = state.enabled)?.let { line ->
            Text(
                when (line) {
                    // Here, and not on the filters screen, the sentence names the remedy: Save
                    // there, which has no other reason to be pressed. Text and not a button:
                    // taking a script back over belongs to the screen that warns in red.
                    VacationFilterLine.RULES_NOT_RUNNING_WITH_REMEDY -> stringResource(
                        R.string.settings_vacation_filters_not_running,
                        stringResource(R.string.inbox_settings),
                        stringResource(R.string.settings_filters_title),
                    )
                    // Switch on: the rules are stopped because the responder is running, so that
                    // remedy would undo what this screen was just used for. The fact, and no path.
                    VacationFilterLine.RULES_NOT_RUNNING_FACT ->
                        stringResource(R.string.settings_filters_not_running)
                    VacationFilterLine.RESPONDER_WILL_SUSPEND_RULES ->
                        stringResource(R.string.settings_vacation_suspends_filters)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s4),
            )
        }
        SettingsSection(stringResource(R.string.settings_vacation_section)) {
            SettingSwitch(
                title = stringResource(R.string.settings_vacation_enabled_title),
                subtitle = stringResource(R.string.settings_vacation_enabled_subtitle),
                checked = state.enabled,
                onCheckedChange = viewModel::setEnabled,
            )
            SettingTextField(
                label = stringResource(R.string.settings_vacation_subject_label),
                value = state.subject,
                onValueChange = viewModel::setSubject,
            )
            OutlinedTextField(
                value = state.message,
                onValueChange = viewModel::setMessage,
                label = { Text(stringResource(R.string.settings_vacation_message_label)) },
                minLines = 4,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s4),
            )
        }
        SettingsSection(stringResource(R.string.settings_vacation_period_section)) {
            VacationDateRow(
                label = stringResource(R.string.settings_vacation_from_label),
                millis = state.fromDate,
                onPick = viewModel::setFromDate,
            )
            VacationDateRow(
                label = stringResource(R.string.settings_vacation_to_label),
                millis = state.toDate,
                onPick = viewModel::setToDate,
            )
        }
        if (state.errorKind == VacationError.INVALID_DATES) {
            Text(
                stringResource(R.string.settings_vacation_invalid_dates),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s4),
            )
        }
        if (state.errorKind == VacationError.SAVE) {
            Text(
                stringResource(R.string.settings_vacation_save_error, state.errorDetail),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s4),
            )
        }
        Button(
            onClick = viewModel::save,
            // Nothing to push until a field actually differs from what the server holds (#34).
            enabled = !state.saving && state.dirty,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s16),
        ) {
            if (state.saving) {
                CircularProgressIndicator(
                    modifier = Modifier.width(MailMetrics.s20),
                    strokeWidth = MailMetrics.s2,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(stringResource(R.string.settings_vacation_save))
            }
        }
        if (state.savedTick > 0) {
            Text(
                stringResource(R.string.settings_vacation_saved),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = MailMetrics.s16).align(Alignment.CenterHorizontally),
            )
        }
        Spacer(Modifier.height(MailMetrics.s24))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VacationDateRow(label: String, millis: Long?, onPick: (Long?) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showPicker = true }
            .padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                millis?.let(::formatVacationDate) ?: stringResource(R.string.settings_vacation_date_unset),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (millis != null) {
            IconButton(onClick = { onPick(null) }) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.settings_vacation_clear_date))
            }
        }
    }
    if (showPicker) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = millis)
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    onPick(pickerState.selectedDateMillis)
                    showPicker = false
                }) { Text(stringResource(R.string.settings_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

/** Format a UTC-midnight epoch-millis as a localized date (follows the app language). */
private fun formatVacationDate(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
        .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

/** Shared scaffold for the hub and detail screens: a [LargeTopAppBar] that collapses on scroll,
 *  matching the inbox pattern (DESIGN.md). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    // 16dp mirrors the platform's start inset (#146). No maxLines, no overflow,
                    // no softWrap = false: this title is the account address when the name field is
                    // empty, and an ellipsised address is unreadable.
                    Text(
                        title,
                        modifier = Modifier.padding(end = MailMetrics.s16),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        content = content,
    )
}

/** The always-visible head of an identity row: the address, the "default sender" badge, and while
 *  folded what the signature holds. Shared by both groups so a folded row always looks the same. */
@Composable
private fun IdentityRowHeader(
    email: String,
    isDefault: Boolean,
    signature: SignatureState,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = MailMetrics.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // A row added but not filled in yet still needs something to tap.
                    email.ifBlank { stringResource(R.string.settings_identity_new) },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isDefault) {
                    Spacer(Modifier.width(MailMetrics.s8))
                    Text(
                        stringResource(R.string.settings_identity_default_sender),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = MailMetrics.s6, vertical = MailMetrics.s2),
                    )
                }
            }
            if (!expanded) {
                Text(
                    stringResource(
                        when (signature) {
                            SignatureState.HTML -> R.string.settings_identity_signature_html
                            SignatureState.TEXT -> R.string.settings_identity_signature_text
                            SignatureState.NONE -> R.string.settings_identity_signature_none
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = stringResource(
                if (expanded) R.string.settings_identity_collapse else R.string.settings_identity_expand,
            ),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * What the signature will look like in a message, delimiter included (#90). Derived from the same
 */

/** The "Default sender" identity radio and its caption (#78). Shared by the server and manual
 *  groups, so either can be chosen as the composer's pre-selection. */
@Composable
private fun DefaultIdentityRadioRow(selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = MailMetrics.s8)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(MailMetrics.s8))
        Column {
            Text(
                stringResource(R.string.settings_identity_default_sender),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.settings_identity_default_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One outcome-framed delivery choice (radio + explanation), issue #17. */
@Composable
internal fun DeliveryModeOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s10),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(MailMetrics.s16))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OfflineSection(viewModel: SettingsViewModel) {
    val window by viewModel.offlineWindow.collectAsStateWithLifecycle()
    val limit by viewModel.offlineAttachmentLimit.collectAsStateWithLifecycle()
    val lastSync by viewModel.offlineLastSync.collectAsStateWithLifecycle()
    val bytes by viewModel.offlineCacheBytes.collectAsStateWithLifecycle()
    val context = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(lastSync) { viewModel.refreshOfflineCacheSize() }
    SettingsSection(stringResource(R.string.settings_offline_section)) {
        SettingChoiceRow(
            title = stringResource(R.string.settings_offline_window_title),
            options = app.sterna.core.data.mail.OfflineWindow.entries.toList(),
            selected = window,
            optionLabel = { offlineWindowLabel(context, it) },
            onSelect = viewModel::setOfflineWindow,
        )
        SettingChoiceRow(
            title = stringResource(R.string.settings_offline_attachments_title),
            options = app.sterna.core.data.mail.OfflineAttachmentLimit.entries.toList(),
            selected = limit,
            optionLabel = { offlineAttachmentLabel(context, it) },
            onSelect = viewModel::setOfflineAttachmentLimit,
        )
        Text(
            stringResource(
                R.string.settings_offline_status,
                android.text.format.Formatter.formatShortFileSize(context, bytes),
                if (lastSync > 0) {
                    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
                        .format(java.util.Date(lastSync))
                } else {
                    stringResource(R.string.settings_offline_never)
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s8),
        )
    }
}

private fun offlineWindowLabel(context: Context, w: app.sterna.core.data.mail.OfflineWindow): String = when (w) {
    app.sterna.core.data.mail.OfflineWindow.ONE -> context.getString(R.string.settings_offline_window_1)
    app.sterna.core.data.mail.OfflineWindow.THREE -> context.getString(R.string.settings_offline_window_3)
    app.sterna.core.data.mail.OfflineWindow.SIX -> context.getString(R.string.settings_offline_window_6)
    app.sterna.core.data.mail.OfflineWindow.TWELVE -> context.getString(R.string.settings_offline_window_12)
    app.sterna.core.data.mail.OfflineWindow.ALL -> context.getString(R.string.settings_offline_window_all)
}

private fun offlineAttachmentLabel(context: Context, l: app.sterna.core.data.mail.OfflineAttachmentLimit): String = when (l) {
    app.sterna.core.data.mail.OfflineAttachmentLimit.NONE -> context.getString(R.string.settings_offline_attachments_none)
    app.sterna.core.data.mail.OfflineAttachmentLimit.MB_5 -> context.getString(R.string.settings_offline_attachments_5)
    app.sterna.core.data.mail.OfflineAttachmentLimit.MB_25 -> context.getString(R.string.settings_offline_attachments_25)
    app.sterna.core.data.mail.OfflineAttachmentLimit.MB_100 -> context.getString(R.string.settings_offline_attachments_100)
}
