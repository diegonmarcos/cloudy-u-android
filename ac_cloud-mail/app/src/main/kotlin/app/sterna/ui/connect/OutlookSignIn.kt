package app.sterna.ui.connect

import android.content.Context
import android.widget.Toast
import app.sterna.R
import app.sterna.core.data.mail.MailRepository
import app.sterna.core.data.mail.OAuthProvider
import app.sterna.core.jmap.DeviceTokenResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** In-progress state of an Outlook device-flow sign-in (drives the approval panel). */
sealed interface OutlookProgress {
    data object Idle : OutlookProgress
    data object Starting : OutlookProgress
    data class AwaitingApproval(
        val userCode: String,
        val verificationUri: String,
        val verificationUriComplete: String?,
    ) : OutlookProgress
    data object Connecting : OutlookProgress
}

/** One-shot terminal result of a sign-in (collected only while a screen is alive). */
sealed interface OutlookOutcome {
    data object Success : OutlookOutcome
    data class Error(val message: String) : OutlookOutcome
}

/**
 * App-scoped driver for the Outlook (Microsoft) OAuth device flow. Runs start → poll → add-account
 */
class OutlookSignIn(
    private val repo: MailRepository,
    private val scope: CoroutineScope,
    private val appContext: Context,
) {
    private val _progress = MutableStateFlow<OutlookProgress>(OutlookProgress.Idle)
    val progress: StateFlow<OutlookProgress> = _progress.asStateFlow()

    private val _outcomes = MutableSharedFlow<OutlookOutcome>(extraBufferCapacity = 1)
    val outcomes: SharedFlow<OutlookOutcome> = _outcomes.asSharedFlow()

    private var job: Job? = null

    fun start(email: String, accountName: String) {
        if (job?.isActive == true) return
        val provider = OAuthProvider.MICROSOFT
        job = scope.launch {
            try {
                _progress.value = OutlookProgress.Starting
                val device = repo.startProviderDeviceAuth(provider, email)
                _progress.value = OutlookProgress.AwaitingApproval(
                    device.userCode, device.verificationUri, device.verificationUriComplete,
                )
                var interval = device.interval.coerceAtLeast(1).toLong()
                val deadline = System.currentTimeMillis() + device.expiresIn * 1000L
                while (System.currentTimeMillis() < deadline) {
                    delay(interval * 1000)
                    when (val result = repo.pollProviderToken(provider, device.deviceCode)) {
                        is DeviceTokenResult.Success -> {
                            _progress.value = OutlookProgress.Connecting
                            runCatching { repo.addOAuthImapAccount(provider, email, result.tokens, accountName) }
                                .onSuccess { created ->
                                    // Same rule as every other add route: an add that landed on an
                                    // account already there says so ([accountAddedToast]).
                                    accountAddedToast(created, R.string.connect_outlook_added)
                                        ?.let { toast(appContext.getString(it)) }
                                    _outcomes.emit(OutlookOutcome.Success)
                                }
                                .onFailure {
                                    _outcomes.emit(OutlookOutcome.Error(it.message ?: it.javaClass.simpleName))
                                }
                            _progress.value = OutlookProgress.Idle
                            return@launch
                        }
                        DeviceTokenResult.Pending -> Unit
                        DeviceTokenResult.SlowDown -> interval += 5
                        is DeviceTokenResult.Failed -> {
                            _outcomes.emit(OutlookOutcome.Error(appContext.getString(R.string.connect_oauth_denied)))
                            _progress.value = OutlookProgress.Idle
                            return@launch
                        }
                    }
                }
                _outcomes.emit(OutlookOutcome.Error(appContext.getString(R.string.connect_oauth_expired)))
                _progress.value = OutlookProgress.Idle
            } catch (t: Throwable) {
                _outcomes.emit(OutlookOutcome.Error(t.message ?: t.javaClass.simpleName))
                _progress.value = OutlookProgress.Idle
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _progress.value = OutlookProgress.Idle
    }

    private suspend fun toast(message: String) = withContext(Dispatchers.Main) {
        Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
    }
}
