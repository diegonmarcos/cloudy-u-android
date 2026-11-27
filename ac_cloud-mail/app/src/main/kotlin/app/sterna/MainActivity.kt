package app.sterna

import android.content.Intent
import android.net.MailTo
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import com.diegonmarcos.superapp.bottomnav.FleetChrome
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.appcompat.app.AppCompatActivity
import app.sterna.core.data.settings.LIST_MONOGRAM_DEFAULT
import app.sterna.core.data.settings.ListDensity
import app.sterna.core.data.settings.PreviewLines
import app.sterna.core.data.settings.PURE_BLACK_DEFAULT
import app.sterna.core.data.settings.AppStyle
import app.sterna.core.data.settings.ThemeMode
import app.sterna.ui.SternaApp
import app.sterna.ui.compose.ComposerResumeSlot
import app.sterna.ui.message.NavFadeGuard
import app.sterna.ui.components.LocalListDensity
import app.sterna.ui.components.LocalListMonogram
import app.sterna.ui.components.LocalPreviewLines
import app.sterna.ui.theme.SternaTheme

class MainActivity : AppCompatActivity() {
    /** A mailto: link waiting to open the compose screen (#15). Set from the launch intent or
     *  [onNewIntent], consumed by the NavHost once it has navigated. */
    private val pendingMailto = androidx.compose.runtime.mutableStateOf<MailtoDraft?>(null)

    /** A new-mail notification tap waiting to open that message (Codeberg #17 follow-up). Same
     *  singleTask/onNewIntent plumbing as [pendingMailto]. */
    private val pendingEmailOpen = androidx.compose.runtime.mutableStateOf<EmailOpenTarget?>(null)

    /** A home-screen widget tap waiting to put the list on the unified inbox (#112). Same one-shot
     *  discipline as the two above: an order carried out once, not a state the activity is in. */
    private val pendingUnifiedOpen = androidx.compose.runtime.mutableStateOf(false)

    /** #1814 A Configs launcher shortcut ("Configs > General" / "Configs > About": the lib's FleetConfigsActivity, aliased to this activity in the
     *  manifest) waiting to open that page inside the settings tree. */
    private val pendingConfigsPage = androidx.compose.runtime.mutableStateOf<String?>(null)

    /** Read here, on its own, so both the cold start and the singleTask re-entry are covered; the extra is stripped once read. */
    override fun onResume() {
        super.onResume()
        val page = intent?.getStringExtra(com.diegonmarcos.superapp.fleetconfigs.FleetConfigsActivity.EXTRA_PAGE) ?: return
        intent.removeExtra(com.diegonmarcos.superapp.fleetconfigs.FleetConfigsActivity.EXTRA_PAGE)
        pendingConfigsPage.value = page
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        app.sterna.core.data.mail.ColdStart.arm() // #1952: rows from the store before the first server call
        super.onCreate(savedInstanceState)
        FleetChrome.apply(this)
        // Only on a real launch. The activity is singleTask, so the intent that opened a
        // notification stays the activity's intent for good, and re-parsing it on every recreation
        // would re-navigate to that message on top of whatever the user is doing.
        if (savedInstanceState == null) {
            pendingMailto.value = parseMailto(intent) ?: parseShare(intent)
            pendingEmailOpen.value = parseEmailOpen(intent)
            pendingUnifiedOpen.value = parseUnifiedOpen(intent)
            consumeOAuthRedirect()
            // Sweep the orphaned composer resumes. With no saved state there is no
            // `rememberSaveable` left that could consume any slot, so every file still sitting
            ComposerResumeSlot.sweep(filesDir)
        }
        val settings = application.container.settingsRepository
        setContent {
            val themeMode by settings.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            val dynamicColor by settings.dynamicColor.collectAsState(initial = false)
            val density by settings.listDensity.collectAsState(initial = ListDensity.NORMAL)
            val previewLines by settings.previewLines.collectAsState(initial = PreviewLines.ONE)
            // LIST_MONOGRAM_DEFAULT, not a literal, for the same reason.
            val listMonogram by settings.listMonogram.collectAsState(initial = LIST_MONOGRAM_DEFAULT)
            // PURE_BLACK_DEFAULT, not a literal, for the same reason: one that drifts repaints the
            // whole theme a frame after launch.
            val pureBlack by settings.pureBlack.collectAsState(initial = PURE_BLACK_DEFAULT)
            val appStyle by settings.appStyle.collectAsState(initial = AppStyle.CLOUD_DARK)
            SternaTheme(themeMode = themeMode, dynamicColor = dynamicColor, pureBlack = pureBlack, appStyle = appStyle) {
                CompositionLocalProvider(
                    LocalListDensity provides density,
                    LocalPreviewLines provides previewLines,
                    LocalListMonogram provides listMonogram,
                ) {
                    SternaApp(
                        pendingMailto = pendingMailto.value,
                        onMailtoConsumed = {
                            pendingMailto.value = null
                            stripMailtoPayload()
                        },
                        pendingEmailOpen = pendingEmailOpen.value,
                        onEmailOpenConsumed = {
                            pendingEmailOpen.value = null
                            stripEmailOpenPayload()
                        },
                        pendingUnifiedOpen = pendingUnifiedOpen.value,
                        onUnifiedOpenConsumed = {
                            pendingUnifiedOpen.value = false
                            stripUnifiedOpenPayload()
                        },
                        pendingConfigsPage = pendingConfigsPage.value,
                        onConfigsPageConsumed = { pendingConfigsPage.value = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        (parseMailto(intent) ?: parseShare(intent))?.let { pendingMailto.value = it }
        parseEmailOpen(intent)?.let { pendingEmailOpen.value = it }
        // The widget's usual path, not an edge case: the app is normally already running when a
        // cell is tapped. Set only when the extra is there, like the two above — a bare assignment
        // would clear a request that arrived a moment ago and has not been consumed.
        if (parseUnifiedOpen(intent)) pendingUnifiedOpen.value = true
        consumeOAuthRedirect()
    }

    /**
     * Hand an OAuth authorization-code redirect (#55) to the app-scoped driver, and strip it from
     */
    private fun consumeOAuthRedirect() {
        val current = intent ?: return
        val data = current.data ?: return
        if (!application.container.oauthCodeSignIn.onRedirect(data)) return
        current.data = null
    }

    /**
     * Drop a consumed one-shot payload from the retained intent. Belt and braces next to the
     */
    private fun stripEmailOpenPayload() {
        val i = intent ?: return
        i.removeExtra(EXTRA_OPEN_EMAIL_ID)
        i.removeExtra(EXTRA_OPEN_ACCOUNT_ID)
        i.removeExtra(EXTRA_OPEN_MAILBOX_ID)
    }

    /**
     * The same one-shot strip for the home-screen widget's request (#112), and its OWN function on
     */
    private fun stripUnifiedOpenPayload() {
        val i = intent ?: return
        i.removeExtra(EXTRA_OPEN_UNIFIED)
    }

    /** Does this intent carry a home-screen widget tap? (#112) */
    private fun parseUnifiedOpen(intent: Intent?): Boolean =
        intent?.getBooleanExtra(EXTRA_OPEN_UNIFIED, false) == true

    private fun stripMailtoPayload() {
        val i = intent ?: return
        if ("mailto".equals(i.scheme, ignoreCase = true)) i.data = null
        if (i.action == Intent.ACTION_SEND || i.action == Intent.ACTION_SEND_MULTIPLE) {
            i.action = Intent.ACTION_MAIN
            i.removeExtra(Intent.EXTRA_SUBJECT)
            i.removeExtra(Intent.EXTRA_TEXT)
            i.removeExtra(Intent.EXTRA_STREAM)
        }
    }

    /** The message a tapped new-mail notification wants to open, or null. */
    private fun parseEmailOpen(intent: Intent?): EmailOpenTarget? {
        val emailId = intent?.getStringExtra(EXTRA_OPEN_EMAIL_ID) ?: return null
        return EmailOpenTarget(
            emailId = emailId,
            accountId = intent.getStringExtra(EXTRA_OPEN_ACCOUNT_ID)?.ifBlank { null },
            mailboxId = intent.getStringExtra(EXTRA_OPEN_MAILBOX_ID)?.ifBlank { null },
        )
    }

    /** RFC 6068 mailto: parsing — addresses plus the optional subject/body/cc/bcc fields. */
    private fun parseMailto(intent: Intent?): MailtoDraft? {
        val data = intent?.data ?: return null
        if (!"mailto".equals(data.scheme, ignoreCase = true)) return null
        return runCatching {
            val m = MailTo.parse(data.toString())
            MailtoDraft(
                to = m.to.orEmpty(),
                cc = m.cc.orEmpty(),
                bcc = m.headers?.get("bcc").orEmpty(),
                subject = m.subject.orEmpty(),
                body = m.body.orEmpty(),
            )
        }.getOrNull()
    }

    /**
     * A system "Share" opens the compose screen: shared text and subject prefill the fields, and any
     */
    private fun parseShare(intent: Intent?): MailtoDraft? {
        if (intent == null) return null
        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return null
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        @Suppress("DEPRECATION")
        val uris: List<Uri> = when (action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            else -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        }.filter { "content".equals(it.scheme, ignoreCase = true) }
        if (subject.isBlank() && text.isBlank() && uris.isEmpty()) return null
        application.container.pendingShareUris = uris
        return MailtoDraft(to = "", cc = "", bcc = "", subject = subject, body = text)
    }

    override fun onStop() {
        super.onStop()
        application.container.appLock.onAppBackgrounded(System.currentTimeMillis())
        // Stopped activity → no frames → the reader's GL functor cannot draw, so a process kill
        // while backgrounded must not count as a fade-window crash.
        NavFadeGuard.onActivityStop(this)
        app.sterna.push.ForegroundPush.onBackground()
    }

    override fun onStart() {
        super.onStart()
        application.container.appLock.onAppForegrounded(System.currentTimeMillis())
        NavFadeGuard.onActivityStart(this)
        // Live JMAP push while the app is on screen, whatever the delivery setting (seconds, not
        // the periodic worker's minutes).
        app.sterna.push.ForegroundPush.onForeground(this)
        // Fill the offline cache on open; WorkManager waits for a network, so offline is a no-op.
        app.sterna.mail.OfflineSyncWorker.runNow(this)
        // #1837 and a Rust mail core pass for every JMAP account.
        app.sterna.mail.MailCoreSyncWorker.runNow(this)
        applySecureFlag()
    }

    /**
     * With app lock on, mark the window FLAG_SECURE so message bodies stay out of the recents
     */
    private fun applySecureFlag() {
        if (application.container.appLock.isEnabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    companion object {
        const val EXTRA_OPEN_EMAIL_ID = "app.sterna.OPEN_EMAIL_ID"
        const val EXTRA_OPEN_ACCOUNT_ID = "app.sterna.OPEN_ACCOUNT_ID"
        const val EXTRA_OPEN_MAILBOX_ID = "app.sterna.OPEN_MAILBOX_ID"

        /**
         * Open the cross-account unified inbox — set by the home-screen widget (#112), and only
         */
        const val EXTRA_OPEN_UNIFIED = "app.sterna.OPEN_UNIFIED"
    }
}

/** Prefill fields parsed from a mailto: link, handed to the compose screen (Codeberg #15). */
data class MailtoDraft(
    val to: String,
    val cc: String,
    val bcc: String,
    val subject: String,
    val body: String,
)

/**
 * The message a tapped new-mail notification should open, and the context it lives in: its account
 */
data class EmailOpenTarget(
    val emailId: String,
    val accountId: String?,
    val mailboxId: String? = null,
)
