package com.diegonmarcos.superapp.bottomnav

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.ComponentActivity
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * #1842 THE Url-Uri dropdown of every app's top bar: the far-left control of the first row, automatic (an app that declares
 * nothing still has it), listing with icons
 *   A) Pages  the parent (bottom-nav) pages of the app, AI, the app's own pages slot, Settings > Profile, Settings > Configs;
 *   B) Tools  back, up, home; send the app to the background, force close, restart; the app's own tools slot.
 * The two slots are DECLARED (`build.json::ui.url_uri = {"pages": [{id,label,icon,page}], "tools": [{id,label,icon}]}`, baked as
 * `UI_URL_URI_B64`): an app fills them from its build.json and draws nothing itself. Pure here ([urlUriModel]); the first row in
 * libs:ui-nav-bars draws it and routes a tap ([UrlUriSystem] for the platform actions).
 */
public object UrlUriIds {
    public const val AI: String = "ai"
    public const val PROFILE: String = "profile"
    public const val CONFIGS: String = "configs"
    public const val BACK: String = "back"
    public const val UP: String = "up"
    public const val HOME: String = "home"
    public const val BACKGROUND: String = "background"
    public const val FORCE_CLOSE: String = "force_close"
    public const val RESTART: String = "restart"
    public const val SECTION_PREFIX: String = "section:"
    public const val PAGE_SLOT_PREFIX: String = "slot_page:"
    public const val TOOL_SLOT_PREFIX: String = "slot_tool:"
    public fun section(id: String): String = SECTION_PREFIX + id
    public fun pageSlot(id: String): String = PAGE_SLOT_PREFIX + id
    public fun toolSlot(id: String): String = TOOL_SLOT_PREFIX + id

    /** The fixed Pages entries after the bottom-nav pages, in order (the app's slot sits between AI and Profile). */
    public val FIXED_PAGES: List<String> = listOf(AI, PROFILE, CONFIGS)

    /** The fixed Tools, in order (the app's slot follows them). */
    public val FIXED_TOOLS: List<String> = listOf(BACK, UP, HOME, BACKGROUND, FORCE_CLOSE, RESTART)
}

/** One entry an app declares in a slot. [page] = the section id (or "section/page") a pages-slot entry opens; a tools-slot entry is handed to the host as [id]. */
public data class UrlUriItem(val id: String, val label: String, val icon: String, val page: String = "")

/** `build.json::ui.url_uri`: the two app-specific slots. Empty (the default) is valid: the dropdown is still there. */
public data class UrlUriDecl(val pages: List<UrlUriItem> = emptyList(), val tools: List<UrlUriItem> = emptyList()) {
    /** What is wrong with this declaration (empty = valid): a slot entry needs an id, a label and an icon, ids are unique, a pages entry names its page. */
    public fun problems(): List<String> = buildList {
        for ((slot, items) in listOf("pages" to pages, "tools" to tools)) {
            items.forEach { if (it.id.isBlank() || it.label.isBlank() || it.icon.isBlank()) add("ui.url_uri.$slot entry '${it.id}' needs id, label and icon") }
            if (items.map { it.id }.toSet().size != items.size) add("ui.url_uri.$slot repeats an id")
        }
        pages.filter { it.page.isBlank() }.forEach { add("ui.url_uri.pages entry '${it.id}' names no page") }
    }

    public companion object {
        private fun items(o: Any?): List<UrlUriItem> = (o as? List<*>)?.mapNotNull { e ->
            val m = e as? Map<*, *> ?: return@mapNotNull null
            UrlUriItem(m["id"] as? String ?: "", m["label"] as? String ?: "", m["icon"] as? String ?: "", m["page"] as? String ?: "")
        } ?: emptyList()

        /** From the object's JSON text; fail-soft (a malformed blob is the empty declaration, never a crash on launch). */
        public fun parse(json: String): UrlUriDecl = runCatching {
            val o = MiniJson.parse(json) as? Map<*, *> ?: return@runCatching UrlUriDecl()
            UrlUriDecl(items(o["pages"]), items(o["tools"]))
        }.getOrDefault(UrlUriDecl())

        /** From BuildConfig.UI_URL_URI_B64; blank or malformed is the empty declaration. */
        @OptIn(ExperimentalEncodingApi::class)
        public fun fromBuildConfig(b64: String): UrlUriDecl = runCatching {
            if (b64.isBlank()) return@runCatching UrlUriDecl()
            parse(Base64.Default.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL).decode(b64.filterNot { it.isWhitespace() }).decodeToString())
        }.getOrDefault(UrlUriDecl())
    }
}

/** One row of the dropdown. [icon] is a libs:ui-icons name. */
public data class UrlUriEntry(val id: String, val label: String, val icon: String, val selected: Boolean = false, val enabled: Boolean = true)

/** The two groups, as drawn: Pages above Tools. */
public data class UrlUriModel(val pages: List<UrlUriEntry>, val tools: List<UrlUriEntry>)

/**
 * The model, pure. [sections] are the app's bottom-nav parent pages in order ([currentSectionId] is marked); [canUp] says whether the
 * page shown has a parent to go up to (otherwise Up is disabled, not dropped).
 */
public fun urlUriModel(sections: List<NavSection>, currentSectionId: String?, decl: UrlUriDecl, canUp: Boolean): UrlUriModel {
    val pages = buildList {
        sections.forEach { add(UrlUriEntry(UrlUriIds.section(it.id), it.label, it.icon, selected = it.id == currentSectionId)) }
        add(UrlUriEntry(UrlUriIds.AI, "AI", "ai"))
        decl.pages.forEach { add(UrlUriEntry(UrlUriIds.pageSlot(it.id), it.label, it.icon)) }
        add(UrlUriEntry(UrlUriIds.PROFILE, "Settings / Profile", "profile"))
        add(UrlUriEntry(UrlUriIds.CONFIGS, "Settings / Configs", "configs"))
    }
    val tools = buildList {
        add(UrlUriEntry(UrlUriIds.BACK, "Back", "arrow_back"))
        add(UrlUriEntry(UrlUriIds.UP, "Up", "arrow_upward", enabled = canUp))
        add(UrlUriEntry(UrlUriIds.HOME, "Home", "home"))
        add(UrlUriEntry(UrlUriIds.BACKGROUND, "Send app to background", "minimize"))
        add(UrlUriEntry(UrlUriIds.FORCE_CLOSE, "Force close", "power_settings_new"))
        add(UrlUriEntry(UrlUriIds.RESTART, "Restart app", "restart_alt"))
        decl.tools.forEach { add(UrlUriEntry(UrlUriIds.toolSlot(it.id), it.label, it.icon)) }
    }
    return UrlUriModel(pages, tools)
}

/** The platform actions of the Tools group. */
public object UrlUriSystem {
    private fun activity(ctx: Context): Activity? {
        var c: Context? = ctx
        while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
        return null
    }

    /** What the system Back does now (the host's BackHandlers run first). */
    public fun back(ctx: Context) {
        val a = activity(ctx) ?: return
        if (a is ComponentActivity) a.onBackPressedDispatcher.onBackPressed() else a.finish()
    }

    /** Send the app to the background (the task stays, the launcher shows). */
    public fun toBackground(ctx: Context) { activity(ctx)?.moveTaskToBack(true) }

    /** Remove the task and end the process: the next launch is cold. */
    public fun forceClose(ctx: Context) {
        activity(ctx)?.finishAndRemoveTask()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    /** Close the process and launch the app again (an alarm a moment later: the new task starts after this process is gone). */
    public fun restart(ctx: Context) {
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) ?: return
        val pi = PendingIntent.getActivity(ctx, RESTART_REQUEST, launch, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        (ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)?.set(AlarmManager.RTC, System.currentTimeMillis() + RESTART_DELAY_MS, pi)
        forceClose(ctx)
    }

    private const val RESTART_REQUEST = 1842
    private const val RESTART_DELAY_MS = 400L
}

/**
 * What the Url-Uri dropdown reads of the HOST app's build.json without the app passing anything (so every app has it by default):
 * the app's own generated BuildConfig, found by name. A field the app does not bake answers blank / empty, never a crash.
 */
public object UrlUriBuildConfig {
    private fun buildConfig(ctx: Context): Class<*>? {
        val names = LinkedHashSet<String>()
        names += ctx.packageName.removeSuffix(".debug")
        var pkg = (ctx as? Activity ?: generateSequence(ctx) { (it as? ContextWrapper)?.baseContext }.firstOrNull { it is Activity })?.javaClass?.name?.substringBeforeLast('.', "") ?: ""
        while (pkg.isNotEmpty()) { names += pkg; pkg = pkg.substringBeforeLast('.', "") }
        return names.firstNotNullOfOrNull { runCatching { Class.forName("$it.BuildConfig") }.getOrNull() }
    }

    /** The string constant [name] of the host's BuildConfig; blank when absent. */
    public fun field(ctx: Context, name: String): String =
        runCatching { buildConfig(ctx)?.getField(name)?.get(null) as? String }.getOrNull().orEmpty()

    /** `build.json::ui.url_uri`, baked as UI_URL_URI_B64. */
    public fun decl(ctx: Context): UrlUriDecl = UrlUriDecl.fromBuildConfig(field(ctx, "UI_URL_URI_B64"))

    /** `build.json::ui.default_section`, what Home opens. */
    public fun defaultSection(ctx: Context): String = field(ctx, "UI_DEFAULT_SECTION")

    /** The bottom-nav parent pages (`ui.bottom_nav` over `ui.sections`); empty when the app bakes none. */
    public fun bottomPages(ctx: Context): List<NavSection> =
        runCatching { NavDecl.fromBuildConfig(field(ctx, "UI_SECTIONS_B64"), field(ctx, "UI_BOTTOM_NAV"), field(ctx, "UI_DEFAULT_SECTION")).bottomSections() }.getOrDefault(emptyList())
}
