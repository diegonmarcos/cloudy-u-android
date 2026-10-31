package com.diegonmarcos.clouddrive

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.diegonmarcos.clouddrive.sync.LocalGitPageMemory
import androidx.compose.runtime.CompositionLocalProvider
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import com.diegonmarcos.cloudlib.gitsync.GitCredentialStore
import com.diegonmarcos.superapp.crash.SafeEncryptedPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import com.diegonmarcos.superapp.bottomnav.FleetChrome
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.diegonmarcos.clouddrive.backups.MirrorRunner
import com.diegonmarcos.clouddrive.configs.ConfigsScreen
import com.diegonmarcos.clouddrive.files.FilesController
import com.diegonmarcos.clouddrive.files.FilesScreen
import com.diegonmarcos.clouddrive.files.ExplorerScreen
import com.diegonmarcos.clouddrive.manage.ManageScreen
import com.diegonmarcos.clouddrive.agents.AgentsScreen
import com.diegonmarcos.clouddrive.ai.AiScreen
import com.diegonmarcos.clouddrive.files.FilesUiState
import com.diegonmarcos.clouddrive.files.Places
import com.diegonmarcos.clouddrive.home.HomeScreen
import com.diegonmarcos.clouddrive.sync.GitSyncCoordinator
import com.diegonmarcos.clouddrive.sync.RcloneCoordinator
import com.diegonmarcos.clouddrive.sync.GitPageScreen
import com.diegonmarcos.clouddrive.sync.MountsPageScreen
import com.diegonmarcos.clouddrive.storage.StorageCoordinator
import com.diegonmarcos.clouddrive.sync.FsyncPageScreen
import com.diegonmarcos.clouddrive.startup.StartupGate
import com.diegonmarcos.clouddrive.startup.StartupGuard
import com.diegonmarcos.superapp.crash.CrashReports
import com.diegonmarcos.clouddrive.startup.StartupState
import com.diegonmarcos.clouddrive.startup.StoreStartupController
import com.diegonmarcos.cloudlib.sharedstore.StoreAccess
import com.diegonmarcos.clouddrive.ui.DriveShell
import com.diegonmarcos.clouddrive.ui.DriveTheme
import com.diegonmarcos.clouddrive.ui.EmptyState
import com.diegonmarcos.clouddrive.ui.IconCatalog
import com.diegonmarcos.clouddrive.viewer.ImageViewerActivity
import com.diegonmarcos.clouddrive.volumes.VolumesScreen
import com.diegonmarcos.superapp.updater.Updater
import java.io.File
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext

/**
 * #579 the chrome's host: a Compose activity that renders [DriveShell] and implements
 * [DriveActions] for the screens — the result launchers (SAF tree grant, engines), the
 * FileProvider hand-offs, the system intents. Nothing here draws; nothing in a screen
 * builds an Intent.
 */
class MainActivity : ComponentActivity(), DriveActions {
    private val gitPage: com.diegonmarcos.clouddrive.sync.GitPageViewModel by viewModels()
    /** #1289 the shell's openPath, set by the composition; [openInFiles] reveals a folder through it. */
    private var openInFilesHook: ((String) -> Unit)? = null
    override fun openInFiles(path: String) { openInFilesHook?.invoke(path) }

    private lateinit var prefs: DrivePrefs
    /** #730 the all-files grant as last seen, so onResume can tell the moment it ARRIVES. */
    private var hadStorageAccess = false
    private var filesController: FilesController? = null

    private val openTreeLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? -> persistTreeGrant(uri) }
    /** #1375 the per-volume SAF grant (StorageVolume.createOpenDocumentTreeIntent) for a drive without all-files access. */
    private val volumeTreeLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r -> persistTreeGrant(r.data?.data) }
    /** #1375 a folder to open Explorer on, handed over by UsbDriveActivity (the drive just plugged in). */
    private val pendingOpen = mutableStateOf<String?>(null)

    /** The engines come back with a path to reveal in the active Files pane (#567 push 5). */
    private val engineLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val path = result.data?.getStringExtra(EngineActivity.RESULT_PATH) ?: return@registerForActivityResult
        filesController?.reveal(path)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // #1106 CRASH-SAFE STARTUP: nothing below may take the app down. "Retry in safe mode" (libs:crash's
        // report screen) skips the optional initialisers; a report nobody has seen yet is shown before
        // anything else runs. #1122 the handler, the report file and the screen are libs:crash's.
        StartupGuard.safeMode = intent?.getBooleanExtra(CrashReports.EXTRA_SAFE_MODE, false) == true
        if (!StartupGuard.safeMode && CrashReports.unseen(CrashReports.dir(this)).isNotEmpty()) {
            CrashReports.showScreen(this, clearTask = true)
            finish()
            return
        }
        try {
            startup()
        } catch (t: Throwable) {
            CrashReports.report(this, "MainActivity.onCreate", t)
            CrashReports.showScreen(this, clearTask = true)
            finish()
            return
        }
        pendingOpen.value = intent?.getStringExtra(com.diegonmarcos.clouddrive.usb.UsbDriveActivity.EXTRA_OPEN_PATH)
        // #603 the shared store's first-run seed (build.json::storage.seed): the declared PUBLIC
        // repositories, cloned shallow into the store the first time it does not hold them.
        // (StoreSeedWorker.schedule guards itself and no-ops in safe mode.)
        StoreSeedWorker.schedule(this)
        // A phase failed but the app is up: show the trace over it (Close returns to the app).
        if (StartupGuard.failureCount() > 0) CrashReports.showScreen(this, clearTask = false)
    }

    /** Every initialiser, each behind [guarded]: one failing phase is recorded and the rest still run. */
    private fun startup() {
        guarded("FleetChrome") {
            FleetChrome.apply(this)
        }
        // cloud-shared-store: saved sync/clone paths move off the old location, once (idempotent).
        guarded("JobPathMigration", optional = true) {
            JobPathMigration.run(this)
        }
        guarded("DataMigration", optional = true) {
            DataMigration.onLaunch(this)
        }
        // Mount keeper (build.json::mount_keeper): re-attach to live mounts, restart the keeper if mounts
        // were on. Never reconnects a healthy mount; only the keeper reopens a dead one, with backoff.
        guarded("MountKeeper", optional = true) {
            com.diegonmarcos.clouddrive.mounts.MountKeeper.onAppOpen(this)
        }
        guarded("system bars") {
            WindowCompat.getInsetsController(window, window.decorView).apply {
                // Dark page top and bottom (Theme.CloudDrive, #336/#337) → light system-bar glyphs.
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
        prefs = DrivePrefs(this)
        // #603 the Files tab is a manager over ALL of shared storage, so all-files access is asked at startup.
        // #1372 it is asked by libs:perms' first-run sheet (build.json perms.first_run "libs:perms": All files access
        // listed with its reason, Grant all and Later), not by opening the bare settings page on every launch; the
        // Files tab's own grant button (requestStorageAccess) still opens that page directly.
        hadStorageAccess = Places.hasAllFilesAccess(this)
        guarded("engines") {
            EngineActivity.declareFromBuild(this)
        }
        // #1356 the Git page's state is activity-scoped (survives a bottom-nav switch) and saved with the activity (survives process death).
        setContent { CompositionLocalProvider(LocalGitPageMemory provides gitPage.store) { DriveTheme { Root(); DataMigration.Dialog() } } }
        guarded("Updater", optional = true) {
            Updater.start(this)
        }
        // #575 the GitSync scheduler: repositories that opted in sync in the background.
        guarded("GitSyncWorker", optional = true) {
            GitSyncWorker.schedule(this)
        }
        // #1084 the Unison pairs that declare a schedule tick on the same WorkManager.
        guarded("UnisonSyncWorker", optional = true) {
            UnisonSyncWorker.schedule(this)
        }
    }

    private fun guarded(phase: String, optional: Boolean = false, block: () -> Unit) {
        StartupGuard.step(this, phase, optional, block)
    }

    /**
     * #730 the fresh-phone path: onCreate scheduled the seed and sent the user to the all-files
     * toggle; they come back HERE with the grant. The seed's pass that ran without it is waiting
     * out a retry back-off, so it is replaced now rather than leaving the store empty meanwhile.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(com.diegonmarcos.clouddrive.usb.UsbDriveActivity.EXTRA_OPEN_PATH)?.let { pendingOpen.value = it }
    }

    override fun onResume() {
        super.onResume()
        val has = Places.hasAllFilesAccess(this)
        if (has && !hadStorageAccess) StoreSeedWorker.kick(this)
        hadStorageAccess = has
    }

    @Composable
    private fun Root() {
        val scope = rememberCoroutineScope()
        val ctx = LocalContext.current
        var hasAccess by remember { mutableStateOf(Places.hasAllFilesAccess(ctx)) }
        // Re-read the grant when the user comes back from the system settings screen.
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(lifecycle) {
            val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) hasAccess = Places.hasAllFilesAccess(ctx) }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
        val saved = rememberSaveable { mutableStateOf("") }
        val files = remember {
            val snap = prefs.snapshot.value
            val (store, external) = Places.initialLocations()
            val initial = FilesUiState.decode(saved.value) ?: FilesUiState.initial(store, external, snap.defaultSort, snap.showHidden, snap.dualPane)
            FilesController(applicationContext, initial, scope, prefs).also { filesController = it }
        }
        val filesState by files.state.collectAsState()
        LaunchedEffect(filesState) { saved.value = filesState.encode() }
        // #1106 built OFF the main thread: GitCredentialStore opens Keystore-backed prefs (a slow, failure-prone
        // call that used to run here, inside Compose, and killed the app). SafeEncryptedPrefs never throws; when it
        // had to reset the store the user is told below.
        var gitOrNull by remember { mutableStateOf<GitSyncCoordinator?>(null) }
        var gitReset by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            val (made, reset) = withContext(Dispatchers.IO) {
                GitSyncCoordinator(applicationContext, scope) to SafeEncryptedPrefs.wasReset(applicationContext, GitCredentialStore.FILE)
            }
            gitOrNull = made
            gitReset = reset
            // #1313 app start, coordinator ready: stale temp packs out of every objects/pack (IO, silent when none).
            made.sweepStalePacks()
        }
        val git = gitOrNull
        val rclone = remember { RcloneCoordinator(applicationContext, scope, prefs) }
        val storage = remember { StorageCoordinator(applicationContext, scope, rclone, prefs) }
        val mirrors = remember { MirrorRunner(scope, prefs) }
        val rcloneVersion by rclone.version.collectAsState()
        // #603/#609 a screen may ask for another tab (an Apps tile's route, a Home quick action):
        // the request is a tab id and, optionally, a Sync or Configs sub-page id, applied once
        // by the shell and by SyncScreen/ConfigsScreen and then cleared.
        // #1106 safe mode opens the file list.
        var jumpTab by remember { mutableStateOf<String?>(if (StartupGuard.safeMode) Declarations.SECTION_EXPLORER else null) }
        var jumpPage by remember { mutableStateOf<String?>(null) }
        val jump: (String, String) -> Unit = { tab, page -> jumpPage = page.ifBlank { null }; jumpTab = tab }
        val openPath: (String) -> Unit = { path -> filesController?.reveal(path); jumpPage = Declarations.PAGE_LOCAL; jumpTab = Declarations.SECTION_EXPLORER }
        openInFilesHook = openPath
        val usbOpen by pendingOpen
        LaunchedEffect(usbOpen) { usbOpen?.let { openPath(it); pendingOpen.value = null } }
        val syncAll: () -> Unit = { gitOrNull?.let { g -> g.repos.value.filter { it.autoSync }.forEach { g.syncNow(it) } } }

        // #1106 the startup sequence: terminal installed -> started -> $HOME ready -> mounted -> the shell.
        // A failure shows its remedies and "Continue with local files" (degraded mode) instead of a crash.
        val startup = remember { StoreStartupController(applicationContext, scope, skip = StartupGuard.safeMode) }
        LaunchedEffect(startup) { startup.start() }
        val startupState by startup.state.collectAsState()
        var degraded by remember { mutableStateOf(false) }
        if (git == null) {
            Box(Modifier.fillMaxSize())
        } else if (startupState is StartupState.Ready || degraded) {
            DriveShell(select = jumpTab, onSelected = { jumpTab = null }) { tabId, _ ->
                when (tabId) {
                    "explorer" -> ExplorerScreen(jumpPage, onPageConsumed = { jumpPage = null }, local = { FilesScreen(files, this, hasAccess) }, cloud = { VolumesScreen(rclone, prefs, this, openPath) }, openDrive = { filesController?.reveal(it) })
                    "manage" -> ManageScreen(this, prefs, onShowInExplorer = openPath, page = jumpPage, onPageConsumed = { jumpPage = null })
                    "agents" -> AgentsScreen()
                    "ai" -> AiScreen()
                    "home" -> HomeScreen(git, this, onOpenFiles = { jump(Declarations.SECTION_EXPLORER, Declarations.PAGE_LOCAL) }, onOpenVolumes = { jump(Declarations.SECTION_EXPLORER, Declarations.PAGE_CLOUD) }, onOpenSync = { jumpTab = Declarations.SECTION_GIT }, onRoute = jump, onSyncAll = syncAll, page = jumpPage, onPageConsumed = { jumpPage = null })
                    "git" -> GitPageScreen(git, prefs, this, jumpPage, onPageConsumed = { jumpPage = null })
                    "fsync" -> FsyncPageScreen(storage, rclone, this, prefs, jumpPage, onPageConsumed = { jumpPage = null })
                    "mounts" -> MountsPageScreen(storage, rclone, prefs, this, jumpPage, onPageConsumed = { jumpPage = null })
                    "configs" -> ConfigsScreen(mirrors, prefs, this, hasAccess, rcloneVersion, git, storage, rclone, jumpPage, onPageConsumed = { jumpPage = null })
                    else -> EmptyState(IconCatalog.vectorOrDefault(Declarations.iconDefault), stringResource(R.string.chrome_unknown_tab), "", Modifier)
                }
            }
        } else {
            StartupGate(
                state = startupState,
                terminalPackage = StoreAccess.ownerPackage(),
                onRetry = { startup.retry() },
                onContinueLocal = { degraded = true },
                onGrantAccess = { requestStorageAccess() },
            )
        }
        if (gitReset) {
            AlertDialog(
                onDismissRequest = { SafeEncryptedPrefs.acknowledgeReset(ctx, GitCredentialStore.FILE); gitReset = false },
                text = { Text(stringResource(R.string.git_sync_sign_in_reset)) },
                confirmButton = {
                    TextButton(onClick = { SafeEncryptedPrefs.acknowledgeReset(ctx, GitCredentialStore.FILE); gitReset = false }) {
                        Text(stringResource(R.string.git_sync_sign_in_reset_ok))
                    }
                },
            )
        }
    }

    // ── DriveActions ────────────────────────────────────────────────────────

    override fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        // #639 the per-package toggle first; the fleet-wide list only if no activity claims it,
        // so a refused deep link still lands somewhere the grant can be given.
        runCatching { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
            .onFailure { runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } }
    }

    override fun requestTreeGrant() {
        // #1375 a plugged-in drive without all-files access: the system's grant for exactly that volume, else the picker.
        val drive = com.diegonmarcos.clouddrive.usb.StorageVolumes.drives(this).firstOrNull()
        val perVolume = drive?.let { com.diegonmarcos.clouddrive.usb.StorageVolumes.grantIntent(this, it.path) }
        if (perVolume != null && runCatching { volumeTreeLauncher.launch(perVolume) }.isSuccess) return
        runCatching { openTreeLauncher.launch(null) }
    }

    private fun persistTreeGrant(uri: Uri?) {
        if (uri == null) return
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        val name = runCatching { DocumentsContract.getTreeDocumentId(uri).substringBeforeLast(':') }.getOrNull() ?: uri.lastPathSegment ?: getString(R.string.files_place_sd_card)
        prefs.rememberTreeGrant(uri, name)
    }

    override fun openEngine(engine: String, target: String, url: String) {
        engineLauncher.launch(EngineActivity.intent(this, engine, target, url))
    }

    override fun openImage(path: String, siblings: List<String>) { startActivity(ImageViewerActivity.intent(this, path, siblings)) }

    /** #577 the fleet's ONE PDF reader (native pdfium); the Files tab's PDF rows open there. */
    override fun openPdf(path: String) { startActivity(PdfReaderActivity.intent(this, path)) }

    override fun openWith(path: String) {
        val file = File(path)
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            // The system chooser, with the type the file really is (MimeTypes fills in what MimeTypeMap lacks: md, mht, 7z…).
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeOf(file)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            view.clipData = ClipData.newRawUri(file.name, uri)
            startActivity(Intent.createChooser(view, file.name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
    }

    override fun share(paths: List<String>) {
        val files = paths.map(::File).filter { it.isFile }
        if (files.isEmpty()) return
        runCatching {
            val uris = files.map { FileProvider.getUriForFile(this, "$packageName.files", it) }
            val intent = if (uris.size == 1) Intent(Intent.ACTION_SEND).setType(mimeOf(files.first())).putExtra(Intent.EXTRA_STREAM, uris.first())
            else Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            startActivity(Intent.createChooser(intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
        }
    }

    override fun shareText(title: String, text: String) {
        runCatching { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, text), null)) }
    }

    override fun copyText(text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("cloud-drive", text))
    }

    override fun openUrl(url: String) {
        val parsed = Uri.parse(url.trim())
        if (parsed.scheme != "http" && parsed.scheme != "https") return
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, parsed)) }
    }

    override fun launchApp(packageName: String, fallbackUrl: String): Boolean {
        val launch = if (packageName.isBlank()) null else runCatching { packageManager.getLaunchIntentForPackage(packageName) }.getOrNull()
        if (launch != null) return runCatching { startActivity(launch) }.isSuccess
        if (fallbackUrl.isNotBlank()) return runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl))) }.isSuccess
        return false
    }

    override fun openAuthProfile(pkg: String, target: String, extra: String): Boolean {
        if (pkg.isBlank()) return false
        val launch = runCatching { packageManager.getLaunchIntentForPackage(pkg) }.getOrNull() ?: return false
        if (extra.isNotBlank() && target.isNotBlank()) launch.putExtra(extra, target)
        return runCatching { startActivity(launch) }.isSuccess
    }

    private fun mimeOf(file: File): String = com.diegonmarcos.clouddrive.files.MimeTypes.forName(file.name) { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
}
