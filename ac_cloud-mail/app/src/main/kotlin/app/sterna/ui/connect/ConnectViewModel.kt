package app.sterna.ui.connect

import android.app.Application
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.sterna.R
import app.sterna.container
import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.AuthType
import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailEndpoint
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.account.accountKeyOf
import app.sterna.core.data.account.resolveExistingLogin
import app.sterna.core.data.autoconfig.MailAutoconfigResult
import app.sterna.core.data.autoconfig.discoverMailAutoconfig
import app.sterna.core.data.mail.MailRepository
import app.sterna.core.data.mail.NotifyRead
import app.sterna.core.data.mail.OAuthDeniedException
import app.sterna.core.imap.ImapException
import app.sterna.core.data.mail.OAuthProvider
import app.sterna.core.jmap.BearerAuth
import app.sterna.core.jmap.DeviceAuthorization
import app.sterna.core.jmap.Jmap
import app.sterna.core.jmap.JmapException
import app.sterna.core.jmap.OAuthMetadata
import app.sterna.net.hasUsableNetwork
import app.sterna.push.NewMailNotifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** UI state for the connect/account-setup screen. */
sealed interface ConnectState {
    data object Idle : ConnectState
    data object Connecting : ConnectState
    data object Discovering : ConnectState
    data object Connected : ConnectState
    /** Autodiscovery found no server; the user must enter it manually. */
    data object NeedsServer : ConnectState
    /** Device flow started: show the user code and wait for browser approval. */
    data class AwaitingApproval(
        val userCode: String,
        val verificationUri: String,
        val verificationUriComplete: String?,
        /** The address the user typed, for the approval page to pre-fill (#55). In the state, not
         *  the screen, which cannot tell the two producers apart and would hint Microsoft too. */
        val loginHint: String? = null,
    ) : ConnectState
    /** Authorization-code flow started: the browser must be sent to [authorizationUrl] (#55). */
    data class AwaitingBrowser(val authorizationUrl: String) : ConnectState
    data class Error(val message: String) : ConnectState
}

/** True when a JMAP password sign-in is aimed at Fastmail — their endpoint only accepts API
 *  tokens (#54): the address is @fastmail.com/.fm, or the server points at api.fastmail.com. */
internal fun isFastmailTarget(email: String, server: String): Boolean {
    val domain = email.trim().substringAfterLast('@', "").lowercase()
    return domain == "fastmail.com" || domain == "fastmail.fm" ||
        server.contains("api.fastmail.com", ignoreCase = true)
}

/** The account an add resolved to: its id, and whether this add is what created it. */
internal data class AddedAccount(val id: String, val created: Boolean)

/** What an add says when it is over — null for "say nothing". An add resolving onto an account
 *  already stored refreshes it in place, so a re-add says so. */
@StringRes
internal fun accountAddedToast(created: Boolean, @StringRes createdRes: Int?): Int? =
    if (created) createdRes else R.string.connect_account_refreshed

/** Whether an IMAP sign-in failure means the server looked at the credentials and said no — the one
 *  reading that may say "wrong email or password". With a [responseCode] (RFC 5530) only the three
 *  credential codes blame the password; `[UNAVAILABLE]` during AUTHENTICATE is a server down. */
internal fun imapAuthRejected(responseCode: String?, message: String): Boolean =
    if (responseCode != null) {
        responseCode in CREDENTIAL_RESPONSE_CODES
    } else {
        message.contains("LOGIN", ignoreCase = true) ||
            message.contains("AUTHENTICATE", ignoreCase = true)
    }

/** The RFC 5530 codes that mean "the credentials themselves were refused" — and no other. */
private val CREDENTIAL_RESPONSE_CODES = setOf("AUTHENTICATIONFAILED", "AUTHORIZATIONFAILED", "EXPIRED")

/** What an add ended on: the account's id, and the priming failure it survived, if any. It is
 *  never a reason to undo the add (see [addAccountThenPrime]), only something to log. */
internal data class PrimedAccount(val id: String, val primeFailure: Throwable? = null)

/** Add an account in the one order that cannot write mail under an empty account id (#121):
 *  [validate] writes nothing, [persist] creates or finds the account, [prime] fills the cache with
 *  credentials stamped with that id. A failed priming keeps the account: `refresh()` persists the
 *  folder list before it rethrows, and removing would move the user off what she was reading. */
internal suspend fun addAccountThenPrime(
    probe: AccountCredentials,
    validate: suspend (AccountCredentials) -> Unit,
    persist: suspend () -> AddedAccount,
    prime: suspend (AccountCredentials) -> Unit,
): PrimedAccount {
    validate(probe)
    val account = persist()
    // The belt inside the flow: an id-less account cannot be primed, it can only be a bug.
    check(account.id.isNotBlank()) { "Refusing to prime the cache under a blank account id." }
    return try {
        prime(probe.copy(id = account.id))
        PrimedAccount(account.id)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (t: Throwable) {
        PrimedAccount(account.id, primeFailure = t)
    }
}

/** The ids an add should write into the new inbox's notification baseline, or null for none.
 *  Without it the first background pass is the first sight of the inbox, and a first sight seeds
 *  Null on an empty list, which on IMAP is also what a countless SELECT looks like. */
internal fun baselineForAddedInbox(read: NotifyRead, hasBaseline: Boolean): List<String>? =
    if (hasBaseline || read.baselineIds.isEmpty()) null else read.baselineIds

class ConnectViewModel(application: Application) : AndroidViewModel(application) {

    private val container = application.container

    /** What every add route's offline guard reads connectivity from ([hasUsableNetwork]), rather
     *  than running out every host's timeouts. */
    private val app: Application get() = getApplication()

    private val _state = MutableStateFlow<ConnectState>(ConnectState.Idle)
    val state: StateFlow<ConnectState> = _state.asStateFlow()

    private val _importSignIn = MutableStateFlow<ImportSignIn>(ImportSignIn.None)
    /** Drives the per-account password prompt shown after a settings import (see [beginImportSignIn]). */
    val importSignIn: StateFlow<ImportSignIn> = _importSignIn.asStateFlow()

    private var oauthJob: Job? = null
    private var importOAuthJob: Job? = null

    // ---- IMAP/SMTP settings discovery (the autoconfig cascade, under today's form) ----

    private var imapDiscoveryJob: Job? = null

    /** The address the cascade last set out for — a job still running, or a verdict rendered.
     *  Either way the same address is not probed twice; a different one starts over. */
    private var imapDiscoveryEmail: String? = null

    private val _imapDiscovery = MutableStateFlow<MailAutoconfigResult.Found?>(null)

    /** The one discovered configuration awaiting the screen. Consumed once
     *  ([consumeImapDiscovery]): a field the user then clears must not refill itself. */
    val imapDiscovery: StateFlow<MailAutoconfigResult.Found?> = _imapDiscovery.asStateFlow()

    private val _discovery = MutableStateFlow<DiscoveryVerdict?>(null)

    /** The durable verdict of the last probe, beside the one-shot [imapDiscovery] and never in place
     *  of it: consumed, it would send step 2 back to the address step on every rotation. */
    internal val discovery: StateFlow<DiscoveryVerdict?> = _discovery.asStateFlow()

    private val _oauthDiscovery = MutableStateFlow<OAuthDiscovery?>(null)

    /** How the address' server says this account signs in, reused by the hand-over instead of
     *  asking twice. Beside [discovery], not inside it: a refusal to probe
     *  ([verdictAfterRefusal]) has nothing to say about OAuth. */
    internal val oauthDiscovery: StateFlow<OAuthDiscovery?> = _oauthDiscovery.asStateFlow()

    /** Run the autoconfig cascade for [email] in the background. Whether it may set out is
     *  [shouldDiscoverImapSettings]'s call: offline it does not start and records nothing, since
     *  `NotFound` is not the same as "still running". */
    internal fun discoverImapSettings(
        step: ConnectStep,
        route: ConnectRoute,
        preset: PresetForm,
        email: String,
    ) {
        // One read of the link, for both decisions below: read twice, the guard can refuse for want
        // of a network and the refusal be recorded as though the network were there.
        val online = hasUsableNetwork(app)
        if (!shouldDiscoverImapSettings(step, route, preset, email, online = online)) {
            // A refusal still has to answer the walk — and must record nothing when the refusal is
            // the missing network itself ([verdictAfterRefusal]).
            _discovery.value = verdictAfterRefusal(_discovery.value, email, online = online)
            return
        }
        val address = email.trim()
        if (address == imapDiscoveryEmail) return
        imapDiscoveryJob?.cancel()
        imapDiscoveryEmail = address
        imapDiscoveryJob = viewModelScope.launch {
            // No runCatching around the cascade: it owns its failures and rethrows cancellation.
            // The two probes run concurrently, or the step would cost 20 s instead of 10.
            val probe = probeAddressTogether(
                settings = { discoverMailAutoconfig(address) },
                oauth = { probeOAuthFor(address) },
            )
            val result = probe.settings
            // The OAuth answer before the verdict the walk steps on: the credentials step is drawn
            // the instant _discovery lands, and without it would flash a password field.
            _oauthDiscovery.value = probe.oauth
            _discovery.value = DiscoveryVerdict(address, result)
            _imapDiscovery.value = result as? MailAutoconfigResult.Found
        }
    }

    /** Ask the address' domain how it signs in — no credentials involved, so it can run at the
     *  address step. It rides with every cascade, on every step: gated on the step, a pass records
     *  a `Found` with no OAuth answer beside it and step 2 draws a bare wheel for ever. */
    private suspend fun probeOAuthFor(address: String): OAuthDiscovery {
        // The address' bare domain bounds what the discovery document may name, besides the host
        // asked. Read from `Jmap.autodiscoverHosts`, which `mailOAuthCandidates` also derives from.
        val addressDomain = Jmap.autodiscoverHosts(address).firstOrNull().orEmpty()
        val chosen = discoverOAuthAmong(mailOAuthCandidates(address)) {
            container.mailRepository.discoverOAuth(it, addressDomain)
        }
        return OAuthDiscovery(address, chosen)
    }

    /** Where the address step's button leads — [addressAdvance]'s call, with the link read here and
     *  now, airplane mode being switched on a screen that is not recomposing. No side effect: it
     *  also computes the button's `enabled`. */
    internal fun advanceFromAddress(email: String): AddressAdvance =
        addressAdvance(email, _discovery.value, _oauthDiscovery.value, online = hasUsableNetwork(app))

    /** Say that the device is offline, with the same sentence as the six sign-in routes' guards.
     *  A report, not a step: it must not move the user into the manual form. */
    internal fun reportOffline() {
        _state.value = ConnectState.Error(string(R.string.connect_offline))
    }

    /** The screen wrote the verdict into the fields: drop it so it is applied exactly once. */
    fun consumeImapDiscovery() {
        _imapDiscovery.value = null
    }

    /** The walk moved: whatever a finished attempt left goes with the step it belongs to.
     *  [clearsAttemptOnLeave] also decides which states must survive, as what stops a second submit. */
    internal fun clearFinishedAttempt() {
        if (clearsAttemptOnLeave(_state.value)) _state.value = ConnectState.Idle
    }

    init {
        observeOutlookSignIn()
        observeOAuthCodeSignIn()
    }

    fun connect(server: String, username: String, password: String, accountName: String) {
        if (_state.value is ConnectState.Connecting || _state.value is ConnectState.Discovering) return
        if (!hasUsableNetwork(app)) {
            _state.value = ConnectState.Error(string(R.string.connect_offline))
            return
        }
        _state.value = ConnectState.Connecting
        viewModelScope.launch { finishJmapConnect(server.trim(), username, password, accountName) }
    }

    /** Autodiscovery path (RFC 8620 §2.2): probe the email domain's `/.well-known/jmap`. If nothing
     *  responds, ask for the server manually; a credential rejection is reported as such. */
    fun connectAuto(email: String, password: String, accountName: String) {
        if (_state.value is ConnectState.Connecting || _state.value is ConnectState.Discovering) return
        if (!hasUsableNetwork(app)) {
            _state.value = ConnectState.Error(string(R.string.connect_offline))
            return
        }
        _state.value = ConnectState.Discovering
        viewModelScope.launch {
            val result = try {
                container.mailRepository.discoverJmapServer(email.trim(), password)
            } catch (cancelled: CancellationException) {
                // Leaving the screen cancels the probes: that is not "no server found", and the
                // NeedsServer below would be a form nobody asked for, on a screen that is gone.
                throw cancelled
            } catch (_: Throwable) {
                MailRepository.DiscoveryResult.NotFound
            }
            when (result) {
                is MailRepository.DiscoveryResult.Found -> {
                    _state.value = ConnectState.Connecting
                    finishJmapConnect(result.server, email, password, accountName)
                }
                MailRepository.DiscoveryResult.BadCredentials ->
                    _state.value = ConnectState.Error(getApplication<Application>().getString(R.string.connect_bad_credentials))
                MailRepository.DiscoveryResult.NotFound ->
                    _state.value = ConnectState.NeedsServer
            }
        }
    }

    /** API-token sign-in (#54): a server-generated Bearer token instead of a password. A blank
     *  [server] autodiscovers; otherwise the input is resolved tolerantly (host, URL, session URL). */
    fun connectToken(server: String, email: String, token: String, accountName: String) {
        if (busy()) return
        if (!hasUsableNetwork(app)) {
            _state.value = ConnectState.Error(string(R.string.connect_offline))
            return
        }
        val emailTrim = email.trim()
        val serverTrim = server.trim()
        if (serverTrim.isNotBlank()) {
            _state.value = ConnectState.Connecting
            viewModelScope.launch { finishTokenConnect(serverTrim, emailTrim, token, accountName) }
            return
        }
        _state.value = ConnectState.Discovering
        viewModelScope.launch {
            val result = try {
                container.mailRepository.discoverJmapServer(emailTrim, password = "", token = token)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                MailRepository.DiscoveryResult.NotFound
            }
            when (result) {
                is MailRepository.DiscoveryResult.Found -> {
                    _state.value = ConnectState.Connecting
                    finishTokenConnect(result.server, emailTrim, token, accountName)
                }
                MailRepository.DiscoveryResult.BadCredentials ->
                    _state.value = ConnectState.Error(string(R.string.connect_token_rejected))
                MailRepository.DiscoveryResult.NotFound ->
                    _state.value = ConnectState.NeedsServer
            }
        }
    }

    /** Resolve the server input with the token, validate, persist. Mirrors [finishJmapConnect]. */
    private suspend fun finishTokenConnect(server: String, email: String, token: String, accountName: String) {
        try {
            val resolved = container.mailRepository.resolveJmapServerInput(server, BearerAuth(token))
            // The token alone identifies the account server-side — Bearer auth never validates the
            // typed address, so adopt the session's own when it declares one.
            val address = runCatching {
                container.mailRepository.sessionIdentity(resolved, BearerAuth(token))
            }.getOrNull() ?: email
            // Re-adding the same mailbox resolves to the same server + adopted address, so the store
            // refreshes in place. Not filtered on authType: password then token is one account.
            val stored = container.accountStore.accounts()
            val created = resolveExistingLogin(stored, accountKeyOf(MailProtocol.JMAP, resolved, "", address)) == null
            // Blank id on purpose, even when re-adding: the real id comes back from persist and
            // [addAccountThenPrime] stamps it onto the credentials it primes with (#121).
            val probe = AccountCredentials(resolved, address, token, authType = AuthType.API_TOKEN)
            val added = addAccountThenPrime(
                probe = probe,
                validate = { container.mailRepository.testConnection(it).getOrThrow() },
                // One call either way: add() refreshes the login stored under this identity and
                // hands back its id, atomically.
                persist = {
                    AddedAccount(
                        container.accountStore.add(resolved, address, token, accountName.trim(), authType = AuthType.API_TOKEN),
                        created = created,
                    )
                },
                prime = { primeInbox(it) },
            )
            warnIfNotPrimed(added)
            // Surface linked sub-accounts before navigating, like the password path (#31). Only on
            // a real creation — see [finishJmapConnect] for why a re-add must not.
            if (created) container.mailRepository.reconcileLinkedAccountsAfterAdd(added.id)
            accountAddedToast(created, null)?.let { toast(it) }
            _state.value = ConnectState.Connected
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            // A 401/403 with a token means the token itself was rejected — say so.
            val code = (t as? JmapException)?.httpCode
            _state.value = ConnectState.Error(
                if (code == 401 || code == 403) string(R.string.connect_token_rejected)
                else t.message ?: t.javaClass.simpleName,
            )
        }
    }

    /** OAuth device flow (RFC 8628): discover the OAuth server for the email's domain, start a
     *  device authorization, show the user code, then poll until approval. The password is ignored. */
    fun connectOAuth(email: String, server: String, accountName: String) {
        if (busy()) return
        if (!hasUsableNetwork(app)) {
            _state.value = ConnectState.Error(string(R.string.connect_offline))
            return
        }
        val emailTrim = email.trim()
        // With a server entered (advanced), discover OAuth there; otherwise derive candidates.
        val candidates = if (server.isNotBlank()) listOf(server.trim()) else Jmap.autodiscoverHosts(emailTrim)
        _state.value = ConnectState.Discovering
        oauthJob = viewModelScope.launch {
            // Collect, don't stop at the first answer — unless the address step already asked
            // ([reusableOAuth]): a host answering once but not twice would look unsupported.
            val reused = reusableOAuth(_oauthDiscovery.value, emailTrim, server)
            // Same bound as the address step: even with a server typed by hand, what the document
            // may name stays the typed host or the address' own domain.
            val addressDomain = Jmap.autodiscoverHosts(emailTrim).firstOrNull().orEmpty()
            val discovered = reused?.let { listOf(it) }
                ?: collectOAuthHosts(candidates) { container.mailRepository.discoverOAuth(it, addressDomain) }
            // "No OAuth here" and "OAuth, but not a flow this app can drive" are different facts;
            // oauthDiscoveryFailure picks the sentence, and answers for a null metadata.
            val chosen = chooseOAuthHost(discovered)
            val advertised = chosen?.second
            val discoveryFailure = oauthDiscoveryFailure(advertised)
            if (chosen == null || discoveryFailure != null) {
                _state.value = ConnectState.Error(
                    string(discoveryFailure ?: requireNotNull(oauthDiscoveryFailure(null))),
                )
                return@launch
            }
            val (host, found) = chosen
            // Which grant is chooseOAuthGrant's call, and it prefers the device flow. The code grant
            // hands over to the app-scoped driver, since the sign-in comes back through MainActivity.
            if (chooseOAuthGrant(found) == OAuthGrant.AUTHORIZATION_CODE) {
                // By name: `email` and `accountName` are adjacent Strings and a swap compiles.
                container.oauthCodeSignIn.start(
                    host = host,
                    metadata = found,
                    email = emailTrim,
                    accountName = accountName,
                )
                return@launch
            }
            val start = runCatching { container.mailRepository.startDeviceAuthorization(found) }
            val device = start.getOrNull()
            if (device == null) {
                // A transport failure carries no oauthError and falls back to the generic sentence.
                val spec = oauthFailureSpec(
                    error = (start.exceptionOrNull() as? JmapException)?.oauthError.orEmpty(),
                    description = "",
                    aadstsCode = null,
                    fallbackRes = R.string.connect_oauth_failed,
                )
                _state.value = ConnectState.Error(
                    if (spec.arg == null) string(spec.resId) else string(spec.resId, spec.arg),
                )
                return@launch
            }
            // emailTrim travels on: the approval page can pre-fill it (#55) instead of asking
            // for the address the user typed one screen ago.
            _state.value = ConnectState.AwaitingApproval(
                device.userCode, device.verificationUri, device.verificationUriComplete, emailTrim,
            )
            pollForToken(host, found, device, emailTrim, accountName)
        }
    }

    /** Map [pollDeviceGrant]'s verdict onto the screen. The waiting itself is not this body's. */
    private suspend fun pollForToken(
        host: String,
        metadata: OAuthMetadata,
        device: DeviceAuthorization,
        email: String,
        accountName: String,
    ) {
        // An unreadable poll must not leave this coroutine: an uncaught throw in viewModelScope
        // takes the app down. Here and not in the loop, which catches nothing, so Cancel still ends.
        val verdict = try {
            pollDeviceGrant(
                // No clamp here: the one-second floor is pollDeviceGrant's, where a test runs it.
                intervalSeconds = device.interval.toLong(),
                expiresInSeconds = device.expiresIn.toLong(),
            ) { container.mailRepository.pollDeviceToken(metadata, device.deviceCode) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            val spec = oauthFailureSpec(
                error = (t as? JmapException)?.oauthError.orEmpty(),
                description = "",
                aadstsCode = null,
                fallbackRes = R.string.connect_oauth_failed,
            )
            _state.value = ConnectState.Error(
                if (spec.arg == null) string(spec.resId) else string(spec.resId, spec.arg),
            )
            return
        }
        when (verdict) {
            is DevicePollVerdict.Approved -> {
                _state.value = ConnectState.Connecting
                val outcome = runCatching {
                    container.mailRepository.addOAuthAccount(host, email, metadata, verdict.tokens, accountName)
                }
                _state.value = outcome.fold(
                    onSuccess = { created ->
                        accountAddedToast(created, null)?.let { toast(it) }
                        ConnectState.Connected
                    },
                    onFailure = { ConnectState.Error(it.message ?: it.javaClass.simpleName) },
                )
            }
            is DevicePollVerdict.Refused -> {
                // Not every terminal poll is a refusal by the reader: say what the server said.
                _state.value = ConnectState.Error(oauthFailureMessage(getApplication<Application>(), verdict.failure))
            }
            // Which end went quiet is oauthRanOutMessage's choice, not this screen's.
            is DevicePollVerdict.RanOut ->
                _state.value = ConnectState.Error(string(oauthRanOutMessage(verdict.everReachedAServer)))
        }
    }

    /** OAuth device flow for Outlook/Microsoft over IMAP+SMTP with XOAUTH2, delegated to the
     *  app-scoped [OutlookSignIn] so the poll survives the round-trip to the browser. */
    fun connectOutlookOAuth(email: String, accountName: String) {
        if (busy()) return
        // The two facts below outrank being offline: an unconfigured build and an empty address
        // stay wrong once the network is back.
        if (!OAuthProvider.MICROSOFT.isConfigured) {
            _state.value = ConnectState.Error(string(R.string.connect_oauth_provider_unconfigured))
            return
        }
        val emailTrim = email.trim()
        if (emailTrim.isBlank()) {
            _state.value = ConnectState.Error(string(R.string.connect_oauth_need_email))
            return
        }
        if (!hasUsableNetwork(app)) {
            _state.value = ConnectState.Error(string(R.string.connect_offline))
            return
        }
        container.outlookSignIn.start(emailTrim, accountName)
    }

    /**
     * #1693 The fleet's mail accounts come from Cloud Account, the one app that signs in: this asks its provider (off the main
     * thread) and adds what it holds through the vault import's writer. [onResult] gets one sentence for the snackbar; a missing or
     * refusing Cloud Account is that sentence, never a crash, and no password is in it.
     */
    fun takeAccountsFromCloudAccount(onResult: (String) -> Unit) {
        viewModelScope.launch {
            val out = withContext(Dispatchers.IO) {
                runCatching { app.sterna.fleet.MailAccountSync.pull(app, container.accountStore) }
                    .getOrElse { app.sterna.fleet.MailAccountSync.Outcome.Refused("Cloud Account could not be read (${it.javaClass.simpleName})") }
            }
            onResult(
                when (out) {
                    is app.sterna.fleet.MailAccountSync.Outcome.Added ->
                        if (out.count > 0) app.getString(R.string.connect_cloud_account_added, out.count, out.total)
                        else app.getString(R.string.connect_cloud_account_nothing_new)
                    app.sterna.fleet.MailAccountSync.Outcome.AccountMissing ->
                        com.diegonmarcos.superapp.fleetendpoints.SignInContract.missing(com.diegonmarcos.superapp.fleetendpoints.MailAccountsContract.PROVIDER).line
                    is app.sterna.fleet.MailAccountSync.Outcome.Refused -> out.line
                },
            )
        }
    }

    /** Mirror the app-scoped Outlook sign-in into this screen's state. */
    private fun observeOutlookSignIn() {
        viewModelScope.launch {
            container.outlookSignIn.progress.collect { p ->
                _state.value = when (p) {
                    OutlookProgress.Idle -> return@collect
                    OutlookProgress.Starting -> ConnectState.Discovering
                    // No login hint on this one: the Microsoft device page is out of #55's
                    // scope, and OutlookProgress carries no address to give it anyway.
                    is OutlookProgress.AwaitingApproval ->
                        ConnectState.AwaitingApproval(
                            p.userCode, p.verificationUri, p.verificationUriComplete, null,
                        )
                    OutlookProgress.Connecting -> ConnectState.Connecting
                }
            }
        }
        viewModelScope.launch {
            container.outlookSignIn.outcomes.collect { o ->
                _state.value = when (o) {
                    OutlookOutcome.Success -> ConnectState.Connected
                    is OutlookOutcome.Error -> ConnectState.Error(o.message)
                }
            }
        }
    }

    /** Mirror the app-scoped authorization-code sign-in into this screen's state (#55). */
    private fun observeOAuthCodeSignIn() {
        viewModelScope.launch {
            container.oauthCodeSignIn.progress.collect { p ->
                _state.value = when (p) {
                    // Idle is where the driver sits before and after a sign-in: adopting it would
                    // wipe whatever this screen is showing, including the error just emitted.
                    OAuthCodeProgress.Idle -> return@collect
                    is OAuthCodeProgress.AwaitingBrowser -> ConnectState.AwaitingBrowser(p.authorizationUrl)
                    OAuthCodeProgress.Connecting -> ConnectState.Connecting
                }
            }
        }
        viewModelScope.launch {
            container.oauthCodeSignIn.outcomes.collect { o ->
                _state.value = when (o) {
                    OAuthCodeOutcome.Success -> ConnectState.Connected
                    is OAuthCodeOutcome.Error -> ConnectState.Error(o.message)
                }
            }
        }
    }

    // ---- post-import per-account sign-in ----

    /** One imported account still awaiting sign-in. [provider] is set only for OAUTH accounts on a
     *  known provider (Microsoft); null means basic-auth or an unknown XOAUTH2 server. */
    data class PendingAccount(
        val id: String,
        val label: String,
        val email: String,
        val authType: AuthType,
        val provider: OAuthProvider?,
    )

    /** State of the "sign in to your imported accounts" step: a [Listing] of pending accounts, a tap
     *  selects one, a sign-in drops it off, a swipe dismisses it. An emptied list enters the app. */
    sealed interface ImportSignIn {
        data object None : ImportSignIn
        data class Listing(val pending: List<PendingAccount>, val selected: SignInTarget? = null) : ImportSignIn
        data object Done : ImportSignIn
        data class Approval(val userCode: String, val verificationUri: String, val verificationUriComplete: String?)
    }

    data class SignInTarget(
        val account: PendingAccount,
        val verifying: Boolean = false,
        val error: String? = null,
        /** Non-null while this OAuth account is awaiting browser approval (device flow). */
        val approval: ImportSignIn.Approval? = null,
        /** After a failed/declined OAuth sign-in, offer switching this account to an app password. */
        val offerAppPasswordFallback: Boolean = false,
        /** Render a password field even though the account imported as OAuth (fallback chosen). */
        val forcePassword: Boolean = false,
    )

    /** The inert imported accounts still awaiting sign-in, as [StoredAccount]s for the list UI.
     *  Re-read on each access; the [importSignIn] flow drives the recomposition that re-reads it. */
    val pendingStoredAccounts: List<StoredAccount>
        get() = container.accountStore.pendingImportAccounts()

    /** Imported accounts still awaiting sign-in: inert (no stored secret), any auth type. */
    private fun pendingAccounts(): List<PendingAccount> =
        container.accountStore.pendingImportAccounts().map { a ->
            PendingAccount(
                id = a.id, label = a.label(), email = a.username,
                authType = a.authType,
                provider = if (a.authType == AuthType.OAUTH) OAuthProvider.forImapHost(a.imapHost) else null,
            )
        }

    /** After an import, show the user-driven list of accounts to sign in. With nothing pending,
     *  enter the app (something is already signed in) or fall back to the add-account form. */
    fun beginImportSignIn() {
        val p = pendingAccounts()
        _importSignIn.value = if (p.isEmpty()) finishOrIdle() else ImportSignIn.Listing(p)
    }

    /** "Add account" escape from the pending-imports list. With every imported account deferred
     *  there is no signed-in account, so without this exit the listing is a dead end. */
    fun leaveImportListing() {
        if (currentListing()?.selected == null) _importSignIn.value = ImportSignIn.None
    }

    /** Re-enter the sign-in list for any still-unauthenticated imported account. Does nothing when
     *  there are none, and never overrides a list already on screen. */
    fun resumeImportSignIn() {
        if (_importSignIn.value is ImportSignIn.Listing) return
        val p = pendingAccounts()
        if (p.isNotEmpty()) _importSignIn.value = ImportSignIn.Listing(p)
    }

    private fun currentListing() = _importSignIn.value as? ImportSignIn.Listing

    /** Recompute the pending list, keeping [keepSelected] only if that account is still pending.
     *  When nothing is left, finish (enter the app) or fall back to the add-account form. */
    private fun refreshListing(keepSelected: SignInTarget? = currentListing()?.selected) {
        val p = pendingAccounts()
        _importSignIn.value =
            if (p.isEmpty()) finishOrIdle()
            else ImportSignIn.Listing(p, keepSelected?.takeIf { s -> p.any { it.id == s.account.id } })
    }

    private fun finishOrIdle(): ImportSignIn =
        if (container.accountStore.accounts().any { container.accountStore.credentials(it.id) != null }) ImportSignIn.Done
        else ImportSignIn.None

    fun selectImportAccount(id: String) {
        val listing = currentListing() ?: return
        val account = listing.pending.firstOrNull { it.id == id } ?: return
        _importSignIn.value = listing.copy(selected = SignInTarget(account))
    }

    fun closeImportAccount() {
        importOAuthJob?.cancel(); importOAuthJob = null
        refreshListing(keepSelected = null)
    }

    /** Swipe-dismiss: disconnect and REMOVE the imported account entirely (it never appears in the
     *  account list; the user can re-import it later). Undoable via [restoreImportAccount]. */
    fun dismissImportAccount(id: String) {
        container.accountStore.remove(id)
        if (currentListing()?.selected?.account?.id == id) closeImportAccount() else refreshListing()
    }

    fun restoreImportAccount(account: StoredAccount) {
        container.accountStore.readdImportedAccount(account)
        refreshListing()
    }

    private fun updateSelected(block: (SignInTarget) -> SignInTarget) {
        val l = currentListing() ?: return
        val s = l.selected ?: return
        _importSignIn.value = l.copy(selected = block(s))
    }

    private fun closeImportAccountThenAdvance() = refreshListing(keepSelected = null)

    fun submitImportPassword(password: String) {
        val target = currentListing()?.selected ?: return
        if (target.verifying || password.isBlank()) return
        val account = container.accountStore.account(target.account.id) ?: return
        if (!hasUsableNetwork(app)) {
            updateSelected { it.copy(error = string(R.string.connect_offline)) }
            return
        }
        updateSelected { it.copy(verifying = true, error = null) }
        viewModelScope.launch {
            container.mailRepository.testConnection(credentialsFor(account, password)).fold(
                onSuccess = {
                    container.accountStore.updatePassword(target.account.id, password)
                    container.accountStore.setImportPending(target.account.id, false)
                    closeImportAccountThenAdvance()
                },
                onFailure = { e ->
                    val msg = e.message ?: string(R.string.connect_bad_credentials)
                    val authRejected = msg.contains("LOGIN", true) || msg.contains("AUTHENTICATE", true) || msg.contains("credential", true)
                    updateSelected {
                        it.copy(
                            verifying = false,
                            error = if (authRejected) msg + " " + string(R.string.connect_provider_app_password_note) else msg,
                            offerAppPasswordFallback = authRejected,
                        )
                    }
                },
            )
        }
    }

    fun startImportOAuth() {
        val target = currentListing()?.selected ?: return
        val provider = target.account.provider ?: return
        if (target.verifying || target.approval != null) return
        if (!hasUsableNetwork(app)) {
            updateSelected { it.copy(error = string(R.string.connect_offline)) }
            return
        }
        updateSelected { it.copy(verifying = true, error = null, offerAppPasswordFallback = false) }
        importOAuthJob = viewModelScope.launch {
            val result = container.mailRepository.runProviderDeviceFlow(provider) { device ->
                updateSelected {
                    it.copy(
                        verifying = false,
                        approval = ImportSignIn.Approval(device.userCode, device.verificationUri, device.verificationUriComplete),
                    )
                }
            }
            result.fold(
                onSuccess = { tokens ->
                    updateSelected { it.copy(approval = null, verifying = true, error = null) }
                    runCatching { container.mailRepository.signInImportedOAuth(target.account.id, provider, tokens) }.fold(
                        onSuccess = {
                            container.accountStore.setImportPending(target.account.id, false)
                            refreshListing(keepSelected = null)
                        },
                        onFailure = { e ->
                            updateSelected {
                                it.copy(
                                    verifying = false,
                                    error = e.message ?: string(R.string.connect_oauth_failed),
                                    offerAppPasswordFallback = true,
                                )
                            }
                        },
                    )
                },
                onFailure = { e ->
                    val msg = if (e is OAuthDeniedException) oauthFailureMessage(getApplication<Application>(), e.failure)
                    else (e.message ?: string(R.string.connect_oauth_failed))
                    updateSelected { it.copy(approval = null, verifying = false, error = msg, offerAppPasswordFallback = true) }
                },
            )
        }
    }

    /** Cancel an in-progress import device flow; the account stays selected and retryable. */
    fun cancelImportOAuth() {
        importOAuthJob?.cancel(); importOAuthJob = null
        updateSelected { it.copy(approval = null, verifying = false) }
    }

    /** Fallback: switch the selected account to manual app-password auth and show its password field. */
    fun switchImportToAppPassword() {
        importOAuthJob?.cancel(); importOAuthJob = null
        val target = currentListing()?.selected ?: return
        container.accountStore.convertToBasicAuth(target.account.id)
        updateSelected {
            it.copy(
                account = it.account.copy(authType = AuthType.BASIC, provider = null),
                forcePassword = true, offerAppPasswordFallback = false,
                approval = null, error = null, verifying = false,
            )
        }
    }

    private fun credentialsFor(a: StoredAccount, password: String) = AccountCredentials(
        server = a.server,
        username = a.username,
        password = password,
        id = a.id,
        protocol = a.protocol,
        imap = if (a.protocol == MailProtocol.IMAP) MailEndpoint(a.imapHost, a.imapPort, a.imapSecurity) else null,
        smtp = if (a.protocol == MailProtocol.IMAP) MailEndpoint(a.smtpHost, a.smtpPort, a.smtpSecurity) else null,
    )

    fun cancelOAuth() {
        oauthJob?.cancel()
        oauthJob = null
        container.outlookSignIn.cancel()
        // Drops the pending authorization with it: after Cancel, a redirect that still arrives
        // (the browser was left open on the sign-in page) finds nothing and exchanges nothing.
        container.oauthCodeSignIn.cancel()
        _state.value = ConnectState.Idle
    }

    private fun busy() = _state.value is ConnectState.Connecting ||
        _state.value is ConnectState.Discovering ||
        _state.value is ConnectState.AwaitingApproval ||
        _state.value is ConnectState.AwaitingBrowser

    /** Say [resId] on the Application, so the sentence survives the screen navigating away the
     *  moment an add succeeds. */
    private fun toast(@StringRes resId: Int) {
        Toast.makeText(app, string(resId), Toast.LENGTH_LONG).show()
    }
    // It shows [resId] rather than naming a resource here, which would put the same sentence on
    // every add again — the defect accountAddedToast exists to remove.

    private fun string(resId: Int) = getApplication<Application>().getString(resId)
    private fun string(resId: Int, vararg args: Any) = getApplication<Application>().getString(resId, *args)

    /** An add whose first inbox load failed still added the account and still reaches
     *  [ConnectState.Connected]: the cache fills on the first refresh. */
    private fun warnIfNotPrimed(added: PrimedAccount) {
        added.primeFailure?.let {
            android.util.Log.w(
                "SternaConnect",
                "account ${added.id} was added; loading its inbox failed and will be retried on the next refresh",
                it,
            )
        }
    }

    private suspend fun primeInbox(credentials: AccountCredentials) {
        val meta = container.mailRepository.refresh(credentials)
        container.accountStore.saveInboxMetaFor(
            credentials.id, meta.mailboxId, meta.mailboxName, meta.accountName, meta.unreadCount,
        )
        // Last, and only after a refresh that succeeded. Nothing above may move below it and no
        // runCatching may wrap it: a partial seed marks the folder known, and the next pass
        // announces the rest as new mail and unarchives it server-side.
        seedInboxBaseline(credentials.id, meta.mailboxId)
    }

    /** Mark the inbox this add just loaded as "already seen", from the page the add itself read.
     *  What to write is [baselineForAddedInbox]'s decision. */
    private suspend fun seedInboxBaseline(accountId: String, mailboxId: String) {
        val read = container.mailRepository.notifyRead(accountId, mailboxId)
        val baseline = baselineForAddedInbox(read, NewMailNotifier.hasBaseline(app, accountId, mailboxId))
            ?: return
        NewMailNotifier.seed(app, accountId, mailboxId, baseline)
    }

    /** Validate against [server], persist on success. Runs in the caller's coroutine. */
    private suspend fun finishJmapConnect(server: String, username: String, password: String, accountName: String) {
        try {
            // Asked before the round-trip, on the identity the store keys on: add() refreshes a
            // login already there.
            val stored = container.accountStore.accounts()
            val created = resolveExistingLogin(stored, accountKeyOf(MailProtocol.JMAP, server, "", username)) == null
            // Blank id on purpose: this copy only ever proves the credentials. The cache is
            // primed from the id-stamped copy [addAccountThenPrime] builds (#121).
            val probe = AccountCredentials(server, username.trim(), password)
            val added = addAccountThenPrime(
                probe = probe,
                validate = { container.mailRepository.testConnection(it).getOrThrow() },
                // A blank name falls back to the address.
                persist = {
                    AddedAccount(
                        container.accountStore.add(server, username, password, accountName.trim()),
                        created = created,
                    )
                },
                prime = { primeInbox(it) },
            )
            warnIfNotPrimed(added)
            // Surface linked sub-accounts before navigating (#31) — only on a real create. A re-add
            // may have moved the login from OAuth to a password; reconciling then hands
            // `diffLinkedAccounts` a session of the other auth family, and eviction reads the raw
            // discovered list (AccountStore), purging every sub-account (#129).
            if (created) container.mailRepository.reconcileLinkedAccountsAfterAdd(added.id)
            accountAddedToast(created, null)?.let { toast(it) }
            _state.value = ConnectState.Connected
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            // Fastmail's endpoint refuses password auth outright (API tokens only, #54):
            // a 401 from it gets the same steer as the inline hint, not just a bare error.
            val msg = t.message ?: t.javaClass.simpleName
            val fastmail401 = (t as? JmapException)?.httpCode == 401 && isFastmailTarget(username, server)
            _state.value = ConnectState.Error(
                if (fastmail401) msg + " " + string(R.string.connect_fastmail_token_hint) else msg,
            )
        }
    }

    fun connectImap(
        username: String,
        password: String,
        accountName: String,
        imapHost: String,
        imapPort: Int,
        imapSecurity: ConnectionSecurity,
        smtpHost: String,
        smtpPort: Int,
        smtpSecurity: ConnectionSecurity,
    ) {
        if (_state.value is ConnectState.Connecting) return
        if (!hasUsableNetwork(app)) {
            _state.value = ConnectState.Error(string(R.string.connect_offline))
            return
        }
        _state.value = ConnectState.Connecting
        viewModelScope.launch {
            try {
                // An IMAP account is keyed on its IMAP host, not on the (blank) server. Asked
                // before the network round-trip.
                val stored = container.accountStore.accounts()
                val created = resolveExistingLogin(stored, accountKeyOf(MailProtocol.IMAP, "", imapHost, username)) == null
                // Blank id on purpose: proving credentials only. See [addAccountThenPrime] (#121).
                val probe = AccountCredentials(
                    server = "",
                    username = username.trim(),
                    password = password,
                    protocol = MailProtocol.IMAP,
                    imap = MailEndpoint(imapHost.trim(), imapPort, imapSecurity),
                    smtp = MailEndpoint(smtpHost.trim(), smtpPort, smtpSecurity),
                )
                val added = addAccountThenPrime(
                    probe = probe,
                    validate = { container.mailRepository.testConnection(it).getOrThrow() },
                    persist = {
                        AddedAccount(
                            container.accountStore.add(
                                server = "",
                                username = username,
                                password = password,
                                accountName = accountName.trim(),
                                protocol = MailProtocol.IMAP,
                                imapHost = imapHost,
                                imapPort = imapPort,
                                imapSecurity = imapSecurity,
                                smtpHost = smtpHost,
                                smtpPort = smtpPort,
                                smtpSecurity = smtpSecurity,
                            ),
                            created = created,
                        )
                    },
                    prime = { primeInbox(it) },
                )
                warnIfNotPrimed(added)
                accountAddedToast(created, null)?.let { toast(it) }
                _state.value = ConnectState.Connected
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                // Refused credentials point the user at app passwords (RFC 5530 code when sent).
                val code = (t as? ImapException)?.responseCode
                _state.value = ConnectState.Error(
                    when {
                        imapAuthRejected(code, t.message.orEmpty()) ->
                            string(R.string.connect_bad_credentials) + " " +
                                string(R.string.connect_provider_app_password_note)
                        // The server named its failure: show the CODE, never the response body —
                        // a server's free text has already leaked a bearer token once (8e942069).
                        code != null -> string(R.string.connect_imap_refused, code)
                        else -> t.message ?: t.javaClass.simpleName
                    },
                )
            }
        }
    }
}
