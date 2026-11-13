package com.diegonmarcos.superapp.settings

import com.diegonmarcos.superapp.bgpolicy.BgEngine
import com.diegonmarcos.superapp.bgpolicy.BgEssential
import com.diegonmarcos.superapp.bgpolicy.BgFakeDevice
import com.diegonmarcos.superapp.bgpolicy.BgKv
import com.diegonmarcos.superapp.bgpolicy.BgPkg
import com.diegonmarcos.superapp.bgpolicy.BgShell
import com.diegonmarcos.superapp.bgpolicy.BgStore
import com.diegonmarcos.superapp.bgpolicy.SsDecl
import com.diegonmarcos.superapp.bgpolicy.SsHost
import com.diegonmarcos.superapp.bgpolicy.SsStep
import com.diegonmarcos.superapp.bgpolicy.SuperSaving
import org.json.JSONObject
import java.io.File

/**
 * #1720 A fake phone under the REAL mode declarations (build.json::ui.launcher_modes and assets/super-saving.json): the
 * [ModeSwitch] and the [SuperSaving] engines are the production classes, only the shell, the host and the store are fakes.
 */
internal class ModeRig(vararg apps: String = arrayOf("com.a", "com.b", "com.diegonmarcos.cloudchat")) {

    class Host(private val pkgs: List<BgPkg>) : SsHost {
        var syncOn = true; var idleOn = false; var keeperOn = true
        var maker = ""; var api = 34
        /** The app without the sync-settings permissions: every read and write of the master sync switch throws. */
        var syncThrows = false
        val toggles = HashMap<String, Boolean>()
        override fun masterSync(): Boolean { if (syncThrows) throw SecurityException("no READ_SYNC_SETTINGS"); return syncOn }
        override fun setMasterSync(on: Boolean) { if (syncThrows) throw SecurityException("no WRITE_SYNC_SETTINGS"); syncOn = on }
        override fun idleArmed() = idleOn
        override fun armIdle(on: Boolean) { idleOn = on }
        override fun keeperEnabled() = keeperOn
        override fun setKeeper(on: Boolean) { keeperOn = on }
        override fun packages() = pkgs
        override fun toggle(id: String): Boolean? = toggles[id]
        override fun setToggles(want: Map<String, Boolean>) { toggles.putAll(want) }
        override fun manufacturer() = maker
        override fun sdk() = api
    }

    val dev = BgFakeDevice(*apps)
    val host = Host(apps.map { BgPkg(it, it, false) })
    val kv: BgKv = BgFakeDevice.kv()
    val audit = ArrayList<String>()
    var sel: String? = null
    /** null = the ADB shell is down. */
    var shell: BgShell? = dev

    init {
        dev.settings.putAll(mapOf(
            "global/low_power" to "0", "global/wifi_scan_always_enabled" to "1", "global/ble_scan_always_enabled" to "1", "secure/location_mode" to "3",
            "global/bluetooth_on" to "1", "global/mobile_data" to "1", "global/preferred_network_mode" to "33",
            "system/peak_refresh_rate" to "120", "system/min_refresh_rate" to "120", "system/screen_brightness_mode" to "0",
            "system/screen_brightness" to "200", "system/screen_off_timeout" to "60000", "secure/doze_always_on" to "1",
            "global/window_animation_scale" to "1", "global/transition_animation_scale" to "1",
            "global/heads_up_notifications_enabled" to "1", "global/zen_mode" to "0",
        ))
        dev.nfc = "on"; dev.night = "auto"
        // every switch a mode names exists on this phone; the three "saving" switches start off, the rest on
        for (id in ids) decl.getValue(id).optJSONObject("toggles")?.let { t -> for (k in t.keys()) host.toggles[k] = k !in setOf("background_pause", "oled_black", "analytics_batch") }
    }

    /** Samsung keys as a One UI build holds them. */
    fun samsung(): ModeRig {
        host.maker = "samsung"
        dev.settings.putAll(mapOf("secure/refresh_rate_mode" to "1", "global/sem_enhanced_cpu_responsiveness" to "0", "system/aod_mode" to "1", "system/edge_lighting" to "1"))
        return this
    }

    /** Everything a mode can touch, as plain data, to compare before and after. */
    fun world(): List<Any?> = listOf(
        dev.settings.toSortedMap().toString(), dev.nfc, dev.night, dev.idleForced,
        dev.state.mapValues { listOf(it.value.any, it.value.inn, it.value.bucket, it.value.idle) }.toString(),
        host.syncOn, host.idleOn, host.keeperOn, host.toggles.toSortedMap().toString(),
    )

    fun engine(id: String, key: String): SuperSaving? {
        val st = stepsFor(id) ?: return null
        val bg = BgEngine(shell, BgEssential(cfg, setOf("com.diegonmarcos.superapp")), BgStore(kv), { audit += it })
        return SuperSaving(st, shell, host, bg, cfg, kv, { audit += it }, modeId = id, label = decl.getValue(id).getString("label"), tag = "mode-$id", journalKey = key)
    }

    fun switch() = ModeSwitch({ ids }, { sel }, { sel = it }, kv, engine = { id, key -> engine(id, key) },
        journalOnly = { decl[it]?.optString("engine").orEmpty().isNotEmpty() }, audit = { audit += it })

    companion object {
        private val modes = JSONObject(File("../build.json").readText()).getJSONObject("ui").getJSONArray("launcher_modes")
        val decl: Map<String, JSONObject> = (0 until modes.length()).map { modes.getJSONObject(it) }.associateBy { it.getString("id") }
        val ids: List<String> = decl.keys.toList()
        private val asset = File("src/main/assets/super-saving.json").readText()
        private val cfg = BgFakeDevice.cfg()

        /** The steps of a declared mode, built the way SuperSavingRuntime.stepsOf builds them. */
        fun stepsFor(id: String): List<SsStep>? {
            val o = decl[id] ?: return null
            if (o.optString("engine").isNotEmpty()) return SsDecl.parse(asset)
            val out = ArrayList<SsStep>()
            o.optJSONObject("toggles")?.let { out.add(SsStep("launcher", "Launcher switches", "launcher_toggles", false, false, JSONObject().put("toggles", it))) }
            o.optJSONArray("steps")?.let { out.addAll(SsDecl.parseArray(it)) }
            return out
        }
    }
}
