package com.diegonmarcos.superapp.devcontrol

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The data half of Configs ▸ About (DevControlFragment), Compose since #773. The page is ~30 service blocks in eight macro
 * groups; a block is a [AboutSection] whose body declares [AboutItem]s into an [AboutItems] builder when — and only when —
 * it is reached (#286: the old page paid for all thirty before its first frame). Everything the page prints also lands in
 * the section's copy buffer, which is what "Copy All Infos" assembles, in DECLARED order.
 */
internal sealed interface AboutItem {
    /** A key / value line. [text] and [color] are state so a probe can fill a "reading…" row in later. */
    class Row(val key: String, initial: String) : AboutItem {
        var text by mutableStateOf(initial)
        var color by mutableStateOf<Color?>(null)
    }

    class Small(val text: String) : AboutItem

    class Button(val label: String, val gray: Boolean, val onClick: () -> Unit) : AboutItem

    /** A switch whose state it owns; [onChange] persists it. */
    class Toggle(val label: String, checked: Boolean, val onChange: (Boolean) -> Unit) : AboutItem {
        var on by mutableStateOf(checked)
    }

    class Custom(val content: @Composable () -> Unit) : AboutItem
}

/** What a section body writes into. */
internal class AboutItems(private val section: AboutSection, val scope: CoroutineScope) {
    /** The section's copy buffer: every row lands here as it is declared. */
    val buf: StringBuilder get() = section.buf

    fun row(key: String, value: String): AboutItem.Row {
        buf.append("  ").append(key).append(": ").append(value).append("\n")
        return AboutItem.Row(key, value).also { section.items += it }
    }

    fun small(text: String): AboutItem.Small = AboutItem.Small(text).also { section.items += it }

    fun button(label: String, gray: Boolean = false, onClick: () -> Unit) {
        section.items += AboutItem.Button(label, gray, onClick)
    }

    fun toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        section.items += AboutItem.Toggle(label, checked, onChange)
    }

    fun custom(content: @Composable () -> Unit) {
        section.items += AboutItem.Custom(content)
    }

    fun remove(item: AboutItem) { section.items.remove(item) }

    /** Run this block's body again from scratch — a switch moved, a server restarted, "Refresh" tapped — so its rows say what
     *  is true now. Scoped to the block: the page keeps its scroll position and every other block keeps what it read. */
    fun reload() { section.reset(); section.load(scope) }

    /** Work that outlives the body (a probe filling a row in): owned by the page's composition scope, so teardown cancels it. */
    fun launch(block: suspend CoroutineScope.() -> Unit): Job = scope.launch(block = block)
}

/** One service block: its heading, its macro group (the Index card that owns it) and its lazily-run body. */
internal class AboutSection(val macro: String, val head: String, private val body: AboutItems.() -> Unit) {
    val items = mutableStateListOf<AboutItem>()
    val buf: StringBuilder = StringBuilder("\n## ").append(head).append("\n")

    /** True once the body has run — exactly once per view lifecycle, so scrolling back and forth never re-probes. */
    var started by mutableStateOf(false)
        private set

    /**
     * Run the body, and turn a throw into a VISIBLE failure inside this block. #281: a status surface that reported READY
     * when nothing worked is why this does not swallow — an empty section and a working-but-boring section must not look
     * alike. #331: this runs on the main thread from a composition effect, so anything escaping would take the process down.
     */
    fun reset() {
        items.clear()
        buf.setLength(0)
        buf.append("\n## ").append(head).append("\n")
        started = false
    }

    fun load(scope: CoroutineScope) {
        if (started) return
        started = true
        try {
            body(AboutItems(this, scope))
        } catch (t: Throwable) {
            val why = "${t.javaClass.simpleName}: ${t.message ?: "no detail"}"
            runCatching {
                items.clear()
                items += AboutItem.Small("⚠ $head failed to load — $why")
            }
            buf.append("  FAILED: ").append(why).append("\n")
        }
    }
}

/** The page, top to bottom, after the title and the Index. */
internal sealed interface AboutBlock {
    /** A big group divider ("📱  APP & BUILD"); [label] is the text without its leading glyph, [glyph] the glyph. */
    class Macro(val label: String, val glyph: String) : AboutBlock
    class Section(val section: AboutSection) : AboutBlock
    /** "↑ Go Back Up to Index", closing every macro group. */
    object BackToIndex : AboutBlock
    object CopyAll : AboutBlock
}

/** Collects the page's blocks as the fragment declares them. */
internal class AboutPage(private val attached: () -> Boolean, private val only: Set<String>? = null) {
    val blocks = mutableListOf<AboutBlock>()
    val sections: List<AboutSection> get() = blocks.filterIsInstance<AboutBlock.Section>().map { it.section }
    val macros: List<AboutBlock.Macro> get() = blocks.filterIsInstance<AboutBlock.Macro>()

    private var currentMacro = ""

    fun macro(text: String) {
        // Label without the leading emoji + spacing: "🔍  LOGCAT" -> "LOGCAT".
        val label = text.dropWhile { !it.isLetter() }.trim()
        val glyph = text.takeWhile { !it.isLetter() }.trim()
        currentMacro = label
        if (only == null || label in only) blocks += AboutBlock.Macro(label, glyph)
    }

    /** A section under a macro group this page does not draw ([only], Configs ▸ About) is not registered at all. */
    fun infoSection(head: String, body: AboutItems.() -> Unit) {
        if (only != null && currentMacro !in only) return
        blocks += AboutBlock.Section(AboutSection(currentMacro, head, body))
    }

    /**
     * A section whose data costs real I/O — a socket connect, a recursive directory walk, an exec, a 7-day usage query.
     * [section] alone only defers such a body; it would still block the main thread at the moment the user scrolled to it.
     * This runs [probe] on [kotlinx.coroutines.Dispatchers.IO] under a timeout and renders from the result.
     *
     *  - one slow service cannot stall the others: each probe is its own coroutine and the page awaits none of them;
     *  - a failure or a timeout is RENDERED IN THIS BLOCK — no permanent spinner, no silently empty section;
     *  - cancellation is structural: the scope is the page's composition scope, so every in-flight probe dies with the view
     *    (the #194 class: a fragment painting after detach).
     */
    fun <T> asyncSection(
        head: String,
        timeoutMs: Long = 4_000,
        probe: suspend () -> T,
        render: AboutItems.(T) -> Unit,
    ) = infoSection(head) {
        val loading = small("reading…")
        launch {
            val outcome = runCatching {
                kotlinx.coroutines.withTimeout(timeoutMs) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { probe() }
                }
            }
            // The scope is already cancelled when the view goes, but a fragment can be detached between the resume and
            // this line, and painting then is exactly #194.
            if (!attached()) return@launch
            remove(loading)
            outcome
                .onSuccess { render(it) }
                .onFailure { e ->
                    val why =
                        if (e is kotlinx.coroutines.TimeoutCancellationException) "timed out after $timeoutMs ms"
                        else "${e.javaClass.simpleName}: ${e.message ?: "no detail"}"
                    small("⚠ $head could not be read — $why")
                    buf.append("  UNAVAILABLE: $why\n")
                }
        }
    }

    fun copyAllButton() { blocks += AboutBlock.CopyAll }

    /** Every macro group ends with the way back up: a long page is navigable both ways. Derived from the macros, so a new
     *  macro header gets one for free and a removed one cannot leave a dead link behind. */
    fun withBackLinks(): List<AboutBlock> {
        val out = ArrayList<AboutBlock>()
        var seenMacro = false
        for (b in blocks) {
            if (b is AboutBlock.Macro) {
                if (seenMacro) out += AboutBlock.BackToIndex
                seenMacro = true
            }
            out += b
        }
        out += AboutBlock.BackToIndex
        return out
    }
}
