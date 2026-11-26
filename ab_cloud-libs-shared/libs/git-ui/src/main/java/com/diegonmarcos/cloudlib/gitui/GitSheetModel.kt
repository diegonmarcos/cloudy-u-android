package com.diegonmarcos.cloudlib.gitui

import com.diegonmarcos.cloudlib.gitcore.CloneOptions
import com.diegonmarcos.cloudlib.gitcore.SyncOptions
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * #1934 THE UNIFIED REPO CONTROL SHEET MODEL, parsed from the host's DECLARATION (Cloud Drive build.json ui.git_control_sheet): tabs hold
 * groups, groups hold controls, a control is an action, a field, an option of a group, a flag, a preset, a preview or a run. This module draws
 * it ([GitControlSheetView]); it names no tab, group or control of its own, so another app reuses it with its own declaration. Pure.
 */
data class SheetControl(
    val id: String, val kind: String, val label: String, val action: String = "", val group: String = "", val value: String = "", val flag: String = "",
    val preset: String = "", val source: String = "", val plan: String = "", val hint: String = "", val reason: String = "", val labelFrom: String = "",
    val needsClone: Boolean = false, val danger: Boolean = false, val confirm: Boolean = false, val caution: Boolean = false, val disabled: Boolean = false,
    val locked: Boolean = false, val legacy: List<String> = emptyList(), val values: List<Int> = emptyList(), val note: String = "",
    val inapplicableWhen: Map<String, List<String>> = emptyMap(), val hosting: String = "", val writesRemote: Boolean = false,
)

data class SheetInput(val id: String, val key: String, val label: String, val default: String, val shownWhenOption: Map<String, String> = emptyMap())
data class SheetChips(val key: String, val values: List<Int>, val custom: Boolean, val shownWhenOption: Map<String, String> = emptyMap())

data class SheetGroup(
    val id: String, val label: String, val layout: String, val controls: List<SheetControl>, val option: String = "", val danger: Boolean = false,
    val input: SheetInput? = null, val chips: SheetChips? = null, val sameAs: String = "",
    val shownWhen: Map<String, List<String>> = emptyMap(), val hiddenWhen: Map<String, List<String>> = emptyMap(),
)

data class SheetTab(val id: String, val label: String, val groups: List<SheetGroup>)

data class SheetHeader(
    val actions: List<SheetControl>, val refresh: SheetControl?, val close: SheetControl?, val fields: List<SheetControl>,
    val banner: SheetControl?, val cloneNow: SheetControl?,
)

data class SheetPreset(val id: String, val label: String, val options: Map<String, Any>)

data class GitSheet(
    val header: SheetHeader, val tabs: List<SheetTab>, val bulkTabs: List<SheetTab>,
    val syncPresets: List<SheetPreset>, val clonePresets: List<SheetPreset>, val reasons: Map<String, String>,
    val syncDefaults: SyncOptions, val cloneDefaults: CloneOptions,
) {
    fun tabsFor(bulk: Boolean): List<SheetTab> = if (bulk) bulkTabs else tabs

    /** Every control of one sheet (header included), in display order. */
    fun controls(bulk: Boolean): List<SheetControl> =
        (if (bulk) emptyList() else header.actions + listOfNotNull(header.refresh, header.close) + header.fields + listOfNotNull(header.banner, header.cloneNow)) +
            tabsFor(bulk).flatMap { t -> t.groups.flatMap { it.controls } }

    /** The `legacy` ids of every control of both sheets: the parity tester's view. */
    fun legacyIds(): List<String> = (controls(false) + controls(true)).flatMap { it.legacy }

    companion object {
        private fun JsonObject.s(k: String, d: String = ""): String = (this[k] as? JsonPrimitive)?.contentOrNull ?: d
        private fun JsonObject.b(k: String): Boolean = (this[k] as? JsonPrimitive)?.booleanOrNull ?: false
        private fun objs(e: JsonElement?): List<JsonObject> = (e as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        private fun strs(e: JsonElement?): List<String> = (e as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
        private fun tr(o: JsonObject, key: String, lang: String): String =
            ((o["i18n"] as? JsonObject)?.get(lang) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: o.s(key)
        private fun cond(e: JsonElement?): Map<String, List<String>> = (e as? JsonObject)?.mapValues { (_, v) -> strs(v) } ?: emptyMap()
        private fun condOpt(e: JsonElement?): Map<String, String> = (e as? JsonObject)?.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull.orEmpty() } ?: emptyMap()

        fun control(o: JsonObject, lang: String, reasons: Map<String, String> = emptyMap()): SheetControl {
            val reasonKey = o.s("reason")
            return SheetControl(
                id = o.s("id"), kind = o.s("kind", "action"), label = tr(o, "label", lang), action = o.s("action"), group = o.s("group"), value = o.s("value"),
                flag = o.s("flag"), preset = o.s("preset"), source = o.s("source"), plan = o.s("plan"), hint = o.s("hint"), reason = reasons[reasonKey] ?: reasonKey,
                labelFrom = o.s("label_from"), needsClone = o.b("needs_clone"), danger = o.b("danger"), confirm = o.b("confirm"), caution = o.b("caution"),
                disabled = o.b("disabled"), locked = o.b("locked"), legacy = strs(o["legacy"]),
                values = (o["values"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull } ?: emptyList(), note = o.s("note_min"),
                inapplicableWhen = cond(o["inapplicable_when"]), hosting = o.s("hosting"), writesRemote = o.b("writes_remote"),
            )
        }

        private fun group(o: JsonObject, lang: String, reasons: Map<String, String>): SheetGroup = SheetGroup(
            id = o.s("id"), label = tr(o, "label", lang), layout = o.s("layout", "chips"), controls = objs(o["controls"]).map { control(it, lang, reasons) },
            option = o.s("option"), danger = o.b("danger"),
            input = (o["input"] as? JsonObject)?.let { SheetInput(it.s("id"), it.s("key"), it.s("label"), it.s("default"), condOpt(it["shown_when_option"])) },
            chips = (o["chips"] as? JsonObject)?.let { c -> SheetChips(c.s("key"), (c["values"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull } ?: emptyList(), c.b("custom"), condOpt(c["shown_when_option"])) },
            sameAs = o.s("same_as"), shownWhen = cond(o["shown_when"]), hiddenWhen = cond(o["hidden_when"]),
        )

        private fun tabs(e: JsonElement?, lang: String, reasons: Map<String, String>): List<SheetTab> =
            objs(e).map { t -> SheetTab(t.s("id"), tr(t, "label", lang), objs(t["groups"]).map { group(it, lang, reasons) }) }

        private fun preset(o: JsonObject, lang: String) = SheetPreset(o.s("id"), tr(o, "label", lang), (o["options"] as? JsonObject)?.mapNotNull { (k, v) ->
            (v as? JsonPrimitive)?.let { p -> k to (p.booleanOrNull ?: p.intOrNull ?: p.contentOrNull ?: "") }
        }?.toMap().orEmpty())

        /** Parses the declaration; [lang] is the device language ("es"), the label is the fallback. */
        fun parse(o: JsonObject, lang: String): GitSheet {
            val reasons = (o["reasons"] as? JsonObject)?.mapValues { (it.value as? JsonPrimitive)?.contentOrNull.orEmpty() }.orEmpty()
                .let { base -> (((o["reasons_i18n"] as? JsonObject)?.get(lang)) as? JsonObject)?.let { l -> base + l.mapValues { (it.value as? JsonPrimitive)?.contentOrNull.orEmpty() } } ?: base }
            val h = o["header"] as? JsonObject ?: JsonObject(emptyMap())
            fun one(k: String) = (h[k] as? JsonObject)?.let { control(it, lang, reasons) }
            val d = o["defaults"] as? JsonObject
            val sd = d?.get("sync") as? JsonObject; val cd = d?.get("clone") as? JsonObject
            return GitSheet(
                header = SheetHeader(objs(h["actions"]).map { control(it, lang, reasons) }, one("refresh"), one("close"), objs(h["fields"]).map { control(it, lang, reasons) }, one("banner"), one("clone_now")),
                tabs = tabs(o["tabs"], lang, reasons), bulkTabs = tabs((o["bulk"] as? JsonObject)?.get("tabs"), lang, reasons),
                syncPresets = objs(o["sync_presets"]).map { preset(it, lang) }, clonePresets = objs(o["clone_presets"]).map { preset(it, lang) }, reasons = reasons,
                syncDefaults = SyncOptions(sd?.s("direction", "both") ?: "both", sd?.s("strategy", "ff") ?: "ff", sd?.s("winner", "none") ?: "none", sd?.s("dirty", "none") ?: "none",
                    sd?.s("commit_message", "chore: auto-sync") ?: "chore: auto-sync", sd?.s("scope", "current") ?: "current"),
                cloneDefaults = CloneOptions(cd?.s("dest", "store") ?: "store", cd?.s("workspace", "working") ?: "working", cd?.s("branches", "all") ?: "all", cd?.s("branch", "main") ?: "main",
                    cd?.s("history", "deep") ?: "deep", (cd?.get("depth") as? JsonPrimitive)?.intOrNull ?: 3),
            )
        }
    }
}

/** Applying a preset to the options (pure, tested). Unknown keys are ignored; a preset only sets the keys it names. */
object SheetPresets {
    fun sync(base: SyncOptions, p: SheetPreset): SyncOptions {
        var o = base
        p.options.forEach { (k, v) ->
            o = when (k) {
                "direction" -> o.copy(direction = v.toString()); "strategy" -> o.copy(strategy = v.toString()); "winner" -> o.copy(winner = v.toString())
                "dirty" -> o.copy(dirty = v.toString()); "scope" -> o.copy(scope = v.toString())
                "prune" -> o.copy(prune = v == true); "tags" -> o.copy(tags = v == true); "submodules" -> o.copy(submodules = v == true)
                "forceLease" -> o.copy(forceLease = v == true); "forceRaw" -> o.copy(forceRaw = v == true); "dryRun" -> o.copy(dryRun = v == true)
                else -> o
            }
        }
        return o
    }

    /** A clone preset starts from the full default (Working, All branches, Full history, everything included) and sets what it names. */
    fun clone(base: CloneOptions, p: SheetPreset): CloneOptions {
        var o = CloneOptions(dest = base.dest, branch = base.branch)
        p.options.forEach { (k, v) ->
            o = when (k) {
                "workspace" -> o.copy(workspace = v.toString()); "branches" -> o.copy(branches = v.toString()); "history" -> o.copy(history = v.toString())
                "depth" -> o.copy(depth = (v as? Int) ?: o.depth); "trees" -> o.copy(trees = v == true); "blobs" -> o.copy(blobs = v == true)
                else -> o
            }
        }
        return o
    }

    /** A preset is "on" when applying it to fresh defaults gives the options the sheet holds now. */
    fun activeSync(o: SyncOptions, p: SheetPreset): Boolean = sync(SyncOptions(commitMessage = o.commitMessage), p).normalized() == o.normalized()
    fun activeClone(o: CloneOptions, p: SheetPreset): Boolean = clone(o, p) == o
}
