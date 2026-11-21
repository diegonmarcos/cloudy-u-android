package com.diegonmarcos.superapp.system

import android.content.Context
import android.provider.Settings
import com.diegonmarcos.superapp.bgpolicy.DeviceRead
import com.diegonmarcos.superapp.bgpolicy.DeviceWrite
import com.diegonmarcos.superapp.configs.DeviceControls
import com.diegonmarcos.superapp.configs.SecurityControls
import org.json.JSONArray
import org.json.JSONObject

/**
 * #1847 Banking Apps Mode: the device side of the `device` steps declared in build.json::ui.launcher_modes (mode `banking`).
 * The ordering, the snapshot of what the mode itself changed, the restore in reverse and the per-item verdict are the one mode
 * engine's ([com.diegonmarcos.superapp.bgpolicy.SuperSaving], `device` kind); this file only answers "what does the device say
 * about item X" and "switch X".
 *
 * THE PAIRING GATE (#1833): nothing here connects, arms, pairs or starts the ADB owner. Items are Settings.Global writes
 * (WRITE_SECURE_SETTINGS, else the shell broker ONLY when it is already armed: SecurityControls.setGlobal never arms it), the
 * keep-alive switch (a stored choice, step kind `keeper`), accessibility entries and overlay switches. The ADB shell item closes
 * with the debugging that carries it and is never re-armed from here: it comes back through the keep-alive's implicit
 * reconnect, which ServerAutoStart.mayPair keeps from ever posting the pairing prompt.
 */
object BankingMode {
    const val MODE_ID = "banking"

    /** The report link that opens the Perms page on its accessibility row (PermsLink), not Android's Settings directly. */
    const val PERMS_A11Y = "perms:accessibility"

    /** The Settings screen the owner uses when Android allows no programmatic change of an item. */
    fun link(item: String): String = when (item) {
        "developer_options", "usb_debugging", "wireless_debugging" -> DeviceControls.developmentSettingsAction()
        // #1515 one place: accessibility is granted on the Perms page, so the owner's link is a Perms row, never a raw Settings action.
        "a11y_fleet" -> PERMS_A11Y
        else -> ""
    }

    private fun fromProbe(p: DeviceControls.Probe) = DeviceRead(p.state, p.detail)

    fun read(ctx: Context, item: String): DeviceRead = runCatching {
        if (item in skipped(ctx)) return DeviceRead(null, "left alone: it is in this mode's skip list")
        when (item) {
            "developer_options" -> fromProbe(SecurityControls.devOptions(ctx))
            "usb_debugging" -> fromProbe(SecurityControls.usbDebugging(ctx))
            "wireless_debugging" -> fromProbe(SecurityControls.wirelessDebugging(ctx))
            "adb_shell" -> fromProbe(SecurityControls.shell(ctx))
            "a11y_fleet" -> {
                val c = fleetBound(ctx)
                DeviceRead(c.isNotEmpty(), if (c.isEmpty()) "no fleet accessibility service bound" else "bound: " + c.joinToString(", "), c.joinToString(","))
            }
            "vpn" -> if (!vpnWanted(ctx)) DeviceRead(false, "kept on: the per-mode VPN switch is off") else sample(ctx, "vpn")
            else -> sample(ctx, item)
        }
    }.getOrElse { DeviceRead(null, "${it.javaClass.simpleName}: ${it.message}") }

    /** A declared control of the Quick actions switch board (edge menus, floating menu ...): its live reading. */
    private fun sample(ctx: Context, id: String): DeviceRead {
        val c = DeviceControls.byId[id] ?: return DeviceRead(null, "no control $id")
        val p = c.sample(ctx)
        return DeviceRead(p.state, p.detail)
    }

    fun set(ctx: Context, item: String, on: Boolean, captured: String): DeviceWrite = runCatching {
        when (item) {
            "developer_options" -> global(ctx, item, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, on) { SecurityControls.devOptionsOn(it) }
            "usb_debugging" -> global(ctx, item, Settings.Global.ADB_ENABLED, on) { SecurityControls.usbDebuggingOn(it) }
            "wireless_debugging" -> global(ctx, item, "adb_wifi_enabled", on) { SecurityControls.wirelessDebugging(it).state }
            // The server rides on debugging: it closes with it. Switching it back on is the keep-alive's reconnect, never a pairing.
            "adb_shell" -> if (on) DeviceWrite(true, "back through the keep-alive's reconnect; this mode never pairs", verify = false)
                           else DeviceWrite(false, "closes with USB and wireless debugging")
            "a11y_fleet" -> fleet(ctx, on, captured)
            else -> {
                val c = DeviceControls.byId[item] ?: return DeviceWrite(false, "no control $item")
                val set = c.set ?: return DeviceWrite(false, "$item has no switch")
                val why = c.blocked(ctx)
                if (why.isNotEmpty()) DeviceWrite(false, why, link(item))
                else set(ctx, on).let { DeviceWrite(it.ok, it.detail) }
            }
        }
    }.getOrElse { DeviceWrite(false, "${it.javaClass.simpleName}: ${it.message}", link(item)) }

    private fun global(ctx: Context, item: String, key: String, on: Boolean, read: (Context) -> Boolean?): DeviceWrite {
        if (!SecurityControls.canWrite(ctx)) return DeviceWrite(false, SecurityControls.NO_CHANNEL, link(item))
        val w = SecurityControls.setGlobal(ctx, key, on, read)
        return DeviceWrite(w.ok, w.detail, if (w.ok) "" else link(item))
    }

    // ── the fleet's accessibility services, as AccessibilityManager lists them live ──

    fun fleetBound(ctx: Context): List<String> =
        SecurityControls.bound(ctx).filter { SecurityControls.fleetPackage(it.substringBefore('/')) }.sorted()

    private fun fleet(ctx: Context, on: Boolean, captured: String): DeviceWrite {
        if (!SecurityControls.canWrite(ctx)) return DeviceWrite(false, SecurityControls.NO_CHANNEL, link("a11y_fleet"))
        val targets = if (on) captured.split(',').filter { it.isNotBlank() } else fleetBound(ctx)
        val notes = targets.map { c -> c.substringAfterLast('.') + ": " + SecurityControls.setService(ctx, c, on).detail }
        return DeviceWrite(true, notes.joinToString("; ").ifEmpty { "nothing to change" })
    }

    // ── the mode's own settings (stored): VPN switch, auto, banks ──

    private const val PREFS = "banking_mode"
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Items the mode leaves alone (stored, comma separated; empty by default). A seam for an emulator run that must keep its own adb link. */
    fun skipped(ctx: Context): Set<String> = (prefs(ctx).getString("skip", "") ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** Per-mode switch: also switch the VPN (the Cloud Mesh tunnel) off. Default keeps the VPN on. */
    fun vpnWanted(ctx: Context): Boolean = prefs(ctx).getBoolean("vpn_off", false)
    fun setVpnWanted(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("vpn_off", on).apply() }

    enum class Auto { OFF, OFFER, APPLY }
    fun auto(ctx: Context): Auto = runCatching { Auto.valueOf(prefs(ctx).getString("auto", null) ?: declaredAuto()) }.getOrDefault(Auto.OFF)
    fun setAuto(ctx: Context, a: Auto) { prefs(ctx).edit().putString("auto", a.name).apply() }
    fun nextAuto(a: Auto): Auto = Auto.values()[(a.ordinal + 1) % Auto.values().size]

    /** The mode applied itself for a bank (so leaving the bank may restore it); a mode the owner picked is never restored by leaving. */
    fun autoApplied(ctx: Context): Boolean = prefs(ctx).getBoolean("auto_applied", false)
    fun setAutoApplied(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("auto_applied", v).apply() }

    private fun declaredEntry(): JSONObject? = runCatching {
        val arr = JSONArray(String(android.util.Base64.decode(com.diegonmarcos.superapp.BuildConfig.UI_LAUNCHER_MODES_B64, android.util.Base64.NO_WRAP)))
        (0 until arr.length()).map { arr.getJSONObject(it) }.firstOrNull { it.optString("id") == MODE_ID }
    }.getOrNull()

    private fun declaredAuto() = declaredEntry()?.optString("auto_default", "off")?.uppercase() ?: "OFF"

    /** Pure: the declared banks (`banks`: [{pkg, label}]) of the mode entry. */
    fun parseBanks(entry: JSONObject?): Map<String, String> {
        val a = entry?.optJSONArray("banks") ?: return emptyMap()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optString("pkg").isNotBlank() }
            .associate { it.getString("pkg") to it.optString("label", it.getString("pkg")) }
    }

    fun declaredBanks(): Map<String, String> = parseBanks(declaredEntry())

    /** Banks the owner added in the mode's settings (stored, one package per line). */
    fun addedBanks(ctx: Context): List<String> = (prefs(ctx).getString("added_banks", "") ?: "").split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    fun addBank(ctx: Context, pkg: String) {
        val p = pkg.trim()
        if (p.isEmpty() || p in declaredBanks() || p in addedBanks(ctx)) return
        prefs(ctx).edit().putString("added_banks", (addedBanks(ctx) + p).joinToString("\n")).apply()
    }
    fun removeBank(ctx: Context, pkg: String) { prefs(ctx).edit().putString("added_banks", addedBanks(ctx).filter { it != pkg }.joinToString("\n")).apply() }
    fun banks(ctx: Context): Set<String> = declaredBanks().keys + addedBanks(ctx)

    /** What the foreground watcher does on a change of the foreground package. */
    enum class Action { NONE, OFFER_ON, APPLY_ON, OFFER_OFF, APPLY_OFF }

    /**
     * Pure: the foreground moved from [prev] to [fg]. A bank came to the foreground (and [prev] was not one) while the mode is off
     * -> offer or apply, by [auto]; the foreground left a bank while the mode is on -> offer the restore, or restore it when the
     * mode applied itself ([autoApplied]). Only a CHANGE acts, so one visit is one offer. [fg] null (unknown) never acts.
     */
    fun decide(fg: String?, prev: String?, banks: Set<String>, modeOn: Boolean, auto: Auto, autoApplied: Boolean): Action {
        if (fg == null || auto == Auto.OFF) return Action.NONE
        val inBank = fg in banks
        val wasBank = prev != null && prev in banks
        return when {
            inBank && !wasBank && !modeOn -> if (auto == Auto.APPLY) Action.APPLY_ON else Action.OFFER_ON
            !inBank && wasBank && modeOn -> if (auto == Auto.APPLY && autoApplied) Action.APPLY_OFF else Action.OFFER_OFF
            else -> Action.NONE
        }
    }
}
