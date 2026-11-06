package org.fossify.phone.sim

import android.Manifest
import android.annotation.SuppressLint
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TelephonyNetworkSpecifier
import android.os.Build
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.telephony.euicc.EuiccManager
import java.net.Inet4Address
import java.net.Inet6Address
import java.util.Calendar

/**
 * Reads SIM cards and their live status through SubscriptionManager, TelephonyManager, EuiccManager, ConnectivityManager
 * and NetworkStatsManager. Grants (phone_state, phone_numbers, usage_access) are declared in build.json::perms and asked
 * through libs:perms; whatever is not granted or not exposed reads as null. Nothing here is logged.
 */
@SuppressLint("MissingPermission")
class SimReader(private val ctx: Context) {
    private val sm: SubscriptionManager? = ctx.getSystemService(SubscriptionManager::class.java)
    private val tm: TelephonyManager? = ctx.getSystemService(TelephonyManager::class.java)

    private fun has(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun hasPhoneState(): Boolean = has(Manifest.permission.READ_PHONE_STATE)

    private fun subs(): List<SubscriptionInfo> =
        if (!hasPhoneState()) emptyList() else runCatching { sm?.activeSubscriptionInfoList.orEmpty() }.getOrDefault(emptyList())

    private fun tmFor(subId: Int): TelephonyManager? = runCatching { tm?.createForSubscriptionId(subId) }.getOrNull()

    /** #1528 one card per physical slot and per eSIM profile (inactive ones too when carrier privileges allow). */
    fun cards(): List<SimCard> = if (!hasPhoneState()) emptyList() else SimDetect.cards(AndroidSubSource())

    /** SubscriptionManager / TelephonyManager / EuiccManager behind [SubSource]; whatever is not granted reads as empty or null. */
    private inner class AndroidSubSource : SubSource {
        override fun active(): List<SubRecord> = subs().map { record(it) }

        override fun accessible(): List<SubRecord> =
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) emptyList()
            else runCatching { sm?.accessibleSubscriptionInfoList.orEmpty() }.getOrDefault(emptyList()).map { record(it) }

        override fun live(subscriptionId: Int): SubLive {
            val t = tmFor(subscriptionId)
            return SubLive(
                roaming = runCatching { t?.isNetworkRoaming }.getOrNull(),
                networkType = runCatching { t?.dataNetworkType }.getOrNull()?.let { StatusFormat.rat(it) },
                signalLevel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) runCatching { t?.signalStrength?.level }.getOrNull() else null,
            )
        }

        override val defaultData: Int get() = SubscriptionManager.getDefaultDataSubscriptionId()
        override val defaultVoice: Int get() = SubscriptionManager.getDefaultVoiceSubscriptionId()
        override val defaultSms: Int get() = SubscriptionManager.getDefaultSmsSubscriptionId()
        override val eid: String? get() = runCatching { ctx.getSystemService(EuiccManager::class.java)?.takeIf { it.isEnabled }?.eid }.getOrNull()
        @Suppress("DEPRECATION")
        override val slotCount: Int get() = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) tm?.supportedModemCount ?: 0 else tm?.phoneCount ?: 0
        }.getOrDefault(0)

        private fun record(s: SubscriptionInfo) = SubRecord(
            subscriptionId = s.subscriptionId,
            slot = s.simSlotIndex,
            carrier = s.carrierName?.toString(),
            displayName = s.displayName?.toString(),
            mcc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) s.mccString else s.mcc.toString(),
            mnc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) s.mncString else s.mnc.toString(),
            country = s.countryIso,
            number = number(s),
            iccid = runCatching { s.iccId }.getOrNull()?.takeIf { it.isNotBlank() },
            embedded = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && s.isEmbedded,
        )
    }

    @Suppress("DEPRECATION")
    private fun number(s: SubscriptionInfo): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && has(Manifest.permission.READ_PHONE_NUMBERS)) {
            sm?.getPhoneNumber(s.subscriptionId)
        } else {
            s.number
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    fun status(): List<SimStatus> {
        val month = monthUsage()
        val dData = SubscriptionManager.getDefaultDataSubscriptionId()
        return subs().map { s ->
            val t = tmFor(s.subscriptionId)
            val net = cellular(s.subscriptionId)
            var dbm: Int? = null; var rsrp: Int? = null; var rsrq: Int? = null; var sinr: Int? = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching { t?.signalStrength?.cellSignalStrengths.orEmpty() }.getOrDefault(emptyList()).forEach { c ->
                    when (c) {
                        is CellSignalStrengthNr -> { dbm = c.dbm; rsrp = c.ssRsrp; rsrq = c.ssRsrq; sinr = c.ssSinr }
                        is CellSignalStrengthLte -> if (rsrp == null) { dbm = c.dbm; rsrp = c.rsrp; rsrq = c.rsrq; sinr = c.rssnr }
                        else -> if (dbm == null) dbm = c.dbm
                    }
                }
            }
            SimStatus(
                slot = s.simSlotIndex,
                label = s.displayName?.toString() ?: "SIM ${s.simSlotIndex + 1}",
                serviceState = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) runCatching { t?.serviceState?.state }.getOrNull() else null,
                operator = runCatching { t?.networkOperatorName }.getOrNull(),
                rat = runCatching { t?.dataNetworkType }.getOrNull()?.let { StatusFormat.rat(it) },
                dbm = dbm, rsrp = rsrp, rsrq = rsrq, sinr = sinr,
                cellId = cellId(t),
                dataState = runCatching { t?.dataState }.getOrNull(),
                ipv4 = net.first, ipv6 = net.second, dns = net.third,
                apn = null,
                roaming = runCatching { t?.isNetworkRoaming }.getOrNull(),
                smsCapable = runCatching { t?.isSmsCapable }.getOrNull(),
                callState = callState(t),
                volte = null,
                vowifi = null,
                monthBytes = if (s.subscriptionId == dData) month else null,
            )
        }.sortedBy { it.slot }
    }

    @Suppress("DEPRECATION")
    private fun callState(t: TelephonyManager?): Int? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) t?.callStateForSubscription else t?.callState
    }.getOrNull()

    private fun cellId(t: TelephonyManager?): String? = runCatching {
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) return null
        t?.allCellInfo?.firstOrNull { it.isRegistered }?.let { c ->
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && c is CellInfoNr -> c.cellIdentity.toString().substringAfter("mNci=", "").substringBefore(' ').ifBlank { null }
                c is CellInfoLte -> c.cellIdentity.ci.takeIf { it != Int.MAX_VALUE }?.toString()
                else -> null
            }
        }
    }.getOrNull()

    /** IPv4, IPv6 and DNS of the cellular network carrying [subId] (any cellular network before API 30). */
    private fun cellular(subId: Int): Triple<List<String>, List<String>, List<String>> {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return Triple(emptyList(), emptyList(), emptyList())
        val net = runCatching {
            cm.allNetworks.firstOrNull { n ->
                val caps = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return@firstOrNull false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    (caps.networkSpecifier as? TelephonyNetworkSpecifier)?.subscriptionId?.let { it == subId } ?: true
                } else true
            }
        }.getOrNull() ?: return Triple(emptyList(), emptyList(), emptyList())
        val lp = cm.getLinkProperties(net) ?: return Triple(emptyList(), emptyList(), emptyList())
        val addrs = lp.linkAddresses.map { it.address }
        return Triple(
            addrs.filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress },
            addrs.filterIsInstance<Inet6Address>().mapNotNull { it.hostAddress?.substringBefore('%') },
            lp.dnsServers.mapNotNull { it.hostAddress?.substringBefore('%') },
        )
    }

    /** Mobile bytes (rx+tx) since the 1st of this month; null without usage access. */
    @Suppress("DEPRECATION")
    private fun monthUsage(): Long? = runCatching {
        val nsm = ctx.getSystemService(NetworkStatsManager::class.java) ?: return null
        val start = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val b = nsm.querySummaryForDevice(ConnectivityManager.TYPE_MOBILE, null, start, System.currentTimeMillis())
        b.rxBytes + b.txBytes
    }.getOrNull()
}
