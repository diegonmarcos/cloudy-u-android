package org.fossify.phone.sim

import android.content.SharedPreferences
import java.io.File

/** Test doubles: an in-memory SharedPreferences, a recording secret vault and a fake SubscriptionManager. */
class FakePrefs : SharedPreferences {
    val map = HashMap<String, Any?>()
    override fun getAll(): MutableMap<String, *> = map
    override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String?, defValue: Int): Int = defValue
    override fun getLong(key: String?, defValue: Long): Long = defValue
    override fun getFloat(key: String?, defValue: Float): Float = defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
    override fun contains(key: String?): Boolean = map.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?) = apply { map[key!!] = value }
        override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { map[key!!] = values }
        override fun putInt(key: String?, value: Int) = apply { map[key!!] = value }
        override fun putLong(key: String?, value: Long) = apply { map[key!!] = value }
        override fun putFloat(key: String?, value: Float) = apply { map[key!!] = value }
        override fun putBoolean(key: String?, value: Boolean) = apply { map[key!!] = value }
        override fun remove(key: String?) = apply { map.remove(key) }
        override fun clear() = apply { map.clear() }
        override fun commit(): Boolean = true
        override fun apply() {}
    }
}

class FakeVault(var reachable: Boolean = true) : SimSecretVault {
    val held = HashMap<String, String>()
    override fun get(key: String, field: String): String? = if (reachable) held["$key.$field"].orEmpty() else null
    override fun put(key: String, field: String, value: String): Boolean {
        if (!reachable) return false
        held["$key.$field"] = value
        return true
    }
}

class FakeSubSource(
    val activeList: List<SubRecord> = emptyList(),
    val accessibleList: List<SubRecord> = emptyList(),
    override val slotCount: Int = 2,
    override val defaultData: Int = -1,
) : SubSource {
    override fun active() = activeList
    override fun accessible() = accessibleList
    override fun live(subscriptionId: Int) = SubLive(roaming = false, networkType = "LTE", signalLevel = 3)
    override val defaultVoice: Int = -1
    override val defaultSms: Int = -1
    override val eid: String? = "89049032000001000000000000001234"
}

val simSchema: SimSchema = SimSchema.parse(File("../../data/fleet/app/com.diegonmarcos.comms.dialer/sim-fields.json").readText())
