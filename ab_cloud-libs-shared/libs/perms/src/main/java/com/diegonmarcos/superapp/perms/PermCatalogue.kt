package com.diegonmarcos.superapp.perms

import org.json.JSONArray
import org.json.JSONObject

/**
 * The permission catalogue (#1274): every permission, special access, app-op and adb-only grant the app and its libs can
 * use, read from the data asset `perms-catalogue.json` (libs:perms assets). Nothing about a permission is hard-coded in
 * Kotlin: id, group, kind, section, SDK range, the settings page that grants it, the label to look for there, the adb
 * command and the features that need it all come from the asset. Pure (org.json only): JVM-tested.
 */
enum class PermKind { RUNTIME, SPECIAL, APPOPS, ADB_ONLY;
    companion object { fun of(s: String) = values().firstOrNull { it.name.equals(s.replace('-', '_'), true) } ?: SPECIAL }
}

/** Where a row lives on the page; the order here is the page order. */
enum class PermSection(val title: String) {
    RUNTIME("Runtime"), SPECIAL("Special access"), BATTERY("Battery & background"), SAMSUNG("Samsung"), ADB("ADB-only"),
    /** A system toggle that is not a grant of this app (Location on, the Wi-Fi scan throttle). */
    SYSTEM("System toggles");
    companion object { fun of(s: String) = values().firstOrNull { it.name.equals(s, true) } ?: SPECIAL }
}

/** The exact system page that grants an entry. [packageUri] = add `package:<pkg>` data; [highlightKey] = the preference key to scroll to. */
data class SettingsTarget(
    val action: String,
    val packageUri: Boolean = false,
    val highlightKey: String = "",
    val extraPackage: String = "",
    val samsungComponent: String = "",
    val fallbacks: List<String> = emptyList(),
)

data class PermEntry(
    val id: String,
    val label: String,
    val group: String,
    val kind: PermKind,
    val section: PermSection,
    /** Manifest permission name, when there is one (runtime, adb-only, and special entries backed by a permission). */
    val permission: String = "",
    /** AppOpsManager op string (e.g. android:system_alert_window) used to read the state of app-op entries. */
    val appop: String = "",
    /** Named live check for special entries (all_files, battery_opt, overlay, ...); see PermStatusReader. */
    val check: String = "",
    val minSdk: Int = 1,
    val maxSdk: Int = Int.MAX_VALUE,
    /** "samsung" = only on that OEM. Empty = every device. */
    val oem: String = "",
    /** false = never part of the open-time auto request (background location goes through settings on 30+). */
    val autoRequest: Boolean = true,
    /** Ids that must be granted first (background location after fine location). */
    val requires: List<String> = emptyList(),
    val settings: SettingsTarget? = null,
    val lookFor: String = "",
    val instruction: String = "",
    /** Shell commands without the `adb shell` prefix; {pkg}, {listener}, {a11y}, {admin} are filled at generation time. */
    val adb: List<String> = emptyList(),
    val features: List<String> = emptyList(),
    /** #1372 the one plain-language line under the row's title, and the name of its icon in [PermCatalogue.icons]. */
    val why: String = "",
    val icon: String = "",
)

data class PermCatalogue(val entries: List<PermEntry>, val groupOrder: List<String>, val icons: Map<String, String> = emptyMap()) {

    fun byId(id: String): PermEntry? = entries.firstOrNull { it.id == id }

    /** The entries a feature declares it needs (catalogue `features`). */
    fun forFeature(feature: String): List<PermEntry> = entries.filter { feature in it.features }

    /** Applies on this device at all: SDK window and OEM. Manifest declaration is checked by the Android reader. */
    fun applicable(e: PermEntry, sdk: Int, manufacturer: String): Boolean =
        sdk >= e.minSdk && sdk <= e.maxSdk && (e.oem.isEmpty() || manufacturer.contains(e.oem, ignoreCase = true))

    /** Why [applicable] said no, for the row's reason text. */
    fun notApplicableReason(e: PermEntry, sdk: Int, manufacturer: String): String = when {
        sdk < e.minSdk -> "needs Android API ${e.minSdk}+ (this is $sdk)"
        sdk > e.maxSdk -> "replaced after API ${e.maxSdk} (this is $sdk)"
        e.oem.isNotEmpty() && !manufacturer.contains(e.oem, true) -> "${e.oem} devices only"
        else -> ""
    }

    companion object {
        const val ASSET = "perms-catalogue.json"

        fun parse(json: String): PermCatalogue {
            val root = JSONObject(json)
            val groups = root.optJSONArray("group_order").strings()
            val arr = root.getJSONArray("entries")
            val out = ArrayList<PermEntry>(arr.length())
            val seen = HashSet<String>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.getString("id")
                require(seen.add(id)) { "duplicate permission id $id" }
                val s = o.optJSONObject("settings")
                out += PermEntry(
                    id = id,
                    label = o.optString("label", id),
                    group = o.optString("group", "OTHER"),
                    kind = PermKind.of(o.optString("kind", "special")),
                    section = PermSection.of(o.optString("section", "special")),
                    permission = o.optString("permission", ""),
                    appop = o.optString("appop", ""),
                    check = o.optString("check", ""),
                    minSdk = o.optInt("min_sdk", 1),
                    maxSdk = if (o.has("max_sdk")) o.getInt("max_sdk") else Int.MAX_VALUE,
                    oem = o.optString("oem", ""),
                    autoRequest = o.optBoolean("auto_request", true),
                    requires = o.optJSONArray("requires").strings(),
                    settings = s?.let {
                        SettingsTarget(
                            action = it.getString("action"),
                            packageUri = it.optBoolean("package_uri", false),
                            highlightKey = it.optString("highlight_key", ""),
                            extraPackage = it.optString("extra_package", ""),
                            samsungComponent = it.optString("samsung_component", ""),
                            fallbacks = it.optJSONArray("fallbacks").strings(),
                        )
                    },
                    lookFor = o.optString("look_for", ""),
                    instruction = o.optString("instruction", ""),
                    adb = o.optJSONArray("adb").strings(),
                    features = o.optJSONArray("features").strings(),
                    why = o.optString("why", ""),
                    icon = o.optString("icon", ""),
                )
            }
            val icons = root.optJSONObject("icons")?.let { ic -> ic.keys().asSequence().associateWith { ic.getString(it) } }.orEmpty()
            return PermCatalogue(out, groups, icons)
        }

        private fun JSONArray?.strings(): List<String> =
            if (this == null) emptyList() else (0 until length()).map { getString(it) }
    }
}

/** Live state of one row. */
enum class PermState { GRANTED, DENIED, PERMANENTLY_DENIED, NOT_APPLICABLE, UNKNOWN }

data class PermStatus(val state: PermState, val reason: String = "") {
    val missing: Boolean get() = state == PermState.DENIED || state == PermState.PERMANENTLY_DENIED
}

/**
 * Which runtime permissions the page asks for on open, in system-dialog batches (one batch per Android permission group,
 * in catalogue group order). Never re-asks within a session ([askedThisSession]), never asks a permanently-denied one
 * (the dialog would be a silent no-op), never asks an entry whose prerequisites are not granted yet. A batch result only
 * shrinks the remaining set, so the sequence always terminates. Pure: JVM-tested.
 */
object AutoRequestPlanner {
    fun batches(
        cat: PermCatalogue,
        status: (PermEntry) -> PermStatus,
        askedThisSession: Set<String>,
    ): List<List<PermEntry>> {
        val byId = cat.entries.associateBy { it.id }
        val wanted = cat.entries.filter { e ->
            e.kind == PermKind.RUNTIME && e.autoRequest && e.permission.isNotEmpty() &&
                e.permission !in askedThisSession &&
                status(e).state == PermState.DENIED &&
                e.requires.all { r -> byId[r]?.let { status(it).state == PermState.GRANTED } ?: true }
        }
        val order = cat.groupOrder
        return wanted.groupBy { it.group }
            .toList()
            .sortedBy { (g, _) -> order.indexOf(g).let { if (it < 0) Int.MAX_VALUE else it } }
            .map { (_, v) -> v.distinctBy { it.permission } }
    }
}

/** One shell line of the "Grant all via ADB" block, tagged with the entry it grants. */
data class AdbLine(val entryId: String, val command: String) {
    /** The same line typed on a computer: `adb shell <cmd>`, single-quoted when it needs the device's shell. */
    fun forComputer(): String =
        if (command.any { it in "$;|&()<>*\"" }) "adb shell '" + command.replace("'", "'\\''") + "'"
        else "adb shell $command"
}

/**
 * The full grant block (#1274): pm grant for runtime and adb-only permissions, appops set for app-ops / special access,
 * cmd deviceidle whitelist, settings put for accessibility, cmd notification for listener / DND. Lines come from the
 * catalogue; placeholders are filled from [vars] ({pkg} always). A line whose placeholder has no value on this device
 * (no notification listener declared, say) is dropped, never emitted half-filled. Pure: JVM-tested.
 */
object AdbScript {
    private val PLACEHOLDER = Regex("\\{([a-z0-9_]+)\\}")

    fun lines(entries: List<PermEntry>, vars: Map<String, String>): List<AdbLine> {
        val out = LinkedHashMap<String, AdbLine>()
        for (e in entries) for (raw in e.adb) {
            var ok = true
            val cmd = PLACEHOLDER.replace(raw) { m -> vars[m.groupValues[1]]?.takeIf { it.isNotEmpty() } ?: run { ok = false; "" } }
            if (ok && cmd !in out) out[cmd] = AdbLine(e.id, cmd)
        }
        return out.values.toList()
    }

    fun block(lines: List<AdbLine>, pkg: String): String =
        (listOf("# Grant all for $pkg (${lines.size} commands)") + lines.map { it.forComputer() }).joinToString("\n")
}
