package com.diegonmarcos.cloudaccount.hub

import android.app.Activity
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.staticCompositionLocalOf

/** Asks the owner to prove it is them (biometric or the screen lock). [done] is called exactly once. */
fun interface DeviceAuth {
    fun authenticate(reason: String, done: (Boolean) -> Unit)
}

/**
 * The ONLY way a secret value reaches the screen or the clipboard: [read] is not even called until [DeviceAuth]
 * said yes. A refusal (or no screen lock on the phone) leaves the value where it is.
 */
class RevealGate(private val auth: DeviceAuth) {
    fun reveal(reason: String, read: () -> String?, show: (String) -> Unit, denied: () -> Unit = {}) {
        auth.authenticate(reason) { ok ->
            if (!ok) { denied(); return@authenticate }
            val v = read()
            if (v.isNullOrEmpty()) denied() else show(v)
        }
    }

    /**
     * [reveal] for a value that has to be fetched over the network: [read] runs on the IO dispatcher, and (as with
     * [reveal]) not at all until [DeviceAuth] said yes. [show] and [denied] run on [scope]'s dispatcher (main).
     */
    fun revealOnIo(reason: String, scope: kotlinx.coroutines.CoroutineScope, read: () -> String?, show: (String) -> Unit, denied: () -> Unit = {}) {
        auth.authenticate(reason) { ok ->
            if (!ok) { denied(); return@authenticate }
            scope.launch {
                val v = withContext(Dispatchers.IO) { read() }
                if (v.isNullOrEmpty()) denied() else show(v)
            }
        }
    }
}

/**
 * The phone's device auth: BiometricPrompt with the screen lock allowed (API 29+), the keyguard's confirm screen
 * before that. A phone with no screen lock at all cannot prove anything, so it is refused (fail closed).
 * [launchConfirm] starts the keyguard intent for a result and hands back whether it was confirmed.
 */
class SystemDeviceAuth(
    private val activity: Activity,
    private val launchConfirm: (Intent, (Boolean) -> Unit) -> Unit,
) : DeviceAuth {
    override fun authenticate(reason: String, done: (Boolean) -> Unit) {
        val once = AtomicBoolean(false)
        val finish = { ok: Boolean -> if (once.compareAndSet(false, true)) done(ok) }
        val km = activity.getSystemService(KeyguardManager::class.java)
        if (km == null || !km.isDeviceSecure) { finish(false); return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val b = android.hardware.biometrics.BiometricPrompt.Builder(activity)
                .setTitle("Cloud Account")
                .setDescription(reason)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                b.setAllowedAuthenticators(
                    android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                )
            } else {
                @Suppress("DEPRECATION") b.setDeviceCredentialAllowed(true)
            }
            b.build().authenticate(CancellationSignal(), activity.mainExecutor,
                object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult?) = finish(true)
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) = finish(false)
                })
        } else {
            @Suppress("DEPRECATION")
            val i = km.createConfirmDeviceCredentialIntent("Cloud Account", reason)
            if (i == null) finish(false) else launchConfirm(i, finish)
        }
    }
}

/**
 * Copy a secret, flagged sensitive (the keyboard's clipboard strip and the system preview hide it), and clear it
 * again after [CLEAR_AFTER_MS] unless something else was copied since.
 */
object SecretClipboard {
    const val CLEAR_AFTER_MS = 30_000L
    private const val SENSITIVE = "android.content.extra.IS_SENSITIVE"
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** [schedule] defers the clear; the phone's is the main looper, a test runs it by hand. */
    fun copy(ctx: Context, value: String, schedule: (Long, () -> Unit) -> Unit = { ms, r -> main.postDelayed(r, ms) }): String {
        val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return ""
        val label = "Cloud Account secret ${System.nanoTime()}"
        val clip = ClipData.newPlainText(label, value)
        clip.description.extras = PersistableBundle().apply { putBoolean(SENSITIVE, true) }
        cm.setPrimaryClip(clip)
        schedule(CLEAR_AFTER_MS) { clearIfOurs(ctx, label) }
        return label
    }

    fun clearIfOurs(ctx: Context, label: String) {
        val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return
        if (cm.primaryClipDescription?.label?.toString() != label) return
        val cleared = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && runCatching { cm.clearPrimaryClip() }.isSuccess
        if (!cleared) cm.setPrimaryClip(ClipData.newPlainText("", ""))
    }
}

/** The device auth every reveal and copy goes through. The default refuses: no host, no reveal. */
val LocalDeviceAuth = staticCompositionLocalOf<DeviceAuth> { DeviceAuth { _, done -> done(false) } }
