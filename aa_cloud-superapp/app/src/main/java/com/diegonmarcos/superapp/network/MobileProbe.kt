package com.diegonmarcos.superapp.network

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.CellSignalStrengthCdma
import android.telephony.CellSignalStrengthGsm
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.CellSignalStrengthTdscdma
import android.telephony.CellSignalStrengthWcdma
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.SubscriptionManager
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import java.util.concurrent.ConcurrentHashMap

/**
 * The radio facts behind [MobileLink], read from TelephonyManager / SubscriptionManager under the
 * permissions the app already holds, never throwing: what a permission gates reads null ("—").
 *
 *   no permission        signal (getSignalStrength, the signal callback), roaming, operator name and
 *                        MCC/MNC, the service-state / display-info / data-connection callbacks, the
 *                        default data / voice / SMS SIM ids, airplane mode
 *   ACCESS_NETWORK_STATE isDataEnabled / isDataEnabledForReason / isDataRoamingEnabled (held)
 *   READ_PHONE_STATE     every active SIM (dual SIM), getServiceState, the data / voice network type,
 *                        carrier config (VoLTE / Wi-Fi calling / 5G offered)
 *   privileged           VoLTE / VoNR / Wi-Fi calling switches, preferred network mode: through the
 *                        fleet's shell channel when one is up (NetworkPopupSections reads them)
 *
 * [Watch] is the strip's event feed: TelephonyCallbacks on the default data SIM (signal, service
 * state, display info, data connection) plus observers on the mobile-data / data-roaming /
 * airplane switches and the default-data-SIM change. No polling.
 */
object MobileProbe {

    /** What the callbacks last pushed per subscription; the popup reads it too (getServiceState needs a permission). */
    data class Live(
        val serviceState: Int? = null, val emergencyOnly: Boolean = false, val roaming: Boolean? = null,
        val networkType: Int? = null, val overrideType: Int? = null, val level: Int? = null, val dbm: Int? = null,
    )

    private val live = ConcurrentHashMap<Int, Live>()

    internal fun push(subId: Int, f: (Live) -> Live) { live[subId] = f(live[subId] ?: Live()) }

    /** What the callbacks last pushed for [subId]. */
    fun live(subId: Int): Live? = live[subId]

    /** [i] with the signal the callbacks last pushed: a signal-only event re-derives without a binder read. */
    fun withLiveSignal(i: MobileLink.Inputs): MobileLink.Inputs = i.copy(sims = i.sims.map { s ->
        val l = live[s.subId] ?: return@map s
        s.copy(level = l.level ?: s.level, dbm = l.dbm ?: s.dbm)
    })

    fun phoneGranted(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    fun airplane(ctx: Context): Boolean = runCatching {
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
    }.getOrDefault(false)

    fun defaultDataSubId(): Int = runCatching { SubscriptionManager.getDefaultDataSubscriptionId() }.getOrDefault(MobileLink.NO_SUB)

    private fun base(ctx: Context): TelephonyManager? =
        ctx.applicationContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    fun forSub(ctx: Context, subId: Int): TelephonyManager? {
        val tm = base(ctx) ?: return null
        return if (subId >= 0) runCatching { tm.createForSubscriptionId(subId) }.getOrDefault(tm) else tm
    }

    /** (subId, slot, carrier from the SIM's own record) of every SIM we can learn of. */
    private data class SubRef(val subId: Int, val slot: Int, val carrier: String?)

    private fun subs(ctx: Context, tm: TelephonyManager): List<SubRef> {
        if (phoneGranted(ctx)) runCatching {
            val sm = ctx.applicationContext.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            @Suppress("MissingPermission")
            val list = sm.activeSubscriptionInfoList.orEmpty()
            if (list.isNotEmpty()) return list.map { SubRef(it.subscriptionId, it.simSlotIndex, it.carrierName?.toString()) }
                .sortedBy { it.slot }
        }
        // Without READ_PHONE_STATE: the default data / voice / SMS SIMs are public ids.
        val ids = listOf(
            defaultDataSubId(),
            runCatching { SubscriptionManager.getDefaultVoiceSubscriptionId() }.getOrDefault(-1),
            runCatching { SubscriptionManager.getDefaultSmsSubscriptionId() }.getOrDefault(-1),
        ).filter { it >= 0 }.distinct()
        if (ids.isNotEmpty()) return ids.map { id ->
            SubRef(id, if (Build.VERSION.SDK_INT >= 29) runCatching { SubscriptionManager.getSlotIndex(id) }.getOrDefault(-1) else -1, null)
        }.sortedBy { it.slot }
        // No subscription at all: a SIM may still sit in a slot (locked, not provisioned).
        val slots = runCatching { if (Build.VERSION.SDK_INT >= 30) tm.activeModemCount else @Suppress("DEPRECATION") tm.phoneCount }.getOrDefault(1)
        val present = (0 until slots.coerceAtLeast(1)).filter { s ->
            runCatching { tm.getSimState(s) }.getOrDefault(TelephonyManager.SIM_STATE_UNKNOWN) !in
                setOf(TelephonyManager.SIM_STATE_ABSENT, TelephonyManager.SIM_STATE_UNKNOWN, TelephonyManager.SIM_STATE_NOT_READY)
        }
        return present.map { SubRef(MobileLink.NO_SUB, it, null) }
    }

    /** ServiceState.isEmergencyOnly() is hidden: the state constant, then the (greylisted) method, then its dump. */
    fun emergencyOnly(ss: ServiceState): Boolean {
        if (ss.state == ServiceState.STATE_EMERGENCY_ONLY) return true
        return runCatching { ss.javaClass.getMethod("isEmergencyOnly").invoke(ss) as Boolean }.getOrNull()
            ?: runCatching { ss.toString().contains("mIsEmergencyOnly=true") }.getOrDefault(false)
    }

    fun level(ss: SignalStrength?): Int? = ss?.let { runCatching { SignalLevels.cell(it.level) }.getOrNull() }

    fun dbm(ss: SignalStrength?): Int? = if (ss == null || Build.VERSION.SDK_INT < 29) null else runCatching {
        ss.cellSignalStrengths.map { it.dbm }.firstOrNull { it != Int.MAX_VALUE && it < 0 }
    }.getOrNull()

    private fun sim(ctx: Context, ref: SubRef): MobileLink.Sim {
        val tm = forSub(ctx, ref.subId) ?: return MobileLink.Sim(ref.subId, ref.slot, ref.carrier)
        val l = live[ref.subId]
        val ss = if (phoneGranted(ctx)) runCatching { @Suppress("MissingPermission") tm.serviceState }.getOrNull() else null
        val signal = if (Build.VERSION.SDK_INT >= 28) runCatching { tm.signalStrength }.getOrNull() else null
        val dataUser = runCatching {
            if (Build.VERSION.SDK_INT >= 31) tm.isDataEnabledForReason(TelephonyManager.DATA_ENABLED_REASON_USER)
            else @Suppress("MissingPermission") tm.isDataEnabled
        }.getOrNull()
        val block = if (Build.VERSION.SDK_INT < 31) null else listOf(
            TelephonyManager.DATA_ENABLED_REASON_CARRIER to MobileLink.Block.CARRIER,
            TelephonyManager.DATA_ENABLED_REASON_POLICY to MobileLink.Block.POLICY,
            TelephonyManager.DATA_ENABLED_REASON_THERMAL to MobileLink.Block.THERMAL,
        ).firstOrNull { (r, _) -> runCatching { !tm.isDataEnabledForReason(r) }.getOrDefault(false) }?.second
        val dataRoaming = if (Build.VERSION.SDK_INT >= 29) runCatching { @Suppress("MissingPermission") tm.isDataRoamingEnabled }.getOrNull()
            else runCatching { Settings.Global.getInt(ctx.contentResolver, Settings.Global.DATA_ROAMING) == 1 }.getOrNull()
        val netType = l?.networkType?.takeIf { it != 0 }
            ?: (if (phoneGranted(ctx)) runCatching { @Suppress("MissingPermission") tm.dataNetworkType }.getOrNull()?.takeIf { it != 0 }
                ?: runCatching { @Suppress("MissingPermission") tm.voiceNetworkType }.getOrNull() else null) ?: 0
        val carrier = runCatching { tm.networkOperatorName }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: ref.carrier ?: runCatching { tm.simOperatorName }.getOrNull()
        return MobileLink.Sim(
            subId = ref.subId, slot = ref.slot, carrier = carrier,
            serviceState = ss?.state ?: l?.serviceState,
            emergencyOnly = ss?.let(::emergencyOnly) ?: l?.emergencyOnly ?: false,
            roaming = ss?.roaming ?: l?.roaming ?: runCatching { tm.isNetworkRoaming }.getOrDefault(false),
            dataUser = dataUser, dataBlock = block, dataRoaming = dataRoaming,
            networkType = netType, overrideType = l?.overrideType ?: 0,
            level = l?.level ?: level(signal) ?: SignalLevels.NONE,
            dbm = l?.dbm ?: dbm(signal),
        )
    }

    /** The inputs of [MobileLink.derive], now. Cheap binder reads; safe on the main thread. */
    fun read(ctx: Context, cellularTransport: Boolean = false): MobileLink.Inputs {
        val tm = base(ctx) ?: return MobileLink.Inputs(airplane(ctx), emptyList(), MobileLink.NO_SUB, cellularTransport)
        val sims = subs(ctx, tm).map { sim(ctx, it) }
        return MobileLink.Inputs(airplane(ctx), sims, defaultDataSubId(), cellularTransport)
    }

    /** The popup-only facts the public API gives (the privileged ones come later through the shell). */
    @Suppress("DEPRECATION")
    fun detail(ctx: Context, s: MobileLink.Sim): MobileLink.Detail {
        val tm = forSub(ctx, s.subId) ?: return MobileLink.Detail()
        val phone = phoneGranted(ctx)
        val cc = if (phone && s.subId >= 0) runCatching {
            (ctx.applicationContext.getSystemService(Context.CARRIER_CONFIG_SERVICE) as android.telephony.CarrierConfigManager)
                .getConfigForSubId(s.subId)
        }.getOrNull() else null
        // The VoLTE / Wi-Fi calling switches: ImsMmTelManager needs READ_PRECISE_PHONE_STATE (privileged) on most builds.
        val ims: android.telephony.ims.ImsMmTelManager? = if (Build.VERSION.SDK_INT >= 30 && s.subId >= 0) runCatching {
            ctx.applicationContext.getSystemService(android.telephony.ims.ImsManager::class.java)?.getImsMmTelManager(s.subId)
        }.getOrNull() else null
        val signal = if (Build.VERSION.SDK_INT >= 28) runCatching { tm.signalStrength }.getOrNull() else null
        val allowed = if (Build.VERSION.SDK_INT >= 31) runCatching {
            MobileLink.familiesLabel(MobileLink.familiesOfBitmask(tm.getAllowedNetworkTypesForReason(TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER)))
        }.getOrNull() else null
        return MobileLink.Detail(
            mccMnc = MobileLink.mccMnc(runCatching { tm.networkOperator }.getOrNull()),
            voiceType = if (phone) runCatching { @Suppress("MissingPermission") tm.voiceNetworkType }.getOrNull() else null,
            volte = ims?.let { runCatching { if (it.isAdvancedCallingSettingEnabled) 1 else 0 }.getOrNull() },
            wfc = ims?.let { runCatching { if (it.isVoWiFiSettingEnabled) 1 else 0 }.getOrNull() },
            volteAvailable = cc?.let { runCatching { it.getBoolean(android.telephony.CarrierConfigManager.KEY_CARRIER_VOLTE_AVAILABLE_BOOL) }.getOrNull() },
            wfcAvailable = cc?.let { runCatching { it.getBoolean(android.telephony.CarrierConfigManager.KEY_CARRIER_WFC_IMS_AVAILABLE_BOOL) }.getOrNull() },
            nrAvailability = if (Build.VERSION.SDK_INT >= 31) cc?.let {
                runCatching { it.getIntArray(android.telephony.CarrierConfigManager.KEY_CARRIER_NR_AVAILABILITIES_INT_ARRAY)?.toList() }.getOrNull()
            } else null,
            preferredMode = allowed,
            signals = cellSignals(signal),
        )
    }

    private fun av(v: Int): Int? = v.takeIf { it != Int.MAX_VALUE }

    fun cellSignals(ss: SignalStrength?): List<MobileLink.CellSig> = if (ss == null || Build.VERSION.SDK_INT < 29) emptyList() else runCatching {
        ss.cellSignalStrengths.mapNotNull { c ->
            when (c) {
                is CellSignalStrengthNr -> MobileLink.CellSig("5G", av(c.ssRsrp), av(c.ssRsrq), av(c.ssSinr))
                is CellSignalStrengthLte -> MobileLink.CellSig("4G", av(c.rsrp), av(c.rsrq), av(c.rssnr))
                is CellSignalStrengthWcdma -> MobileLink.CellSig("3G", dbm = av(c.dbm))
                is CellSignalStrengthTdscdma -> MobileLink.CellSig("3G", dbm = av(c.dbm))
                is CellSignalStrengthGsm -> MobileLink.CellSig("2G", dbm = av(c.dbm))
                is CellSignalStrengthCdma -> MobileLink.CellSig("2G", dbm = av(c.dbm))
                else -> null
            }
        }
    }.getOrDefault(emptyList())

    /** The shell command reading the privileged switches of [subIds]: the SIM database, then each preferred network mode. */
    fun shellCommand(subIds: List<Int>): String {
        val q = "content query --uri content://telephony/siminfo --projection"
        val modes = subIds.filter { it >= 0 }.joinToString("; ") { "echo \"pnm$it=\$(settings get global preferred_network_mode$it)\"" }
        return "o=\$($q _id:volte_vt_enabled:wfc_ims_enabled:nr_advanced_calling_enabled 2>&1); " +
            "case \"\$o\" in *Row:*) echo \"\$o\";; *) $q _id:volte_vt_enabled:wfc_ims_enabled 2>/dev/null;; esac; " +
            (if (modes.isEmpty()) "" else "$modes; ") + "echo done"
    }

    /** Fold the shell's answer into [d] for [subId]. */
    fun withShell(d: MobileLink.Detail, out: String, subId: Int): MobileLink.Detail {
        val row = MobileLink.parseSimInfo(out, subId)
        val pnm = Regex("^pnm$subId=(-?\\d+)\\s*$", RegexOption.MULTILINE).find(out)?.groupValues?.get(1)?.toIntOrNull()
        return d.copy(
            volte = d.volte ?: row["volte_vt_enabled"],
            wfc = d.wfc ?: row["wfc_ims_enabled"],
            vonr = d.vonr ?: row["nr_advanced_calling_enabled"],
            preferredMode = d.preferredMode ?: pnm?.let { MobileLink.familiesLabel(MobileLink.familiesOfRilMode(it)) },
        )
    }

    /**
     * The strip's feed: [onChange] runs on the main thread whenever the data SIM's signal, service,
     * network type or data connection changes, a data / roaming / airplane switch flips, or the
     * default data SIM changes (the callbacks then move to the new one).
     */
    class Watch(ctx: Context, private val onChange: (signalOnly: Boolean) -> Unit) {
        private val app = ctx.applicationContext
        private val main = Handler(Looper.getMainLooper())
        private var subId = MobileLink.NO_SUB
        private var tm: TelephonyManager? = null
        private val listeners = mutableListOf<Any>()
        private var started = false

        // Re-entrancy guard + coalescer, all on the main thread. The observer below watches Settings.Global
        // switches; if the consumer's [onChange] (directly, or anything it recreates/writes) touches one of
        // those URIs, the observer fires again. Calling [onChange] straight from the observer made that a
        // self-feeding loop — the crash log is hundreds of identical onChange frames (StackOverflowError),
        // and even async it would peg the CPU and the shell. So every source routes through [fire]: a change
        // provoked while we are already delivering one is dropped ([inDispatch]), and a burst of switch flips
        // is coalesced into one delivery ([coalesce]).
        private var inDispatch = false
        private val coalesce = Runnable { dispatch(false) }

        private fun dispatch(signalOnly: Boolean) {
            inDispatch = true
            try { onChange(signalOnly) } finally { inDispatch = false }
        }

        /** Hand one change to the consumer, never re-entrantly: the single point every event goes through. */
        private fun fire(signalOnly: Boolean) {
            if (inDispatch) return               // a change we provoked while reacting: ignore, no feedback loop
            if (signalOnly) { main.post { dispatch(true) }; return }
            main.removeCallbacks(coalesce)
            main.postDelayed(coalesce, COALESCE_MS)
        }

        private val observer = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) { fire(false) }
        }
        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { rebind(); fire(false) }
        }

        fun start() {
            if (started) return
            started = true
            bind()
            runCatching {
                androidx.core.content.ContextCompat.registerReceiver(app, receiver, IntentFilter().apply {
                    addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
                    addAction(ACTION_DEFAULT_DATA_SUB_CHANGED)
                    addAction(ACTION_SIM_STATE_CHANGED)
                }, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
            }
        }

        fun stop() {
            if (!started) return
            started = false
            main.removeCallbacks(coalesce)
            unbind()
            runCatching { app.unregisterReceiver(receiver) }
        }

        private companion object {
            /** A burst of switch flips (the system writes several of the observed keys at once) is one repaint. */
            const val COALESCE_MS = 150L
        }

        private fun rebind() {
            if (!started || defaultDataSubId() == subId) return
            unbind(); bind()
        }

        private fun bind() {
            subId = defaultDataSubId()
            val t = forSub(app, subId) ?: return
            tm = t
            val sub = subId
            // The switches the system writes when the user flips them (the per-SIM copy is "mobile_data<subId>").
            runCatching {
                val cr = app.contentResolver
                val keys = listOf("mobile_data", "data_roaming", Settings.Global.AIRPLANE_MODE_ON) +
                    (if (sub >= 0) listOf("mobile_data$sub", "data_roaming$sub") else emptyList())
                for (k in keys) cr.registerContentObserver(Settings.Global.getUriFor(k), false, observer)
            }
            val changed: (Boolean) -> Unit = { signalOnly -> fire(signalOnly) }
            if (Build.VERSION.SDK_INT >= 31) {
                // One callback per listener, each registered alone: a listener this app may not use
                // throws, and must not take the others down with it.
                for (cb in listOf<android.telephony.TelephonyCallback>(
                    SignalCb31(sub, changed), ServiceCb31(sub, changed), DisplayCb31(sub, changed), DataCb31(sub, changed),
                )) runCatching { t.registerTelephonyCallback(app.mainExecutor, cb); listeners += cb }
            } else {
                @Suppress("DEPRECATION")
                val l = LegacyListener(sub, changed)
                @Suppress("DEPRECATION")
                runCatching {
                    t.listen(l, android.telephony.PhoneStateListener.LISTEN_SIGNAL_STRENGTHS or
                        android.telephony.PhoneStateListener.LISTEN_SERVICE_STATE or
                        android.telephony.PhoneStateListener.LISTEN_DATA_CONNECTION_STATE)
                    listeners += l
                }
            }
        }

        private fun unbind() {
            runCatching { app.contentResolver.unregisterContentObserver(observer) }
            val t = tm ?: return
            for (l in listeners) runCatching {
                if (Build.VERSION.SDK_INT >= 31 && l is android.telephony.TelephonyCallback) t.unregisterTelephonyCallback(l)
                else if (l is android.telephony.PhoneStateListener) {
                    @Suppress("DEPRECATION")
                    t.listen(l, android.telephony.PhoneStateListener.LISTEN_NONE)
                }
            }
            listeners.clear()
            tm = null
        }
    }

    /** TelephonyManager.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED (SubscriptionManager's, API 26+). */
    const val ACTION_DEFAULT_DATA_SUB_CHANGED = "android.intent.action.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED"
    const val ACTION_SIM_STATE_CHANGED = "android.intent.action.SIM_STATE_CHANGED"

    internal fun onSignal(sub: Int, ss: SignalStrength?) = push(sub) { it.copy(level = level(ss), dbm = dbm(ss)) }
    internal fun onService(sub: Int, ss: ServiceState?) {
        if (ss != null) push(sub) { it.copy(serviceState = ss.state, emergencyOnly = emergencyOnly(ss), roaming = ss.roaming) }
    }
    @androidx.annotation.RequiresApi(31)
    internal fun onDisplay(sub: Int, d: TelephonyDisplayInfo) = push(sub) { it.copy(networkType = d.networkType, overrideType = d.overrideNetworkType) }
    internal fun onDataConnection(sub: Int, networkType: Int) {
        // Once display info has arrived it owns the network type (it tells 5G NSA apart); before that, this one.
        if (networkType != 0) push(sub) { if (it.overrideType != null) it else it.copy(networkType = networkType) }
    }
}

// The API 31+ callbacks, one class per listener so pre-31 runtimes never load TelephonyCallback and a
// listener needing a permission the app lacks fails alone.
@androidx.annotation.RequiresApi(31)
private class SignalCb31(val sub: Int, val changed: (Boolean) -> Unit) :
    android.telephony.TelephonyCallback(), android.telephony.TelephonyCallback.SignalStrengthsListener {
    override fun onSignalStrengthsChanged(ss: SignalStrength) { MobileProbe.onSignal(sub, ss); changed(true) }
}

@androidx.annotation.RequiresApi(31)
private class ServiceCb31(val sub: Int, val changed: (Boolean) -> Unit) :
    android.telephony.TelephonyCallback(), android.telephony.TelephonyCallback.ServiceStateListener {
    override fun onServiceStateChanged(ss: ServiceState) { MobileProbe.onService(sub, ss); changed(false) }
}

@androidx.annotation.RequiresApi(31)
private class DisplayCb31(val sub: Int, val changed: (Boolean) -> Unit) :
    android.telephony.TelephonyCallback(), android.telephony.TelephonyCallback.DisplayInfoListener {
    override fun onDisplayInfoChanged(d: TelephonyDisplayInfo) { MobileProbe.onDisplay(sub, d); changed(false) }
}

@androidx.annotation.RequiresApi(31)
private class DataCb31(val sub: Int, val changed: (Boolean) -> Unit) :
    android.telephony.TelephonyCallback(), android.telephony.TelephonyCallback.DataConnectionStateListener {
    override fun onDataConnectionStateChanged(state: Int, networkType: Int) { MobileProbe.onDataConnection(sub, networkType); changed(false) }
}

/** Android 8-11: the same events (bar display info, which tells 5G NSA apart) through the older PhoneStateListener. */
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
private class LegacyListener(val sub: Int, val changed: (Boolean) -> Unit) : android.telephony.PhoneStateListener() {
    @Deprecated("Deprecated in Java")
    override fun onSignalStrengthsChanged(ss: SignalStrength?) { MobileProbe.onSignal(sub, ss); changed(true) }
    @Deprecated("Deprecated in Java")
    override fun onServiceStateChanged(ss: ServiceState?) { MobileProbe.onService(sub, ss); changed(false) }
    @Deprecated("Deprecated in Java")
    override fun onDataConnectionStateChanged(state: Int, networkType: Int) { MobileProbe.onDataConnection(sub, networkType); changed(false) }
}
