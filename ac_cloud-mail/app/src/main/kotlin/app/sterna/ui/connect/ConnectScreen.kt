package app.sterna.ui.connect

import android.content.Intent
import android.net.Uri
import com.diegonmarcos.superapp.fleetendpoints.MailOAuthContract
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import app.sterna.ui.components.Icon
import app.sterna.ui.components.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.autofill.AutofillType
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import app.sterna.R
import app.sterna.fleet.CloudSecrets
import app.sterna.ui.CloudSecretField
import app.sterna.core.data.autoconfig.MailAutoconfigResult
import app.sterna.core.jmap.withLoginHint
import app.sterna.ui.browser.InAppBrowser
import app.sterna.ui.components.AppPasswordHelpLink
import app.sterna.ui.components.LoadingRing
import app.sterna.ui.components.PendingImportAccountsSection
import app.sterna.ui.components.autofill
import app.sterna.ui.rememberLeaveOnce
import app.sterna.ui.settings.SettingsViewModel
import app.sterna.ui.settings.applyAppLanguage
import app.sterna.ui.theme.MailMetrics
import app.sterna.util.isValidEmail
import app.sterna.core.data.account.AuthType
import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailProtocol
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun ConnectScreen(
    onConnected: () -> Unit,
    firstRun: Boolean = false,
    viewModel: ConnectViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val importSignIn by viewModel.importSignIn.collectAsStateWithLifecycle()

    LaunchedEffect(state) {
        if (state is ConnectState.Connected) onConnected()
    }
    LaunchedEffect(importSignIn) {
        if (importSignIn is ConnectViewModel.ImportSignIn.Done) onConnected()
    }
    // Resume the prompts for imported accounts killed mid-sign-in, or she faces a dead inbox.
    LaunchedEffect(Unit) { viewModel.resumeImportSignIn() }

    var protocol by rememberSaveable { mutableStateOf(MailProtocol.JMAP) }
    // JMAP only: authenticate with a server-generated API token (Bearer) instead of a password.
    var useApiToken by rememberSaveable { mutableStateOf(false) }
    var server by rememberSaveable { mutableStateOf("") }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var accountName by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    // Survives process death; the verdict it needs does not — what is drawn is [stepToRender]'s.
    var rememberedStep by rememberSaveable(stateSaver = ConnectStepSaver) {
        mutableStateOf(ConnectStep.ADDRESS)
    }
    // Tapped while the probe was out: leave when it answers — deciding early means "manual".
    var awaitingVerdict by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state) {
        if (state is ConnectState.NeedsServer) showAdvanced = true
    }

    // Quick setup and the fields it fills are one piece of state, so chips, fields and Connect
    // can never disagree about which provider is selected (#105).
    var preset by rememberSaveable(stateSaver = PresetFormSaver) { mutableStateOf(PresetForm.NONE) }
    val context = LocalContext.current
    // At first run this screen sits above the navigation graph, so the guard against a second
    // browser hangs off the activity.
    val leaveOnce = rememberLeaveOnce()

    // Focusing any credential field scrolls the whole block above the keyboard (#52 follow-up);
    // Material's per-field reveal covers only the focused field.
    val credentialReveal = remember { CredentialBlockReveal() }
    // The focus event fires before the keyboard is up; re-run as the inset settles.
    val imeInsets = WindowInsets.ime
    val density = LocalDensity.current
    LaunchedEffect(imeInsets, density) {
        snapshotFlow { imeInsets.getBottom(density) }
            .collectLatest { bottom -> if (bottom > 0) credentialReveal.reveal() }
    }
    // Its own requester, on a container that exists whether or not there is anything to say.
    val statusReveal = remember { BringIntoViewRequester() }

    // First-run only: Settings is unreachable before an account exists.
    val settingsViewModel: SettingsViewModel = viewModel()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun snackbar(message: String) = scope.launch { snackbarHostState.showSnackbar(message) }
    fun dismissWithUndo(account: app.sterna.core.data.account.StoredAccount) {
        viewModel.dismissImportAccount(account.id)
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                context.getString(R.string.import_pending_dismissed),
                actionLabel = context.getString(R.string.inbox_undo),
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) viewModel.restoreImportAccount(account)
        }
    }
    val importSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            settingsViewModel.importSettings(
                uri,
                onResult = { ok, accountsAdded ->
                    when {
                        ok && accountsAdded > 0 -> viewModel.beginImportSignIn()
                        ok -> snackbar(context.getString(R.string.connect_import_no_accounts))
                        else -> snackbar(context.getString(R.string.connect_import_invalid))
                    }
                },
                onLanguageChanged = { applyAppLanguage(it) },
            )
        }
    }
    // K-9 / Thunderbird `.k9s` export: parsed (not by extension — the files carry no MIME type),
    // its accounts imported inert, then the "accounts to sign in" list appears.
    val importK9Launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            settingsViewModel.importK9Settings(uri) { ok, added, skipped, unverified ->
                when {
                    ok && added > 0 -> {
                        val imported = if (skipped > 0) {
                            context.getString(R.string.import_snackbar_imported_with_skipped, added, skipped)
                        } else {
                            context.getString(R.string.import_snackbar_imported, added)
                        }
                        // The file did not state a connection security we map for some accounts, so
                        // theirs is a safe guess: say so rather than let it pass for what K-9 used.
                        snackbar(
                            if (unverified > 0) {
                                imported + " " +
                                    context.getString(R.string.import_snackbar_check_security, unverified)
                            } else {
                                imported
                            },
                        )
                        viewModel.beginImportSignIn()
                    }
                    ok -> snackbar(context.getString(R.string.connect_import_k9_none))
                    else -> snackbar(context.getString(R.string.connect_import_invalid))
                }
            }
        }
    }

    // One decision, shared by the button's label, its enabled state and the secret field's meaning.
    val route = connectRoute(preset, protocol, useApiToken, server)
    val ready = connectReady(
        route, username, password,
        preset.imapHost, preset.imapPort, preset.smtpHost, preset.smtpPort,
    )

    // Autoconfig cascade under this walk; the ViewModel gates it. What it finds lands only in the
    // visible fields, via [presetFilledWith]: Connect reads the fields and nothing else (WYSIWYG).

    // The durable verdict the walk steps on, never the one-shot fill signal below. [verdictFor]
    // keeps it honest: a verdict about an address since changed answers nothing here.
    val verdict by viewModel.discovery.collectAsStateWithLifecycle()
    val found = verdictFor(verdict, username) as? MailAutoconfigResult.Found
    // Read above the step: a domain over OAuth with no autoconfig reaches step 2 on this alone (#55).
    val oauthVerdict by viewModel.oauthDiscovery.collectAsStateWithLifecycle()
    val ask = credentialsAsk(oauthVerdict, username)

    // The step drawn, not the step remembered: step 2 for a server nobody discovered is not true.
    val walkStep = stepToRender(rememberedStep, found, oauthVerdict, username)

    // The route step 2 really takes (JMAP with a password), never what the manual chips say.
    val credentialsReady = connectReady(
        ConnectRoute.JMAP_AUTODISCOVER, username, password,
        preset.imapHost, preset.imapPort, preset.smtpHost, preset.smtpPort,
    )

    // What tells "nothing started" from "she cancelled it", both ConnectState.Idle. Saved.
    var handoverFor by rememberSaveable { mutableStateOf(NO_HANDOVER) }
    val handedOver = handoverStarted(handoverFor, username)
    // The one way this screen starts a hand-over: the automatic trigger and the SIGN_IN_OFFER
    // button both come through here, so neither starts one without recording that it did.
    fun startHandover() {
        handoverFor = username.trim()
        viewModel.connectOAuth(username, NO_TYPED_SERVER, accountName)
    }

    // The one place the discovery triggers agree on what they hand the guard. Hosts filled in for
    // another address go first: left in place they make the guard refuse for ever.
    fun probeAddress() {
        preset = presetForAddress(preset, verdict, username, walkStep)
        viewModel.discoverImapSettings(walkStep, route, preset, username)
    }
    // Keyed on the STEP as well as the route: arriving at a step is as much a reason to probe as
    // changing protocol, and a route-only key would never fire again once the walk moved.
    LaunchedEffect(route, walkStep) {
        if (walkStep == ConnectStep.ADDRESS || route == ConnectRoute.IMAP_PASSWORD) probeAddress()
    }
    val imapFill by viewModel.imapDiscovery.collectAsStateWithLifecycle()
    LaunchedEffect(imapFill) {
        imapFill?.let {
            preset = presetFilledWith(preset, it, username)
            // Applied exactly once: a field the user clears afterwards must not refill itself.
            viewModel.consumeImapDiscovery()
        }
    }
    // The step this seam has already cleared for, null while it has cleared nothing — what tells a
    // rotation from an arrival. Null is not spelled ADDRESS: an arriving screen must still clear.
    var clearedFor by rememberSaveable(stateSaver = ClearedStepSaver) {
        mutableStateOf<ConnectStep?>(null)
    }
    // Both loose ends of a step change are tied in one seam, so neither can be forgotten at one of
    // the several places the walk moves.
    LaunchedEffect(walkStep) {
        // A demotion is final until she moves herself: a probe succeeding later must not teleport
        // her forward.
        if (rememberedStep != walkStep) rememberedStep = walkStep
        // A stale NeedsServer re-fires the IMAP handover on re-entering step 2, adding an account
        // on a tap on "Continue". Only where due ([clearingIsDue]): a rotation restarts this
        // effect with the step unmoved.
        if (clearingIsDue(clearedFor, walkStep)) {
            clearedFor = walkStep
            viewModel.clearFinishedAttempt()
        }
    }
    // [awaitedAnswer] says what ends the wait. Offline is answered here too: no probe sets out, so
    // an armed wait has nothing that could end it.
    LaunchedEffect(awaitingVerdict, verdict, oauthVerdict, username) {
        when (val answer = awaitedAnswer(awaitingVerdict, viewModel.advanceFromAddress(username))) {
            is AwaitedAnswer.Leave -> {
                awaitingVerdict = false
                rememberedStep = answer.step
            }
            AwaitedAnswer.SayOffline -> {
                awaitingVerdict = false
                viewModel.reportOffline()
            }
            AwaitedAnswer.Wait -> Unit
        }
    }
    // The server answered that this account signs in away from the app: start that sign-in, so the
    // reader never has to know the word OAuth.
    LaunchedEffect(walkStep, ask) {
        if (!shouldStartOAuthSignIn(walkStep, ask, handedOver)) return@LaunchedEffect
        startHandover()
    }
    // JMAP first, on the secret step 2 just collected; the IMAP/SMTP endpoints the domain
    // published only if that answers "no JMAP server here" ([shouldTryDiscoveredImap]).
    LaunchedEffect(state, walkStep, found) {
        if (!shouldTryDiscoveredImap(walkStep, state, found)) return@LaunchedEffect
        // Non-null by that decision; the compiler cannot see it across the call.
        val endpoints = checkNotNull(found)
        viewModel.connectImap(
            username, password, accountName,
            endpoints.incoming.host, endpoints.incoming.port, endpoints.incoming.security,
            endpoints.outgoing.host, endpoints.outgoing.port, endpoints.outgoing.security,
        )
    }

    // Where the Connect button goes, in one place for every step that has one.
    fun submitConnect() {
        when (route) {
            ConnectRoute.OUTLOOK_OAUTH -> viewModel.connectOutlookOAuth(username, accountName)
            ConnectRoute.JMAP_TOKEN -> viewModel.connectToken(server, username, password, accountName)
            ConnectRoute.JMAP_AUTODISCOVER -> viewModel.connectAuto(username, password, accountName)
            ConnectRoute.JMAP_SERVER -> viewModel.connect(server, username, password, accountName)
            ConnectRoute.IMAP_PASSWORD -> viewModel.connectImap(
                username, password, accountName,
                preset.imapHost, preset.imapPort.toInt(), preset.imapSecurity,
                preset.smtpHost, preset.smtpPort.toInt(), preset.smtpSecurity,
            )
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.connect_add_account)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // Keep the focused field visible above the keyboard while typing (#52).
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MailMetrics.s24),
            verticalArrangement = Arrangement.spacedBy(MailMetrics.s12),
        ) {
            // Must stay above the panels: each hands the column over with a `return@Column`, so a
            // handler below is not composed while one is up and Back pops the whole screen with a
            // sign-in in flight. Whose hand-over it is is [backCancelsHandover]'s question.
            BackHandler(enabled = backCancelsHandover(state, handedOver)) { viewModel.cancelOAuth() }
            val awaiting = state as? ConnectState.AwaitingApproval
            if (awaiting != null) {
                DeviceApprovalPanel(awaiting, onCancel = viewModel::cancelOAuth)
                return@Column
            }
            val awaitingBrowser = state as? ConnectState.AwaitingBrowser
            if (awaitingBrowser != null) {
                BrowserAuthorizationPanel(awaitingBrowser, onCancel = viewModel::cancelOAuth)
                return@Column
            }
            (importSignIn as? ConnectViewModel.ImportSignIn.Listing)?.let { listing ->
                val sel = listing.selected
                if (sel == null) {
                    Spacer(Modifier.height(MailMetrics.s8))
                    // The section renders its own "Accounts to sign in" header, so no title here.
                    PendingImportAccountsSection(
                        // Re-read each recomposition (driven by importSignIn), so signed-in and
                        // dismissed accounts drop off the list.
                        accounts = viewModel.pendingStoredAccounts,
                        onSignIn = { viewModel.selectImportAccount(it.id) },
                        onDismiss = { dismissWithUndo(it) },
                    )
                    // Always offer the normal form: with every import deferred, the listing would
                    // be a dead end.
                    Spacer(Modifier.height(MailMetrics.s8))
                    Button(
                        onClick = viewModel::leaveImportListing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(MailMetrics.s8))
                        Text(stringResource(R.string.connect_add_account))
                    }
                } else {
                    ImportAccountSignIn(sel, viewModel)
                }
                return@Column
            }
            // Back belongs to the walk. From the address step [stepBackKeeping] answers null, this
            // handler is disabled and the system pops. The address travels back with it.
            BackHandler(enabled = stepBackKeeping(walkStep, username) != null) {
                stepBackKeeping(walkStep, username)?.let { back ->
                    rememberedStep = back.step
                    username = back.email
                }
            }

            when (walkStep) {
                // ---- 1. The address, alone ----------------------------------------------------
                ConnectStep.ADDRESS -> {
                    if (firstRun) {
                        Spacer(Modifier.height(MailMetrics.s4))
                        Text(
                            stringResource(R.string.connect_welcome_title),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            stringResource(R.string.connect_welcome_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // While deferred imported accounts remain, offer the way back to their list.
                    if (viewModel.pendingStoredAccounts.isNotEmpty()) {
                        Spacer(Modifier.height(MailMetrics.s4))
                        OutlinedButton(
                            onClick = viewModel::resumeImportSignIn,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                            Spacer(Modifier.width(MailMetrics.s8))
                            Text(stringResource(R.string.import_pending_title))
                        }
                    }
                    // Import entry points belong where people add accounts, not in Settings → Backup.
                    Spacer(Modifier.height(MailMetrics.s4))
                    Text(stringResource(R.string.connect_import_header), style = MaterialTheme.typography.labelLarge)
                    OutlinedButton(
                        onClick = {
                            importK9Launcher.launch(
                                arrayOf("application/octet-stream", "text/xml", "application/xml", "*/*"),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.SettingsBackupRestore, contentDescription = null)
                        Spacer(Modifier.width(MailMetrics.s8))
                        Text(stringResource(R.string.connect_import_k9))
                    }
                    OutlinedButton(
                        onClick = {
                            importSettingsLauncher.launch(
                                arrayOf("application/json", "application/octet-stream", "text/plain"),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.SettingsBackupRestore, contentDescription = null)
                        Spacer(Modifier.width(MailMetrics.s8))
                        Text(stringResource(R.string.connect_import_settings))
                    }
                    // #1693 the fleet's own accounts are signed in in Cloud Account: this opens it at the mail page, and takes what it holds.
                    OutlinedButton(
                        onClick = { app.sterna.fleet.MailAccountSync.openCloudAccount(context)?.let { snackbar(it) } },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.SettingsBackupRestore, contentDescription = null)
                        Spacer(Modifier.width(MailMetrics.s8))
                        Text(stringResource(R.string.connect_cloud_account_open))
                    }
                    OutlinedButton(
                        onClick = { viewModel.takeAccountsFromCloudAccount { snackbar(it) } },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.SettingsBackupRestore, contentDescription = null)
                        Spacer(Modifier.width(MailMetrics.s8))
                        Text(stringResource(R.string.connect_cloud_account_import))
                    }
                    // No protocol question, no host field, no sign-in method: the probes answer those.
                    Column(
                        modifier = Modifier.bringIntoViewRequester(credentialReveal.block),
                        verticalArrangement = Arrangement.spacedBy(MailMetrics.s12),
                    ) {
                        AccountNameField(accountName, { accountName = it }, credentialReveal)
                        EmailField(username, { username = it; awaitingVerdict = false }, credentialReveal) {
                            probeAddress()
                        }
                        Button(
                            onClick = {
                                when (val advance = viewModel.advanceFromAddress(username)) {
                                    is AddressAdvance.Go -> rememberedStep = advance.step
                                    // Nothing to wait for, so say so and stay put — Waiting would
                                    // spin for ever. The scroll is explicit: reportOffline writes
                                    // an equal Error, so a second tap emits nothing at all.
                                    AddressAdvance.Offline -> {
                                        viewModel.reportOffline()
                                        scope.launch { statusReveal.bringIntoView() }
                                    }
                                    // Ask for the probe (it may never have set out, the field not
                                    // having lost focus) and leave when it answers.
                                    AddressAdvance.Waiting -> {
                                        awaitingVerdict = true
                                        probeAddress()
                                    }
                                    AddressAdvance.Blocked -> Unit
                                }
                            },
                            // The shared address rule, not advanceFromAddress: this runs in
                            // composition on every keystroke, and the link decides only the tap.
                            enabled = canLeaveAddressStep(username) && !awaitingVerdict,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                stringResource(
                                    if (awaitingVerdict) R.string.connect_discovering else R.string.connect_step_continue,
                                ),
                            )
                        }
                        // Only once a probe for this address came back with nothing: beside a
                        // verdict that worked, it is the protocol question walking back on screen.
                        if (offersManualSetup(verdict, oauthVerdict, username)) {
                            OutlinedButton(
                                onClick = { rememberedStep = ConnectStep.MANUAL },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.connect_step_manual)) }
                        }
                    }
                }
                // ---- 2. What the domain published, and the secret it needs --------------------
                ConnectStep.CREDENTIALS -> {
                    Spacer(Modifier.height(MailMetrics.s4))
                    // Shown rather than assumed (WYSIWYG), and a username node for the password
                    // managers that match on address + domain, not on the secret field.
                    OutlinedTextField(
                        value = username,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.connect_email_username)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().autofill(
                            listOf(AutofillType.EmailAddress, AutofillType.Username),
                        ) {},
                    )
                    // The password field belongs to one of the server's two answers and is drawn
                    // only under it: under the other the browser sign-in is already starting, and a
                    // secret typed meanwhile would be collected and dropped in silence.
                    when (credentialsPane(ask, state, handedOver)) {
                        CredentialsPane.WAITING -> LoadingRing()
                        // The hand-over is over without an account (cancelled, or failed): the step
                        // becomes an invitation again, which is what makes Cancel mean something.
                        CredentialsPane.SIGN_IN_OFFER -> {
                            Text(stringResource(R.string.connect_oauth_code_step1), style = MaterialTheme.typography.bodyMedium)
                            Button(
                                onClick = { startHandover() },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.connect_oauth_open_browser))
                            }
                        }
                        CredentialsPane.PASSWORD -> {
                            // The provider refuses a normal password (Gmail and friends): one tap
                            // to the page where she creates the one that works.
                            found?.let { appPasswordUrlFor(it) }?.let { url ->
                                Text(
                                    stringResource(R.string.connect_provider_app_password_note),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                TextButton(
                                    onClick = {
                                        // A help page, so it opens in this app's own browser (#307).
                                        // The OAuth hand-offs further down deliberately do not:
                                        // a sign-in belongs in the browser holding the session.
                                        leaveOnce { InAppBrowser.openLink(context, Uri.parse(url)) }
                                    },
                                    contentPadding = PaddingValues(horizontal = MailMetrics.s4, vertical = 0.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.OpenInNew,
                                        contentDescription = null,
                                        modifier = Modifier.size(MailMetrics.s16),
                                    )
                                    Spacer(Modifier.width(MailMetrics.s6))
                                    Text(stringResource(R.string.connect_app_password_help))
                                }
                            }
                            Column(
                                modifier = Modifier.bringIntoViewRequester(credentialReveal.block),
                                verticalArrangement = Arrangement.spacedBy(MailMetrics.s12),
                            ) {
                                SecretField(
                                    value = password,
                                    onValue = { password = it },
                                    label = stringResource(R.string.connect_password),
                                    reveal = credentialReveal,
                                    email = username,
                                )
                                val busy = state is ConnectState.Connecting || state is ConnectState.Discovering
                                // Not the manual form's submit: that reads the protocol chips, and
                                // a tap on IMAP/SMTP three steps ago would skip JMAP entirely.
                                Button(
                                    onClick = { viewModel.connectAuto(username, password, accountName) },
                                    enabled = !busy && credentialsReady,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        when (state) {
                                            is ConnectState.Discovering -> stringResource(R.string.connect_discovering)
                                            is ConnectState.Connecting -> stringResource(R.string.connect_connecting)
                                            else -> stringResource(R.string.connect_connect)
                                        },
                                    )
                                }
                            }
                        }
                    }
                    // The way out (#55): with the invitation and nothing else, a server refusing
                    // this app's client id would leave the account addable by no gesture, Back
                    // landing on the address step, which withholds "Set up manually" on a `Found`.
                    credentialsExit(credentialsPane(ask, state, handedOver))?.let { exit ->
                        OutlinedButton(
                            onClick = { rememberedStep = exit },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.connect_step_manual))
                        }
                    }
                }
                // ---- 3. The manual fallback — reached only when discovery came back empty -----
                ConnectStep.MANUAL -> {
                    Text(stringResource(R.string.connect_protocol), style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(MailMetrics.s8)) {
                        FilterChip(
                            selected = protocol == MailProtocol.JMAP,
                            onClick = {
                                protocol = MailProtocol.JMAP
                                // Leaving IMAP disarms an OAuth preset, so Connect can't still be
                                // pointing at the Microsoft flow while the screen says JMAP (#105).
                                preset = presetForProtocol(preset, MailProtocol.JMAP)
                            },
                            label = { Text(stringResource(R.string.connect_jmap)) },
                        )
                        FilterChip(
                            selected = protocol == MailProtocol.IMAP,
                            onClick = {
                                protocol = MailProtocol.IMAP
                                preset = presetForProtocol(preset, MailProtocol.IMAP)
                            },
                            label = { Text(stringResource(R.string.connect_imap_smtp)) },
                        )
                    }

                    if (protocol == MailProtocol.JMAP) {
                        Text(stringResource(R.string.connect_auth_method), style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(MailMetrics.s8)) {
                            FilterChip(
                                selected = !useApiToken,
                                onClick = { useApiToken = false },
                                label = { Text(stringResource(R.string.connect_password)) },
                            )
                            FilterChip(
                                selected = useApiToken,
                                onClick = { useApiToken = true },
                                label = { Text(stringResource(R.string.connect_auth_api_token)) },
                            )
                        }
                        Text(
                            stringResource(
                                if (useApiToken) R.string.connect_api_token_hint else R.string.connect_jmap_autodiscover_hint,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // Fastmail's JMAP endpoint refuses password (Basic) auth — API tokens only
                        // (#54) — so steer its users to the token option before they hit the 401.
                        if (!useApiToken && isFastmailTarget(username, server)) {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                            ) {
                                Text(
                                    stringResource(R.string.connect_fastmail_token_hint),
                                    modifier = Modifier.padding(horizontal = MailMetrics.s12, vertical = MailMetrics.s8),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                            }
                        }
                        TextButton(
                            onClick = { showAdvanced = !showAdvanced },
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            Text(
                                stringResource(
                                    if (showAdvanced) R.string.connect_advanced_hide else R.string.connect_advanced_show,
                                ),
                            )
                        }
                        if (showAdvanced) {
                            OutlinedTextField(
                                value = server,
                                onValueChange = { server = it },
                                label = { Text(stringResource(R.string.connect_jmap_server)) },
                                placeholder = { Text(stringResource(R.string.connect_jmap_server_placeholder)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    } else {
                        Text(stringResource(R.string.connect_provider_preset), style = MaterialTheme.typography.labelLarge)
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(MailMetrics.s8),
                        ) {
                            MAIL_PROVIDERS.forEach { provider ->
                                // FilterChip, not AssistChip: tapping it again lets go (#105).
                                FilterChip(
                                    selected = preset.selected == provider.name,
                                    onClick = { preset = presetChipTapped(preset, provider) },
                                    label = { Text(provider.name) },
                                )
                            }
                        }
                        // Outlook signs in by OAuth: no app-password note, no server/port fields.
                        if (preset.serverFieldsVisible) {
                            Text(
                                stringResource(R.string.connect_provider_app_password_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            // The preset refuses a normal password (Gmail…): one tap to its page.
                            preset.appPasswordUrl?.let { url ->
                                TextButton(
                                    onClick = {
                                        // A help page, so it opens in this app's own browser (#307).
                                        // The OAuth hand-offs further down deliberately do not:
                                        // a sign-in belongs in the browser holding the session.
                                        leaveOnce { InAppBrowser.openLink(context, Uri.parse(url)) }
                                    },
                                    contentPadding = PaddingValues(horizontal = MailMetrics.s4, vertical = 0.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.OpenInNew,
                                        contentDescription = null,
                                        modifier = Modifier.size(MailMetrics.s16),
                                    )
                                    Spacer(Modifier.width(MailMetrics.s6))
                                    Text(stringResource(R.string.connect_app_password_help))
                                }
                            }

                            Text(stringResource(R.string.connect_incoming_imap), style = MaterialTheme.typography.labelLarge)
                            HostPortRow(
                                host = preset.imapHost, onHost = { preset = preset.copy(imapHost = it, discoveredFor = null) },
                                port = preset.imapPort, onPort = { preset = preset.copy(imapPort = it) },
                                hostPlaceholder = stringResource(R.string.connect_imap_host_placeholder),
                            )
                            SecurityChips(preset.imapSecurity) { preset = preset.copy(imapSecurity = it) }

                            Text(stringResource(R.string.connect_outgoing_smtp), style = MaterialTheme.typography.labelLarge)
                            HostPortRow(
                                host = preset.smtpHost, onHost = { preset = preset.copy(smtpHost = it, discoveredFor = null) },
                                port = preset.smtpPort, onPort = { preset = preset.copy(smtpPort = it) },
                                hostPlaceholder = stringResource(R.string.connect_smtp_host_placeholder),
                            )
                            SecurityChips(preset.smtpSecurity) { preset = preset.copy(smtpSecurity = it) }
                        }
                    }

                    // One container for the block: revealed as a unit, all of it clears the keyboard.
                    Column(
                        modifier = Modifier.bringIntoViewRequester(credentialReveal.block),
                        verticalArrangement = Arrangement.spacedBy(MailMetrics.s12),
                    ) {
                        AccountNameField(accountName, { accountName = it }, credentialReveal)
                        EmailField(username, { username = it }, credentialReveal) { probeAddress() }
                        // In API-token mode this same secret field holds the token (labelled accordingly).
                        val tokenMode = route == ConnectRoute.JMAP_TOKEN
                        SecretField(
                            value = password,
                            onValue = { password = it },
                            label = stringResource(
                                if (tokenMode) R.string.connect_auth_api_token else R.string.connect_password,
                            ),
                            reveal = credentialReveal,
                            email = username,
                            token = tokenMode,
                        )
                        val busy = state is ConnectState.Connecting || state is ConnectState.Discovering
                        Button(
                            onClick = { submitConnect() },
                            enabled = !busy && ready,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                when (state) {
                                    is ConnectState.Discovering -> stringResource(R.string.connect_discovering)
                                    is ConnectState.Connecting -> stringResource(R.string.connect_connecting)
                                    else -> stringResource(R.string.connect_connect)
                                },
                            )
                        }

                        if (protocol == MailProtocol.JMAP && !useApiToken) {
                            TextButton(
                                onClick = { viewModel.connectOAuth(username, server, accountName) },
                                enabled = !busy && username.isNotBlank(),
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.connect_oauth_button)) }
                        }
                        // Outlook has no separate button — its provider chip launches the OAuth flow.
                    }
                }
            }

            Spacer(Modifier.height(MailMetrics.s4))
            // A value first ([statusLine]), because it is also what the effect below scrolls to:
            // two readings of the state would drift in silence. The Column is composed whatever
            // the value is; wrap it in `if (status != null)` and the requester attaches too late.
            val status = statusLine(state, walkStep, ask, handedOver)
            Column(modifier = Modifier.bringIntoViewRequester(statusReveal)) {
                when (status) {
                    StatusLine.Working -> LoadingRing()
                    StatusLine.ServerNotFound -> Text(
                        text = stringResource(R.string.connect_server_not_found),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    is StatusLine.Failed -> Text(
                        text = stringResource(R.string.connect_error, status.message),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    null -> Unit
                }
            }
            // The offline answer is the sentence above, and the IME covers it. Keyed on the value
            // said, not on recomposition.
            LaunchedEffect(status) {
                if (statusMustBeRead(status)) statusReveal.bringIntoView()
            }
        }
    }
}

/** What the walk's own sign-in passes where the manual form passes its "Advanced" server field.
 *  Not `server`: that is a `rememberSaveable` nothing ever blanks, not on screen at step 2, and it
 *  decides which host is asked — carried to another address it reports OAuth unsupported, falsely. */
private const val NO_TYPED_SERVER = ""

/** Coordinates scrolling the credential block above the keyboard: one requester for the whole
 *  block, plus the requester of whichever field currently holds focus. */
@OptIn(ExperimentalFoundationApi::class)
private class CredentialBlockReveal {
    val block = BringIntoViewRequester()

    /** The focused field's own requester, or null while no credential field has focus. */
    var focused by mutableStateOf<BringIntoViewRequester?>(null)

    /** Best effort for the block, guarantee for the field: the block request cannot always show
     *  the focused field in full. */
    suspend fun reveal() {
        val field = focused ?: return
        block.bringIntoView()
        field.bringIntoView()
    }
}

/** Marks a credential field: focus reveals the whole block, not just this field, which is all
 *  Material's built-in bring-into-view guarantees. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.credentialField(reveal: CredentialBlockReveal): Modifier {
    val own = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    return this
        .bringIntoViewRequester(own)
        .onFocusEvent { focusState ->
            if (focusState.isFocused) {
                reveal.focused = own
                // For the keyboard-already-open case; the first opening is the inset watcher's.
                scope.launch { reveal.reveal() }
            } else if (reveal.focused === own) {
                reveal.focused = null
            }
        }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AccountNameField(value: String, onValue: (String) -> Unit, reveal: CredentialBlockReveal) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(stringResource(R.string.connect_account_name_optional)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
        modifier = Modifier.fillMaxWidth().credentialField(reveal),
    )
}

/** The address field — one implementation for the address step and the manual form, so the warning
 *  and the discovery trigger cannot drift. [onFocusLost] fires on the falling edge of focus. */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
private fun EmailField(
    value: String,
    onValue: (String) -> Unit,
    reveal: CredentialBlockReveal,
    onFocusLost: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    var hadFocus by remember { mutableStateOf(false) }
    // Flag an obviously malformed email (missing @ or a too-short/absent extension) as the user
    // types, without hard-blocking: some IMAP servers accept a non-email username.
    val emailLooksInvalid = value.isNotBlank() && !isValidEmail(value)
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(stringResource(R.string.connect_email_username)) },
        singleLine = true,
        isError = emailLooksInvalid,
        supportingText = if (emailLooksInvalid) {
            { Text(stringResource(R.string.connect_email_invalid)) }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
        modifier = Modifier.fillMaxWidth().credentialField(reveal)
            .onFocusChanged { focusState ->
                // The moment discovery may set out; the ViewModel's guard decides whether it does.
                if (hadFocus && !focusState.isFocused) onFocusLost()
                hadFocus = focusState.isFocused
            }
            .autofill(listOf(AutofillType.EmailAddress, AutofillType.Username), onValue),
    )
}

/** The secret field: a password at the credentials step, a password or an API token on the manual
 *  form (which is why the [label] is the caller's to choose). */
/** The secret slot: a password at the credentials step, a password or an API token on the manual form (which is why the [label] is the
 *  caller's to choose). #1693 It is not typed here: it is entered once in Cloud Account (App credentials) and taken from there; [email]
 *  names the mailbox the credential is filed under (blank = ask for the address first), [token] picks the API-token id. */
@Composable
private fun SecretField(
    value: String,
    onValue: (String) -> Unit,
    label: String,
    reveal: CredentialBlockReveal,
    email: String = "",
    token: Boolean = false,
) {
    CloudSecretField(
        label = label,
        idOrNull = if (email.isBlank()) null else if (token) CloudSecrets.tokenId(email) else CloudSecrets.passwordId(email),
        filled = value.isNotEmpty(),
        onTaken = onValue,
        modifier = Modifier.fillMaxWidth().credentialField(reveal),
    )
}

@Composable
private fun DeviceApprovalPanel(state: ConnectState.AwaitingApproval, onCancel: () -> Unit) {
    Spacer(Modifier.height(MailMetrics.s8))
    // state.loginHint, and NOT the composable's own username field: this panel is shown for the
    // generic JMAP flow AND for the app-scoped Outlook one, and only the state tells them apart.
    DeviceApprovalContent(
        state.userCode, state.verificationUri, state.verificationUriComplete, state.loginHint, onCancel,
    )
}

/** Authorization-code panel (#55): open the sign-in page, then wait for the browser to come back.
 *  Nothing to show or copy — the exchange travels in the URL — plus an image for no browser. */
@Composable
private fun BrowserAuthorizationPanel(state: ConnectState.AwaitingBrowser, onCancel: () -> Unit) {
    val context = LocalContext.current
    // Two browsers for one authorization: each window carries its own request, the user finishes
    // in one, and the other comes back with a state that is no longer the pending one.
    val leaveOnce = rememberLeaveOnce()
    var noBrowser by remember { mutableStateOf(false) }
    Spacer(Modifier.height(MailMetrics.s8))
    Text(
        stringResource(R.string.connect_oauth_code_step1),
        style = MaterialTheme.typography.bodyMedium,
    )
    Button(
        onClick = {
            leaveOnce {
                // Reports back rather than swallowing: a build with no browser must say so, and
                // must not crash on ActivityNotFoundException either.
                // #1703 the browser trip starts in Cloud Account (it holds the PKCE verifier, the state and the URL): this opens ITS page for the handle.
                val opened = runCatching {
                    context.startActivity(
                        Intent(MailOAuthContract.ACTION_CODE_OPEN)
                            .setPackage(MailOAuthContract.ACCOUNT_PACKAGE)
                            .putExtra(MailOAuthContract.EXTRA_HANDLE, state.handle)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }.isSuccess
                noBrowser = !opened
                opened
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.connect_oauth_open_browser)) }
    if (noBrowser) {
        Text(
            stringResource(R.string.connect_oauth_no_browser),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    Text(
        stringResource(R.string.connect_oauth_code_waiting),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MailMetrics.s12)) {
        CircularProgressIndicator(modifier = Modifier.height(MailMetrics.s20).width(MailMetrics.s20))
        TextButton(onClick = onCancel) { Text(stringResource(R.string.connect_oauth_cancel)) }
    }
}

/** The shared device-flow approval body, reused by the add-account and imported-account flows. */
@Composable
private fun DeviceApprovalContent(
    userCode: String,
    verificationUri: String,
    verificationUriComplete: String?,
    loginHint: String?,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val copiedMsg = stringResource(R.string.connect_oauth_code_copied)
    // Two browsers for one approval: she finishes in one and stares at the other for ever.
    val leaveOnce = rememberLeaveOnce()
    Text(
        stringResource(R.string.connect_oauth_step1),
        style = MaterialTheme.typography.bodyMedium,
    )
    // Tap the code (or the icon) to copy it — typing it on the Microsoft page is a pain.
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
            val approval = verificationUriComplete ?: verificationUri
            // Computed outside the guard on purpose — the hand-off stays inside it, which is what
            // NavHostSourceRulesTest holds. A hint the server wrote wins: see withLoginHint.
            val target = if (loginHint.isNullOrBlank()) approval else withLoginHint(approval, loginHint)
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
    Text(
        stringResource(R.string.connect_oauth_waiting),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MailMetrics.s12)) {
        CircularProgressIndicator(modifier = Modifier.height(MailMetrics.s20).width(MailMetrics.s20))
        TextButton(onClick = onCancel) { Text(stringResource(R.string.connect_oauth_cancel)) }
    }
}

/** The inline sign-in for the imported account tapped: a live device-flow, an OAuth button, or a
 *  password field (basic-auth or a chosen app-password fallback). */
@Composable
private fun ImportAccountSignIn(target: ConnectViewModel.SignInTarget, viewModel: ConnectViewModel) {
    val approval = target.approval
    when {
        approval != null -> {
            Spacer(Modifier.height(MailMetrics.s8))
            // No login hint: this is the post-import Microsoft sign-in, out of #55's scope.
            DeviceApprovalContent(
                approval.userCode, approval.verificationUri,
                approval.verificationUriComplete, null, onCancel = viewModel::cancelImportOAuth,
            )
        }
        target.account.authType == AuthType.OAUTH && !target.forcePassword ->
            ImportOAuthPanel(target, viewModel)
        else ->
            ImportSignInPanel(target, viewModel)
    }
}

/** Post-import step for an OAuth account (Microsoft). Unknown XOAUTH2 hosts cannot be signed in
 *  automatically, so only the app-password fallback is offered. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ImportOAuthPanel(target: ConnectViewModel.SignInTarget, viewModel: ConnectViewModel) {
    val account = target.account
    Spacer(Modifier.height(MailMetrics.s8))
    Text(
        stringResource(R.string.connect_import_signin_title),
        style = MaterialTheme.typography.headlineSmall,
    )
    OutlinedTextField(
        value = account.email,
        onValueChange = {},
        readOnly = true,
        label = { Text(stringResource(R.string.connect_email_username)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().autofill(
            listOf(AutofillType.EmailAddress, AutofillType.Username),
        ) {},
    )
    if (account.provider != null) {
        Text(
            stringResource(R.string.connect_import_oauth_explainer),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = viewModel::startImportOAuth,
            enabled = !target.verifying,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (target.verifying) {
                CircularProgressIndicator(modifier = Modifier.height(MailMetrics.s20).width(MailMetrics.s20))
            } else {
                Text(stringResource(R.string.connect_import_signin_microsoft))
            }
        }
    } else {
        Text(
            stringResource(R.string.connect_import_oauth_unsupported),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    target.error?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    if (target.offerAppPasswordFallback || account.provider == null) {
        Text(
            stringResource(R.string.connect_import_app_password_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = viewModel::switchImportToAppPassword,
            enabled = !target.verifying,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.connect_import_use_app_password)) }
        AppPasswordHelpLink()
    }
    TextButton(onClick = viewModel::closeImportAccount, enabled = !target.verifying) {
        Text(stringResource(R.string.connect_import_signin_skip))
    }
}

/** Post-import step: ask for the account's password, verify it and save it. On success the account
 *  drops off the list; "Back to list" returns without signing in. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ImportSignInPanel(target: ConnectViewModel.SignInTarget, viewModel: ConnectViewModel) {
    val account = target.account
    var password by rememberSaveable(account.id) { mutableStateOf("") }
    Spacer(Modifier.height(MailMetrics.s8))
    Text(
        stringResource(R.string.connect_import_signin_title),
        style = MaterialTheme.typography.headlineSmall,
    )
    // Read-only: says which account this is, and gives password managers the username node they
    // match on (username + domain).
    OutlinedTextField(
        value = account.email,
        onValueChange = {},
        readOnly = true,
        label = { Text(stringResource(R.string.connect_email_username)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().autofill(
            listOf(AutofillType.EmailAddress, AutofillType.Username),
        ) {},
    )
    // A forced fallback means the OAuth sign-in failed or was declined: point at a Microsoft app
    // password to paste below.
    if (target.forcePassword) {
        Text(
            stringResource(R.string.connect_import_app_password_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AppPasswordHelpLink()
    }
    // #1693 the password is entered once in Cloud Account (App credentials) and taken from there, not typed here.
    CloudSecretField(
        label = stringResource(R.string.connect_password),
        idOrNull = CloudSecrets.passwordId(account.email),
        filled = password.isNotBlank(),
        onTaken = { password = it },
        modifier = Modifier.fillMaxWidth(),
    )
    target.error?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    Button(
        onClick = { viewModel.submitImportPassword(password) },
        enabled = password.isNotBlank() && !target.verifying,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (target.verifying) {
            CircularProgressIndicator(modifier = Modifier.height(MailMetrics.s20).width(MailMetrics.s20))
        } else {
            Text(stringResource(R.string.connect_import_signin_button))
        }
    }
    TextButton(onClick = viewModel::closeImportAccount, enabled = !target.verifying) {
        Text(stringResource(R.string.connect_import_signin_skip))
    }
}

/** Keeps the step the walk reached across a rotation and a process death — by name, so a reordered
 *  enum cannot resurrect a different step from an old bundle. Not enough on its own: the verdict
 *  step 2 needs does not survive ([stepToRender]). */
private val ConnectStepSaver = listSaver<ConnectStep, String>(
    save = { listOf(it.name) },
    restore = { ConnectStep.valueOf(it[0]) },
)

/** No step has been cleared for yet — what an ARRIVING screen holds, and a rotation never does. */
private const val NOTHING_CLEARED = ""

private val ClearedStepSaver = listSaver<ConnectStep?, String>(
    save = { listOf(it?.name ?: NOTHING_CLEARED) },
    restore = { saved -> saved[0].takeIf { it != NOTHING_CLEARED }?.let(ConnectStep::valueOf) },
)

private val PresetFormSaver = listSaver<PresetForm, String>(
    save = {
        listOf(
            it.selected.orEmpty(), it.oauth.toString(),
            it.imapHost, it.imapPort, it.imapSecurity.name,
            it.smtpHost, it.smtpPort, it.smtpSecurity.name,
            it.appPasswordUrl.orEmpty(),
            // Saved with the fields it is about: the host values survive process death, so the fact
            // that says who put them there must survive too, or the cascade jams for good.
            it.discoveredFor.orEmpty(),
        )
    },
    restore = {
        PresetForm(
            // No provider name or help page is ever "", so "" stands in for "none": the saved list
            // holds no nulls.
            selected = it[0].ifEmpty { null },
            oauth = it[1] == "true",
            imapHost = it[2],
            imapPort = it[3],
            imapSecurity = ConnectionSecurity.valueOf(it[4]),
            smtpHost = it[5],
            smtpPort = it[6],
            smtpSecurity = ConnectionSecurity.valueOf(it[7]),
            appPasswordUrl = it[8].ifEmpty { null },
            discoveredFor = it[9].ifEmpty { null },
        )
    },
)

@Composable
private fun HostPortRow(
    host: String,
    onHost: (String) -> Unit,
    port: String,
    onPort: (String) -> Unit,
    hostPlaceholder: String = "",
) {
    Row(horizontalArrangement = Arrangement.spacedBy(MailMetrics.s8)) {
        OutlinedTextField(
            value = host,
            onValueChange = onHost,
            label = { Text(stringResource(R.string.connect_server)) },
            placeholder = { if (hostPlaceholder.isNotEmpty()) Text(hostPlaceholder) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = port,
            onValueChange = { onPort(it.filter(Char::isDigit)) },
            label = { Text(stringResource(R.string.connect_port)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(MailMetrics.s110),
        )
    }
}

@Composable
private fun SecurityChips(selected: ConnectionSecurity, onSelect: (ConnectionSecurity) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(MailMetrics.s8)) {
        FilterChip(selected == ConnectionSecurity.TLS, { onSelect(ConnectionSecurity.TLS) }, { Text(stringResource(R.string.connect_security_ssl_tls)) })
        FilterChip(selected == ConnectionSecurity.STARTTLS, { onSelect(ConnectionSecurity.STARTTLS) }, { Text(stringResource(R.string.connect_security_starttls)) })
    }
}
