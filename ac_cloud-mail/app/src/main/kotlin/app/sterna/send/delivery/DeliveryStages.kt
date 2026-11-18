package app.sterna.send.delivery

/**
 * #1802: the four delivery stages of one SENT message, as the Sent row's ticker and the message
 * view's timeline show them. Stage 1 is the phone's own answer (the outbox); stages 2-4 are ONLY
 * ever what the fleet mail server's own records say (submission log, DKIM signer, outbound queue /
 * delivery results / DSNs), delivered as a [ServerDeliveryReport]. Nothing here infers a stage the
 * records do not state: a missing record is [StageMark.Pending], never a guess.
 *
 * The order is the declaration order, and it is binding: a stage can only light once every stage
 * before it is confirmed (see [DeliveryStateMachine]).
 */
enum class DeliveryStage { LEFT_PHONE, SERVER_RECEIVED, VALID_SENDER, SENT }

/** What one mark of the ticker shows. */
sealed interface StageMark {
    /** No record yet: the stage has not happened, or the server has not said so. */
    data object Pending : StageMark

    /** An earlier stage failed or is still open, so this one cannot be judged yet. */
    data object NotReached : StageMark

    /** Confirmed by its record, at [atMillis] when the record carries a time. */
    data class Confirmed(val atMillis: Long?) : StageMark

    /** Only for [DeliveryStage.SENT]: the remote server deferred; still trying. */
    data class Deferred(val atMillis: Long?, val reason: DeliveryReason) : StageMark

    /** The first stage that failed: the ticker draws an X here, [reason] on tap. */
    data class Failed(val atMillis: Long?, val reason: DeliveryReason) : StageMark
}

/** Why a stage failed or is deferred. Typed, so the UI words it; server text passes through. */
sealed interface DeliveryReason {
    /** The outbox gave up on the message (its own failure text, or none). */
    data class PhoneFailed(val detail: String?) : DeliveryReason

    /** The submission server refused: its SMTP code and reply text. */
    data class SubmissionRefused(val smtpCode: Int?, val detail: String?) : DeliveryReason

    /** The sender checks that failed, in [SenderCheck] order; [detail] is the server's text. */
    data class SenderRejected(val failed: List<SenderCheck>, val detail: String?) : DeliveryReason

    /** At least one recipient bounced (the bounced ones, from the DSN / delivery result). */
    data class Bounced(val recipients: List<RecipientDelivery>) : DeliveryReason

    /** At least one recipient was deferred, none bounced (the deferred ones). */
    data class DeferredBy(val recipients: List<RecipientDelivery>) : DeliveryReason
}

/** The checks behind stage 3, each answered by the server's own record. */
enum class SenderCheck { ACCOUNT_MAY_USE_FROM, DKIM_SIGNED, SPF_ALIGNED, DMARC_ALIGNED }

enum class RecipientState { PENDING, DELIVERED, DEFERRED, BOUNCED }

/** One recipient's outcome at the remote MX, as the delivery result or DSN records it. */
data class RecipientDelivery(
    val address: String,
    val state: RecipientState,
    val atMillis: Long? = null,
    val smtpCode: Int? = null,
    val remoteMx: String? = null,
    val detail: String? = null,
    /** Which hop recorded this outcome: relay, mx, local, dsn or queue. A relay 250 is NOT delivery. */
    val hop: String? = null,
) {
    /** The OCI relay accepted it and no DSN or remote answer has come back yet. */
    val acceptedByRelay: Boolean get() = state == RecipientState.PENDING && hop == "relay"
}

/** What the phone itself knows: is the message still in the outbox, and did the outbox fail it. */
data class PhoneDelivery(
    val queuedInOutbox: Boolean,
    val handedOffAtMillis: Long? = null,
    val failure: String? = null,
    val failed: Boolean = failure != null,
)

/**
 * The fleet mail server's records for one message. Every field is nullable: null means the record
 * does not exist (yet), and the stage stays pending. The wire client fills this; nothing else may.
 */
data class ServerDeliveryReport(
    val submission: Submission? = null,
    val sender: SenderAuth? = null,
    val recipients: List<RecipientDelivery> = emptyList(),
) {
    /** Submission log: SMTP 250 with the server's message id, or a refusal. */
    data class Submission(
        val accepted: Boolean,
        val atMillis: Long? = null,
        val smtpCode: Int? = null,
        val messageId: String? = null,
        val detail: String? = null,
    )

    /** Each check is null while the record is missing, true/false once the server recorded it. */
    data class SenderAuth(
        val accountMayUseFrom: Boolean? = null,
        val dkimSigned: Boolean? = null,
        val spfAligned: Boolean? = null,
        val dmarcAligned: Boolean? = null,
        val atMillis: Long? = null,
        val dkimSelector: String? = null,
        val detail: String? = null,
        /** Whether SPF gates the stage. The fleet server reports SPF but does not gate on it. */
        val spfGating: Boolean = true,
        /** The server itself recorded the stage as failed (its verdict wins over the checks). */
        val serverFailed: Boolean = false,
    ) {
        /** The checks the stage is judged on. */
        val gatingChecks: List<SenderCheck>
            get() = SenderCheck.entries.filter { spfGating || it != SenderCheck.SPF_ALIGNED }

        fun answer(check: SenderCheck): Boolean? = when (check) {
            SenderCheck.ACCOUNT_MAY_USE_FROM -> accountMayUseFrom
            SenderCheck.DKIM_SIGNED -> dkimSigned
            SenderCheck.SPF_ALIGNED -> spfAligned
            SenderCheck.DMARC_ALIGNED -> dmarcAligned
        }
    }
}

/** One stage as drawn: its mark plus everything the tap / the timeline shows. */
data class StageView(
    val stage: DeliveryStage,
    val mark: StageMark,
    /** Per-recipient detail, filled for [DeliveryStage.SENT] once the server reports any. */
    val recipients: List<RecipientDelivery> = emptyList(),
    /** The server's message id, filled for [DeliveryStage.SERVER_RECEIVED] once recorded. */
    val messageId: String? = null,
)

/** The four stages, always all four, always in [DeliveryStage] order. */
data class DeliveryTimeline(val stages: List<StageView>) {
    init {
        require(stages.map { it.stage } == DeliveryStage.entries) { "a timeline holds the four stages in order" }
    }

    operator fun get(stage: DeliveryStage): StageView = stages[stage.ordinal]

    /** The first failed stage, if any: the one the X marks. */
    val failedStage: StageView? get() = stages.firstOrNull { it.mark is StageMark.Failed }

    /** Nothing more will change: every stage confirmed, or one failed. Polling stops here. */
    val isTerminal: Boolean
        get() = failedStage != null || stages.all { it.mark is StageMark.Confirmed }
}
