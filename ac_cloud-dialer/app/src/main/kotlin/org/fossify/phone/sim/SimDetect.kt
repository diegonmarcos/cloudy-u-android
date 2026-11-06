package org.fossify.phone.sim

/** One subscription as SubscriptionManager reports it (the platform class mapped to plain data so a test can fake it). */
data class SubRecord(
    val subscriptionId: Int,
    val slot: Int,
    val carrier: String?,
    val displayName: String?,
    val mcc: String?,
    val mnc: String?,
    val country: String?,
    val number: String?,
    val iccid: String?,
    val embedded: Boolean,
)

/** Live, per-subscription state; null = not exposed. */
data class SubLive(val roaming: Boolean?, val networkType: String?, val signalLevel: Int?)

/**
 * The platform behind [SimDetect]: SubscriptionManager.getActiveSubscriptionInfoList, getAccessibleSubscriptionInfoList (eSIM
 * profiles, including inactive ones, when the app holds carrier privileges), the defaults, the EID and the slot count.
 */
interface SubSource {
    fun active(): List<SubRecord>
    fun accessible(): List<SubRecord>
    fun live(subscriptionId: Int): SubLive
    val defaultData: Int
    val defaultVoice: Int
    val defaultSms: Int
    val eid: String?
    val slotCount: Int
}

/** #1528 one card per physical slot (a SIM, or "empty") and one per eSIM profile (active or not). */
object SimDetect {
    fun cards(src: SubSource): List<SimCard> {
        val active = src.active()
        val activeIds = active.map { it.subscriptionId }.toSet()
        val activeCards = active.map { r -> card(src, r, src.live(r.subscriptionId), active = true) }
        val inactive = src.accessible().filter { it.subscriptionId !in activeIds }.distinctBy { it.subscriptionId }
            .map { card(src, it.copy(slot = -1, embedded = true), SubLive(null, null, null), active = false) }
        val taken = active.map { it.slot }.toSet()
        val empty = (0 until src.slotCount).filter { it !in taken }.map { slot ->
            SimCard(slot, null, null, null, null, null, null, null, null, embedded = false, roaming = null, defaultData = false,
                defaultVoice = false, defaultSms = false, networkType = null, signalLevel = null, active = false)
        }
        return (activeCards + empty).sortedBy { it.slot } + inactive.sortedBy { it.displayName ?: it.carrier ?: "" }
    }

    private fun card(src: SubSource, r: SubRecord, l: SubLive, active: Boolean) = SimCard(
        slot = r.slot,
        subscriptionId = r.subscriptionId,
        carrier = r.carrier,
        displayName = r.displayName,
        mcc = r.mcc,
        mnc = r.mnc,
        country = r.country,
        number = r.number?.takeIf { it.isNotBlank() },
        iccid = r.iccid?.takeIf { it.isNotBlank() },
        embedded = r.embedded,
        roaming = l.roaming,
        defaultData = r.subscriptionId == src.defaultData,
        defaultVoice = r.subscriptionId == src.defaultVoice,
        defaultSms = r.subscriptionId == src.defaultSms,
        networkType = l.networkType,
        signalLevel = l.signalLevel,
        eid = if (r.embedded) src.eid else null,
        active = active,
    )
}
