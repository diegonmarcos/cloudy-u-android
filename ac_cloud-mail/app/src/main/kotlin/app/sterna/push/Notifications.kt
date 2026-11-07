package app.sterna.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import app.sterna.MainActivity
import app.sterna.R
import app.sterna.core.data.settings.NotificationContent
import app.sterna.core.jmap.model.Email
import app.sterna.ui.message.verificationCodeFromMessage
import com.diegonmarcos.superapp.core.FleetNotify

enum class MailNotificationAction { REPLY, COPY_CODE, REMIND }

/** Notification channels + helpers. No telemetry, no third-party push. */
object Notifications {
    const val CHANNEL_MAIL = "new_mail"
    const val CHANNEL_SERVICE = "push_service"
    const val SERVICE_ID = 1

    private const val GROUP_PREFIX = "mail:"

    /** Below this many live per-message notifications an account gets no group summary. */
    private const val SUMMARY_MIN_CHILDREN = 2

    /** Whether the account's group summary will be on screen beside a per-message notification
     *  posted now (#56). Only one of the two may alert, but a single arrival gets no summary, so
     *  the child speaks exactly when it stands alone. */
    fun summaryShownFor(liveChildren: Int): Boolean = liveChildren >= SUMMARY_MIN_CHILDREN

    private val ACTIONS = listOf(MailNotificationAction.REPLY, MailNotificationAction.REMIND)
    private val ACTIONS_WITH_CODE = listOf(
        MailNotificationAction.REPLY,
        MailNotificationAction.COPY_CODE,
        MailNotificationAction.REMIND,
    )

    /**
     * The buttons a new-mail notification carries (#57). The question is not which setting is on
     */
    fun actionsFor(
        content: NotificationContent,
        hasSender: Boolean,
        hasSubject: Boolean,
        hasCode: Boolean = false,
    ): List<MailNotificationAction> {
        val all = if (hasCode) ACTIONS_WITH_CODE else ACTIONS
        return when (content) {
            NotificationContent.BODY_PREVIEW,
            NotificationContent.SENDER_AND_SUBJECT,
            -> if (hasSender || hasSubject) all else emptyList()
            NotificationContent.SENDER_ONLY -> if (hasSender) all else emptyList()
            NotificationContent.NONE -> emptyList()
        }
    }

    /** The per-message ids the account is left with once a pass is done. Counted rather than
     *  re-read afterwards: the decision has to be made BEFORE the first child is posted. */
    fun liveChildIdsAfter(
        active: Set<Int>,
        accountId: String,
        cleared: Collection<String>,
        added: Collection<String>,
    ): Set<Int> {
        val live = active.toMutableSet()
        // Both id shapes, through [childIdsOf], so this subtraction cannot drift from the filter.
        live -= childIdsOf(accountId, cleared)
        added.forEach { live += childId(accountId, it) }
        return live
    }

    /**
     * Which of [departedIds] a pass may unpost: messages the server said left the folder that still
     */
    fun departuresToCancel(
        active: Set<Int>,
        accountId: String,
        departedIds: Collection<String>,
        stillHeld: Collection<String>,
    ): List<String> {
        if (departedIds.isEmpty()) return emptyList()
        val held = stillHeld.toSet()
        return departedIds.distinct().filter { it !in held && isChildActive(active, accountId, it) }
    }

    /** The notification id of one message. JMAP ids are per account, so keying on the id alone made
     *  account B's notification replace A's and rewrite A's buttons to carry B's message (#92). */
    fun childId(accountId: String, emailId: String): Int = "$accountId $emailId".hashCode()

    /** The id the same message got before [childId]: banners from an older build are still up. */
    private fun legacyChildId(emailId: String): Int = emailId.hashCode()

    /** Every notification id [emailIds] can be wearing: the current shape and the pre-#92 one.
     *  Both, always — an unmatched banner is immortal, and every summary teardown puts it back. */
    fun childIdsOf(accountId: String, emailIds: Collection<String>): Set<Int> =
        emailIds.flatMapTo(mutableSetOf()) { listOf(childId(accountId, it), legacyChildId(it)) }

    fun isChildActive(active: Set<Int>, accountId: String, emailId: String): Boolean =
        childId(accountId, emailId) in active || legacyChildId(emailId) in active

    /** Request code for an action button's broadcast, unique per account + message + action. */
    private fun actionRequestCode(accountId: String, emailId: String, action: String): Int =
        "$accountId $emailId $action".hashCode()

    /**
     * The new-mail channel, and only it — every function that posts a banner calls this first: on an
     */
    fun ensureMailChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_MAIL,
                context.getString(R.string.notif_channel_mail),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }

    /** Both channels. Called by [PushService] and nothing else: the service channel belongs to a
     *  service that is actually running. */
    fun ensureChannels(context: Context) {
        ensureMailChannel(context)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE,
                context.getString(R.string.notif_channel_sync),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.notif_channel_sync_desc) },
        )
    }

    /**
     * A one-off notification that a queued message could not be sent. [body] is text the app is
     */
    fun notifySendFailed(context: Context, subject: String, content: NotificationContent, body: String = "") {
        ensureMailChannel(context)
        // Keyed on the body too, or a second salvaged text replaces the first.
        val key = if (body.isBlank()) "sendfail:$subject" else "sendfail:$subject:$body"
        val target = if (body.isBlank()) {
            Intent(context, MainActivity::class.java)
        } else {
            Intent(Intent.ACTION_VIEW, draftUri(subject, body), context, MainActivity::class.java)
        }
        val open = PendingIntent.getActivity(
            context,
            if (body.isBlank()) "sendfail".hashCode() else key.hashCode(),
            target.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val noSubject = context.getString(R.string.message_no_subject)
        val line = MailNotificationText.sendFailureLine(content, subject, noSubject)
        val notification = NotificationCompat.Builder(context, CHANNEL_MAIL)
            .setSmallIcon(R.drawable.ic_stat_mail)
            .setContentTitle(context.getString(R.string.notif_send_failed_title))
            // Nothing at all when the setting hides subjects, rather than an empty line.
            .apply {
                if (line != null) {
                    setContentText(line)
                    setStyle(NotificationCompat.BigTextStyle().bigText(line))
                }
            }
            // Auto-cancel only when there is nothing to lose: a hand-back raised for a signed-out
            // account opens nothing on tap, and cancelling it would be the final loss of the text.
            .setAutoCancel(body.isBlank())
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(key.hashCode(), notification)
    }

    private fun draftUri(subject: String, body: String): Uri =
        Uri.parse("mailto:?subject=${Uri.encode(subject)}&body=${Uri.encode(body)}")

    /**
     * The push service's keep-alive badge (FleetNotify.keepAliveNotification): ongoing, a Stop action, and mail data. [badge] is
     * the unread count and the latest message ([KeepAliveBadge.read]); null is the first frame, before that read lands.
     */
    fun serviceNotification(context: Context, badge: KeepAliveBadge.Content? = null): Notification {
        val shown = badge ?: KeepAliveBadge.Content(context.getString(R.string.notif_watching), emptyList())
        val stop = PendingIntent.getService(
            context, 0, Intent(context, PushService::class.java).setAction(PushService.ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return FleetNotify.keepAliveNotification(
            context, R.drawable.ic_stat_mail, shown.title, shown.lines, stop, open,
            stopLabel = context.getString(R.string.notif_keepalive_stop),
        )
    }

    /**
     * [folderName] marks non-inbox mail (#16), null for the inbox; [mailboxId] is that folder's id,
     */
    fun notifyNewMail(
        context: Context,
        email: Email,
        accountId: String,
        silent: Boolean,
        folderName: String?,
        mailboxId: String?,
        content: NotificationContent,
        summarised: Boolean,
    ) {
        ensureMailChannel(context)
        val generic = context.getString(R.string.notif_new_message)
        // Kept apart from the stand-ins below: whether the mail names ITSELF decides the action
        // buttons, and "New message" / "(no subject)" name nothing (#57).
        val fromName = email.from.firstOrNull()?.display()
        val realSubject = email.subject?.takeIf { it.isNotBlank() }
        val sender = fromName ?: generic
        val subject = realSubject ?: context.getString(R.string.message_no_subject)
        // What the notification reveals, per the privacy setting — the rule lives in
        // [MailNotificationText] so no caller can post with the defaults and leak what the user hid
        // (#84). The body opening comes from [Email.preview]; nothing is fetched from here.
        val (title, text, bigText) = MailNotificationText.resolve(content, sender, subject, generic, email.preview)
        // THE reading pane's extractor, on what this notification has of the message: Copy Code is
        // offered only when it finds a code, and the code travels on the button.
        val code = verificationCodeFromMessage(email)
        // Which buttons it may carry: a notification that names nothing offers nothing (#57).
        val actions = actionsFor(
            content,
            hasSender = !fromName.isNullOrBlank(),
            hasSubject = realSubject != null,
            hasCode = code != null,
        )
        val notifId = childId(accountId, email.id)
        // Carry the message identity so a tap opens THAT email even when the app is running. Its
        // account (#31) and folder (#91) travel along so the list ends up where the message is.
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_OPEN_EMAIL_ID, email.id)
            .putExtra(MainActivity.EXTRA_OPEN_ACCOUNT_ID, accountId)
            .apply { if (mailboxId != null) putExtra(MainActivity.EXTRA_OPEN_MAILBOX_ID, mailboxId) }
        val pending = PendingIntent.getActivity(
            context,
            notifId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_MAIL)
            .setSmallIcon(R.drawable.ic_stat_mail)
            .setContentTitle(title)
            .apply {
                if (text != null) setContentText(text)
                if (bigText != null) setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            }
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setCategory(NotificationCompat.CATEGORY_EMAIL)
            .setGroup(GROUP_PREFIX + accountId)
            .setGroupAlertBehavior(
                if (summarised) {
                    NotificationCompat.GROUP_ALERT_SUMMARY
                } else {
                    NotificationCompat.GROUP_ALERT_ALL
                },
            )
            .setSilent(silent)
            .apply { if (folderName != null) setSubText(folderName) }
            .apply {
                actions.forEach { action ->
                    addAction(
                        when (action) {
                            MailNotificationAction.REPLY ->
                                replyAction(context, email.id, accountId, notifId)
                            MailNotificationAction.COPY_CODE -> simpleAction(
                                context,
                                context.getString(R.string.notif_copy_code),
                                NotificationActionReceiver.ACTION_COPY_CODE,
                                email.id,
                                accountId,
                                notifId,
                                code,
                            )
                            MailNotificationAction.REMIND -> simpleAction(
                                context,
                                context.getString(R.string.notif_remind),
                                NotificationActionReceiver.ACTION_REMIND,
                                email.id,
                                accountId,
                                notifId,
                            )
                        },
                    )
                }
            }
            .build()
        context.getSystemService(NotificationManager::class.java).notify(notifId, notification)
    }

    /**
     * Whether tearing the group summary down can be UNDONE, given what the caller expects to keep
     */
    fun teardownRepairable(expectedChildIds: Set<Int>, shownChildIds: Set<Int>): Boolean =
        shownChildIds.containsAll(expectedChildIds)

    /**
     * Rebuild the account's group summary from the currently active children, so successive
     */
    fun updateGroupSummary(
        context: Context,
        accountId: String,
        accountLabel: String,
        silent: Boolean,
        expectedChildIds: Set<Int>,
        cancelledChildIds: Set<Int>,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val group = GROUP_PREFIX + accountId
        val summaryId = ("summary:" + accountId).hashCode()
        val active = manager.activeNotifications
        val children = active.filter { sbn ->
            sbn.id != summaryId && sbn.notification.group == group &&
                (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0 &&
                // …and the caller has NOT just cancelled it. Everything else alive stays, or the
                // cascade takes it and nobody puts it back. Both id shapes.
                sbn.id !in cancelledChildIds
        }.sortedByDescending { it.postTime }
        // The union, and the only number this function publishes: one half is what this pass just
        // posted and the shade does not show yet, the other what is on screen and the caller knows
        // nothing about. Deciding on the shade alone would let the teardown swallow a fresh banner.
        val shownChildIds = children.mapTo(mutableSetOf()) { it.id }
        val live = expectedChildIds + shownChildIds
        if (!summaryShownFor(live.size)) {
            // Cancelling a summary cascades every live child away, so each one captured above is
            // put straight back. ONLY when a summary is really on screen — otherwise the re-post
            // would overwrite the lone child's GROUP_ALERT_ALL, the flag a single arrival needs to
            // announce itself (#56) — and only when every counted id can be put back.
            if (teardownRepairable(expectedChildIds, shownChildIds) &&
                active.any { it.id == summaryId && it.notification.group == group }) {
                manager.cancel(summaryId)
                children.forEach { child -> repostSilently(context, manager, child) }
            }
            return
        }
        val lines = children.map { sbn ->
            val sender = sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                ?: context.getString(R.string.notif_new_message)
            val subject = sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                ?: context.getString(R.string.message_no_subject)
            context.getString(R.string.notif_group_line, sender, subject)
        }
        notifyGroupSummary(context, accountId, accountLabel, live.size, lines, silent)
    }

    /**
     * Put a per-message banner back under its own id after the summary's cascade took it.
     */
    private fun repostSilently(context: Context, manager: NotificationManager, child: StatusBarNotification) {
        ensureMailChannel(context)
        manager.notify(
            child.id,
            Notification.Builder.recoverBuilder(context, child.notification)
                .setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
                .build(),
        )
    }

    /** The active child notification ids for [accountId]'s group, without the summary. Lets a read
     *  message be matched to a live notification to dismiss (#19). */
    fun activeChildIds(context: Context, accountId: String): Set<Int> {
        val manager = context.getSystemService(NotificationManager::class.java)
        val group = GROUP_PREFIX + accountId
        val summaryId = ("summary:" + accountId).hashCode()
        return manager.activeNotifications
            .filter { sbn ->
                sbn.id != summaryId && sbn.notification.group == group &&
                    (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0
            }
            .map { it.id }
            .toSet()
    }

    /**
     * Among [active] — one `(id, group key)` pair per live notification — the ids belonging to
     */
    fun accountGroupIds(active: List<Pair<Int, String?>>, accountId: String): List<Int> {
        val group = GROUP_PREFIX + accountId
        return active.filter { (_, notificationGroup) -> notificationGroup == group }.map { it.first }
    }

    /** Take down every banner of [accountId]. For a sign-out and nothing else: the account is about
     *  to have no credentials, so whatever is left on the shade would stay for ever. */
    fun cancelAccount(context: Context, accountId: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val active = manager.activeNotifications.map { it.id to it.notification.group }
        accountGroupIds(active, accountId).forEach { manager.cancel(it) }
    }

    fun cancelChild(context: Context, accountId: String, emailId: String) {
        // Read or gone: a pending Remind for it has nothing left to remind of.
        NotificationReminders.cancel(context, accountId, emailId)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(childId(accountId, emailId))
        // Also the pre-update form, or a banner from the previous build survives every dismissal.
        manager.cancel(legacyChildId(emailId))
    }

    /** Dismiss the notifications for [emailIds] that just became read, here or on another device,
     *  and refresh the group summary. A no-op for ids with no live notification (#19). */
    fun dismiss(context: Context, accountId: String, accountLabel: String, emailIds: Collection<String>) {
        val active = activeChildIds(context, accountId)
        val hit = emailIds.filter { isChildActive(active, accountId, it) }
        if (hit.isEmpty()) return
        hit.forEach { cancelChild(context, accountId, it) }
        // The cancels just asked for are still in flight, so what the account is left with is the
        // read from the top of this function minus the hits, not the shade's answer (#134).
        updateGroupSummary(
            context,
            accountId,
            accountLabel,
            silent = true,
            expectedChildIds = liveChildIdsAfter(active, accountId, cleared = hit, added = emptyList()),
            // …and exactly what the loop above cancelled, both id shapes — no more.
            cancelledChildIds = childIdsOf(accountId, hit),
        )
    }

    /** Posts the per-account group summary. [lines] are "sender — subject" for the latest batch. */
    fun notifyGroupSummary(
        context: Context,
        accountId: String,
        accountLabel: String,
        count: Int,
        lines: List<String>,
        silent: Boolean = false,
    ) {
        ensureMailChannel(context)
        val title = context.getString(R.string.notif_group_count, count, accountLabel)
        val style = NotificationCompat.InboxStyle().setBigContentTitle(title)
        lines.take(6).forEach { style.addLine(it) }
        if (lines.size > 6) style.setSummaryText("+${lines.size - 6}")
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            context,
            ("summary:" + accountId).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_MAIL)
            .setSmallIcon(R.drawable.ic_stat_mail)
            .setContentTitle(accountLabel)
            .setContentText(title)
            .setStyle(style)
            .setGroup(GROUP_PREFIX + accountId)
            .setGroupSummary(true)
            // The summary speaks for the group (#56); its children are posted quiet under it.
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setSilent(silent)
            .setCategory(NotificationCompat.CATEGORY_EMAIL)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(("summary:" + accountId).hashCode(), notification)
    }

    private fun actionIntent(context: Context, action: String, emailId: String, accountId: String, notifId: Int) =
        Intent(context, NotificationActionReceiver::class.java).apply {
            this.action = action
            putExtra(NotificationActionReceiver.EXTRA_EMAIL_ID, emailId)
            putExtra(NotificationActionReceiver.EXTRA_ACCOUNT_ID, accountId)
            putExtra(NotificationActionReceiver.EXTRA_NOTIF_ID, notifId)
        }

    private fun simpleAction(
        context: Context,
        label: String,
        action: String,
        emailId: String,
        accountId: String,
        notifId: Int,
        code: String? = null,
    ): NotificationCompat.Action {
        val pending = PendingIntent.getBroadcast(
            context,
            actionRequestCode(accountId, emailId, action),
            actionIntent(context, action, emailId, accountId, notifId)
                .apply { if (code != null) putExtra(NotificationActionReceiver.EXTRA_CODE, code) },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(0, label, pending).build()
    }

    private fun replyAction(context: Context, emailId: String, accountId: String, notifId: Int): NotificationCompat.Action {
        val pending = PendingIntent.getBroadcast(
            context,
            actionRequestCode(accountId, emailId, NotificationActionReceiver.ACTION_REPLY),
            actionIntent(context, NotificationActionReceiver.ACTION_REPLY, emailId, accountId, notifId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val remoteInput = RemoteInput.Builder(NotificationActionReceiver.KEY_REPLY)
            .setLabel(context.getString(R.string.notif_reply_hint))
            .build()
        return NotificationCompat.Action.Builder(0, context.getString(R.string.notif_reply), pending)
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .build()
    }
}
