package com.diegonmarcos.superapp.appstore

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.updater.AutoUpdatePrefs
import kotlin.concurrent.thread

// ── the Store's ink, as Compose colours ─────────────────────────────────────
// One place for the literals the View builders used to repeat; the Store is dark by design, so these
// are not palette roles (the palette the pages sit in is built from the same values).

internal fun Int.ink(): Color = Color(this)

internal object Ink {
    val DIM = 0x99FFFFFF.toInt()
    val WHITE = 0xFFFFFFFF.toInt()
    val CARD = 0xFF1C1C24.toInt()
    val RULE = 0xFF2A2A33.toInt()
    val BLUE_FILL = 0xFF2B6CB0.toInt()
    val GREEN_FILL = 0xFF2F855A.toInt()
    val GREY_FILL = 0xFF4A4A55.toInt()
    val AMBER_FILL = 0xFFB7791F.toInt()
    val DISABLED = 0xFF3A3A44.toInt()
}

/** A Store spacing step (before SCALE) as Compose dp. */
internal fun sdp(step: Int): Dp = StoreDensity.dpValue(step).dp

/** Compose test hooks of the shared controls: which kind of Store control a node is, and what it measures. */
object StoreSemantics {
    /** [StoreBar.BUTTON] / [StoreBar.CHIP] / [StoreBar.PAGE] — what only the shared builders set. */
    val Control = SemanticsPropertyKey<String>("StoreControl")
    /** The control's measures (text size, padding, corner radius): two controls with one look read alike. */
    val Look = SemanticsPropertyKey<String>("StoreLook")
    /** The ARGB ink a label is painted in (a failure is red). */
    val Ink = SemanticsPropertyKey<Int>("StoreInk")
}

private var SemanticsPropertyReceiver.storeControl by StoreSemantics.Control
private var SemanticsPropertyReceiver.storeLook by StoreSemantics.Look
internal var SemanticsPropertyReceiver.storeInk by StoreSemantics.Ink

/** #732 the declared styles, read once per composition host. */
@Composable
internal fun rememberStoreControls(): StoreControls.Decl {
    val ctx = LocalContext.current
    return remember(ctx) { StoreControls.load(ctx) }
}

/** A plain tap target, without the ripple the dense store rows never use. */
internal fun Modifier.tap(onClick: (() -> Unit)?): Modifier =
    if (onClick == null) this else this.clickable(interactionSource = null, indication = null, onClick = onClick)

internal fun Modifier.tagged(tag: String?): Modifier = if (tag == null) this else this.testTag(tag)

/** The one background builder for all three styles (what StoreControls.background drew as a GradientDrawable). */
internal fun Modifier.storeSurface(style: StoreControls.Style, active: Boolean, fillOverride: Int? = null): Modifier {
    val shape = RoundedCornerShape((style.radiusDp * StoreDensity.SCALE).dp)
    val fill = fillOverride ?: (if (active) style.fillActive else style.fill) ?: 0
    val stroke = if (active) style.strokeActive ?: style.stroke else style.stroke
    val clipped = this.clip(shape).background(Color(fill))
    return if (stroke != null) clipped.border(StoreDensity.STROKE.dp, Color(stroke), shape) else clipped
}

private fun weightOf(style: StoreControls.Style) = if (style.bold) FontWeight.Bold else FontWeight.Normal

/** A page caption: meta size, dim, with room below. */
@Composable
internal fun StoreCaption(text: String, modifier: Modifier = Modifier, color: Int = Ink.DIM, tag: String? = null) {
    Text(text, color = color.ink(), fontSize = StoreDensity.T_META.sp,
        modifier = modifier.tagged(tag).padding(bottom = sdp(StoreDensity.S8)))
}

/** A heading over a run of rows. */
@Composable
internal fun StoreHeading(text: String, color: Int, modifier: Modifier = Modifier, tag: String? = null) {
    Text(text, color = color.ink(), fontSize = StoreDensity.T_META.sp,
        modifier = modifier.tagged(tag).padding(top = sdp(StoreDensity.S8), bottom = sdp(StoreDensity.S4)))
}

/**
 * THE Store top bar (#565) — one declaration, drawn by both Store tabs. It was
 * StoreCloudFragment.renderHeader; Phone Apps needed the same bar, and a second
 * copy is how two bars drift (#228).
 *
 * Only three verbs belong to the page: Check all, Install all, Update all. The
 * page passes each as a lambda, or null when it cannot do it — Phone Apps has no
 * privileged channel to install or update a package it does not own (#225), so
 * it passes null and the bar draws that button disabled with [Verbs.disabledReason]
 * under it. Every other control (auto-update, Wi-Fi-only, Play Protect, Wireless
 * Debugging, Developer options) is device state, identical on both tabs.
 *
 * Each control is tagged with its [Item] ([itemTag]), so a test can read the rendered bar
 * rather than this source.
 */
object StoreBar {

    const val TAG = "store-bar"

    enum class Item { CHECK, INSTALL, DOWNLOAD, UPDATE, AUTO_UPDATE, WIFI_ONLY, PLAY_PROTECT, WIRELESS_DEBUG }

    /** The test tag of a bar control. */
    fun itemTag(item: Item) = "$TAG:${item.name}"

    class Verbs(
        val checkAll: () -> Unit,
        val installAll: (() -> Unit)?,
        val updateAll: (() -> Unit)?,
        /** Why a null verb is null. Drawn under the bar whenever one is. */
        val disabledReason: Int = 0,
        /** #784 pre-fetch every update into the cache, install nothing. Only the
         *  Cloud tab owns a fleet cache to fill, so null here draws NO button
         *  (rather than a disabled one with nothing to explain). */
        val downloadAll: (() -> Unit)? = null,
    )

    private val MAIN = Handler(Looper.getMainLooper())
    private val AMBER = 0xFFB7791F.toInt()

    /**
     * The bar. Exactly two rows of controls: the batch actions and the auto-update switch on the first,
     * the configs and the remaining switches on the second. Check all refreshes what is on offer,
     * changing nothing; Install all takes only what is not on the device; Update all is every group in
     * one pass, never tab-scoped. A toggle redraws it from the device's new state; [resume] (a counter
     * the host bumps when it comes back to the front) drops the cached readings and re-reads them.
     */
    @Composable
    fun Bar(verbs: Verbs, modifier: Modifier = Modifier, resume: Int = 0) {
        val ctx = LocalContext.current
        var tick by remember { mutableIntStateOf(0) }
        var chan by remember { mutableIntStateOf(0) }
        LaunchedEffect(resume) { if (resume > 0) { StoreStatus.invalidate(); tick++; chan++ } }
        val autoOn = remember(tick) { AutoUpdatePrefs.enabled(ctx) }
        val wifiOnly = remember(tick) { AutoUpdatePrefs.requireUnmetered(ctx) }
        val silent = remember(tick) { AutoUpdatePrefs.canInstallSilently(ctx) }
        val pp = remember(tick) { StoreStatus.playProtect(ctx) }
        val ch = remember(chan, tick) { StoreStatus.channel(ctx) }

        Column(modifier.testTag(TAG)) {
            Row {
                Button(ctx.getString(R.string.store_bar_check_all), Ink.BLUE_FILL, verbs.checkAll,
                    Modifier.weight(1f), itemTag(Item.CHECK))
                Button(ctx.getString(R.string.store_bar_install_all), Ink.BLUE_FILL, verbs.installAll,
                    Modifier.weight(1f), itemTag(Item.INSTALL))
                verbs.downloadAll?.let {
                    Button(ctx.getString(R.string.store_bar_download_all), Ink.BLUE_FILL, it,
                        Modifier.weight(1f), itemTag(Item.DOWNLOAD))
                }
                Button(ctx.getString(R.string.store_bar_update_all), Ink.BLUE_FILL, verbs.updateAll,
                    Modifier.weight(1f), itemTag(Item.UPDATE))
                Button(ctx.getString(R.string.store_bar_auto_update, onOff(ctx, autoOn)),
                    if (autoOn) Ink.GREEN_FILL else Ink.GREY_FILL,
                    {
                        AutoUpdatePrefs.setEnabled(ctx, !autoOn)
                        // Reconcile the periodic workers immediately: start() schedules
                        // when enabled, cancels when disabled (both re-check the pref).
                        com.diegonmarcos.superapp.updater.Updater.start(ctx)
                        ConstellationWorker.start(ctx)
                        toast(ctx, ctx.getString(R.string.store_bar_auto_update_toast, onOff(ctx, !autoOn)))
                        tick++
                    }, Modifier.weight(1f), itemTag(Item.AUTO_UPDATE))
            }
            Row {
                // Only gates the UNATTENDED passes: the buttons above are the user
                // asking, so they download on any network regardless of this.
                Button(ctx.getString(R.string.store_bar_wifi_only, onOff(ctx, wifiOnly)),
                    if (wifiOnly) Ink.GREEN_FILL else Ink.GREY_FILL,
                    {
                        AutoUpdatePrefs.setRequireUnmetered(ctx, !wifiOnly)
                        toast(ctx, ctx.getString(R.string.store_bar_wifi_only_toast, onOff(ctx, !wifiOnly)))
                        tick++
                    }, Modifier.weight(1f), itemTag(Item.WIFI_ONLY))
                // Play Protect's per-INSTALL scan prompt. The label is what the device STORES
                // (ON / OFF / unknown when no key can be read), never a default. Tap toggles
                // through the shell channel (or WRITE_SECURE_SETTINGS); when that is absent or
                // refused it opens Play Protect's own settings. Re-read on resume.
                val ppLabel = when (pp) {
                    ControlStatus.Tri.ON -> onOff(ctx, true)
                    ControlStatus.Tri.OFF -> onOff(ctx, false)
                    ControlStatus.Tri.UNKNOWN -> ctx.getString(R.string.store_unknown_state)
                }
                Button(ctx.getString(R.string.store_bar_play_protect, ppLabel),
                    when (pp) { ControlStatus.Tri.ON -> Ink.GREY_FILL; ControlStatus.Tri.OFF -> Ink.GREEN_FILL; else -> AMBER },
                    {
                        if (pp == ControlStatus.Tri.UNKNOWN) openPlayProtect(ctx)
                        else {
                            // setScanning binds Shizuku, which blocks - never on the main thread.
                            thread(name = "play-protect-toggle") {
                                val r = StorePlayProtect.setScanning(ctx, pp == ControlStatus.Tri.OFF)
                                MAIN.post {
                                    if (r.ok) Toast.makeText(ctx, r.state.describe() + " - via " + r.channel, Toast.LENGTH_LONG).show()
                                    else {
                                        Toast.makeText(ctx, ctx.getString(R.string.store_bar_play_protect_settings, r.output.lineSequence().firstOrNull().orEmpty()), Toast.LENGTH_LONG).show()
                                        openPlayProtect(ctx)
                                    }
                                    StoreStatus.invalidate(); tick++; chan++
                                }
                            }
                        }
                    }, Modifier.weight(1f), itemTag(Item.PLAY_PROTECT))
                // The privileged channel itself: a cached live `id` round trip (uid 2000) over the
                // embedded adb or Shizuku, probed off the main thread. NOT the system "wireless
                // debugging" switch - that can be on while the channel is dead.
                ChannelButton(Modifier.weight(1f), onChange = { chan++ })
            }
            if (verbs.disabledReason != 0 && (verbs.installAll == null || verbs.updateAll == null))
                StoreCaption(ctx.getString(verbs.disabledReason))
            StoreCaption(ctx.getString(if (silent) R.string.store_bar_silent_on else R.string.store_bar_silent_off))
            StoreCaption(ctx.getString(when (pp) {
                ControlStatus.Tri.UNKNOWN -> R.string.store_bar_play_protect_unknown
                ControlStatus.Tri.OFF -> R.string.store_bar_play_protect_off
                ControlStatus.Tri.ON -> R.string.store_bar_play_protect_on }))
            StoreCaption(ctx.getString(when (ch?.state) {
                ControlStatus.Channel.UP -> R.string.store_bar_channel_up_caption
                ControlStatus.Channel.DOWN -> R.string.store_bar_channel_down_caption
                ControlStatus.Channel.NOT_PAIRED -> R.string.store_bar_channel_unpaired_caption
                null -> R.string.store_bar_channel_checking }))
        }
    }

    /**
     * THE Wireless Debugging chip (#918): the privileged channel's live status as its label, and ONE tap
     * target - the ADB Shell page (libs:shizuku-adb-debug-tools AdbShellPage), where pairing, connecting,
     * the local server, the logs and the setup checklist live. It carries no channel control of its own:
     * test-adb-shell-one-place.sh fails if this builder ever starts one again. Drawn by this bar and by
     * Access > Android Perms. [onChange] tells whoever hosts it that the status changed (repaint).
     */
    @Composable
    fun ChannelButton(modifier: Modifier = Modifier, onChange: () -> Unit = {}) {
        val ctx = LocalContext.current
        var tick by remember { mutableIntStateOf(0) }
        val ch = remember(tick) { StoreStatus.channel(ctx) }
        // #1556 Re-probe only when the floor allows it (AdbRecheck, 30 s). The probe settles the chip to a definite state and its
        // completion bumps `tick`; without the floor that completion (or a flapping adb setting / a probe that never settled)
        // re-fired the probe forever — the owner's "keeps checking ADB". A settled state is simply shown; it is not re-probed.
        LaunchedEffect(tick) {
            if (StoreStatus.dueForRecheck()) StoreStatus.refresh(ctx) { MAIN.post { tick++; onChange() } }
        }
        // #1442 Re-verify the live link on resume — subject to the same 30 s floor (no cache clear, so no "checking" flash).
        val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        androidx.compose.runtime.DisposableEffect(owner) {
            val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
                if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) tick++ }
            owner.lifecycle.addObserver(obs)
            onDispose { owner.lifecycle.removeObserver(obs) }
        }
        // #1550 no polling: re-check when Wireless debugging / adb is switched (Settings.Global observer); #1556 floor-gated, and
        // it asks for a re-check (tick++) rather than clearing the cache, so a flapping setting cannot drive a probe loop.
        androidx.compose.runtime.DisposableEffect(ctx) {
            val stop = com.diegonmarcos.superapp.shellclient.AdbWatch.observe(ctx) { tick++ }
            onDispose { stop() }
        }
        val chLabel = ch?.let { ControlStatus.label(it) } ?: ctx.getString(R.string.store_bar_channel_checking)
        Button(chLabel,
            when (ControlStatus.ink(ch)) { ControlStatus.Ink.GREEN -> Ink.GREEN_FILL; ControlStatus.Ink.AMBER -> AMBER
                ControlStatus.Ink.RED -> RED_FILL; ControlStatus.Ink.GREY -> Ink.GREY_FILL },
            { StoreShell.arm(ctx) }, modifier, itemTag(Item.WIRELESS_DEBUG))
    }

    private const val RED_FILL = 0xFFC53030.toInt()

    private fun openPlayProtect(ctx: Context) {
        val pm = ctx.packageManager
        val ladder = listOf(
            Intent().setClassName("com.google.android.gms", "com.google.android.gms.security.settings.VerifyAppsSettingsActivity"),
            Intent(Settings.ACTION_SECURITY_SETTINGS), Intent(Settings.ACTION_SETTINGS))
        for (i in ladder) if (i.resolveActivity(pm) != null && runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }

    private fun onOff(ctx: Context, on: Boolean) = ctx.getString(if (on) R.string.store_on else R.string.store_off)
    private fun toast(ctx: Context, t: String) = Toast.makeText(ctx, t, Toast.LENGTH_SHORT).show()

    // ── #793 THE Store's action button and filter chip ────────────────────────
    // This bar's button is the Store's action button: every Store app row
    // (StoreCloudFragment's verbs), the Store's filter chips, and every Apps Mesh
    // tool, member row button and filter chip are drawn by these builders,
    // in the styles appstore-controls.json declares (`action`, `filter_style`).
    // Each node they draw carries [StoreSemantics.Control], so a test can tell
    // the shared component from a Text merely painted to look like it.

    const val BUTTON = "store-control:button"
    const val CHIP = "store-control:chip"
    const val PAGE = "store-control:page"

    /**
     * The action button: the `action` style in [fill], the verb's own colour. Weighted by whoever lays
     * it out, so a row of them shares its width. A null [onClick] is a verb this page cannot do: drawn,
     * dimmed, inert. [alpha] overrides the dimming (a stage that is not live is drawn at .35, still
     * in its verb's colour); [live] false is inert without losing the colour.
     */
    @Composable
    fun Button(
        label: String, fill: Int?, onClick: (() -> Unit)?, modifier: Modifier = Modifier, tag: String? = null,
        style: StoreControls.Style = rememberStoreControls().action, alpha: Float? = null, live: Boolean = true,
        description: String? = null,
    ) {
        val on = onClick != null && live
        val look = "${StoreDensity.T_META} p${StoreDensity.S8},${StoreDensity.S6} r${style.radiusDp}"
        Box(
            modifier
                .padding(start = sdp(StoreDensity.S2), top = sdp(StoreDensity.S4), end = sdp(StoreDensity.S2), bottom = sdp(StoreDensity.S2))
                .alpha(alpha ?: if (onClick != null) 1f else 0.45f)
                .storeSurface(style, false, if (onClick != null) fill else Ink.DISABLED)
                .tagged(tag)
                .semantics(mergeDescendants = true) {
                    storeControl = BUTTON; storeLook = "button $look"
                    role = Role.Button
                    if (description != null) contentDescription = description
                    if (!on) disabled()
                }
                .tap(if (on) onClick else null)
                .padding(horizontal = sdp(StoreDensity.S8), vertical = sdp(StoreDensity.S6)),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, color = style.text.ink(), fontSize = StoreDensity.T_META.sp, fontWeight = weightOf(style),
                textAlign = TextAlign.Center)
        }
    }

    /**
     * One filter chip of a row that partitions a list; painted on or off by [selected]. [first] has no
     * leading gap. Weighted by whoever lays it out.
     */
    @Composable
    fun Chip(
        label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, first: Boolean = false,
        tag: String? = null, style: StoreControls.Style = rememberStoreControls().filter,
    ) {
        val look = "${StoreDensity.T_CAPTION} p${StoreDensity.S6},${StoreDensity.S6} r${style.radiusDp}"
        Box(
            modifier
                .padding(start = if (first) 0.dp else sdp(StoreDensity.S4))
                .storeSurface(style, selected)
                .tagged(tag)
                .semantics(mergeDescendants = true) {
                    storeControl = CHIP; storeLook = "chip $look"
                    role = Role.Button
                }
                .tap(onClick)
                .padding(sdp(StoreDensity.S6)),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, color = (if (selected) style.textActive else style.text).ink(), fontSize = StoreDensity.T_CAPTION.sp,
                fontWeight = weightOf(style), textAlign = TextAlign.Center, maxLines = 1)
        }
    }

    /** #809 THE page button outside line 2: opens another page, in the `page_style` — icon, label,
     *  chevron — wrapping its caption. */
    @Composable
    fun PageButton(
        icon: String, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, tag: String? = null,
        style: StoreControls.Style = rememberStoreControls().page,
    ) {
        val look = "${StoreDensity.T_META} p${StoreDensity.S8},${StoreDensity.S6} r${style.radiusDp}"
        Box(
            modifier
                .padding(start = sdp(StoreDensity.S2), top = sdp(StoreDensity.S4), end = sdp(StoreDensity.S2), bottom = sdp(StoreDensity.S2))
                .storeSurface(style, false)
                .tagged(tag)
                .semantics(mergeDescendants = true) {
                    storeControl = PAGE; storeLook = "page $look"
                    role = Role.Button
                }
                .tap(onClick)
                .padding(horizontal = sdp(StoreDensity.S8), vertical = sdp(StoreDensity.S6)),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(listOf(icon, label, style.chevron).filter { it.isNotEmpty() }.joinToString("  "),
                color = style.text.ink(), fontSize = StoreDensity.T_META.sp, fontWeight = weightOf(style), maxLines = 1)
        }
    }
}

/** The quick verb on a collapsed row (⬆ / ⬇ / Install / Clear): its caption, its colour and its action. */
class StoreQuick(val label: String, val fill: Int, val onClick: () -> Unit)

/** One button of a row's expanded lines. [live] false is drawn dimmed and inert, in its own colour. */
class StoreVerb(
    val label: String, val fill: Int?, val onClick: (() -> Unit)?, val tag: String? = null,
    val live: Boolean = true, val alpha: Float? = null, val description: String? = null,
)

/**
 * THE Store row and its page parts - one builder for the Cloud page's fleet rows AND the Phone
 * page's app rows, so the two pages cannot drift into two looks again. Before this each page drew
 * its own card: Cloud a dense head (state dot, name, meta, quick verb, chevron) with its buttons as
 * full-width shared action buttons, Phone a taller card whose buttons sat in a sideways-scrolling
 * strip, half of them off-screen. Both now ask this object for:
 *  - [Card]     the row's background and spacing;
 *  - [Head]     dot · name · meta · quick verb · chevron; the chevron opens the detail (and says so);
 *  - [Actions]  the row's buttons, EXPANDED: weight-1 lines of at most [PER_LINE], never a scroller;
 *  - [Filters]  a line of the shared filter chips, painted for the selected one.
 * test-store-phone-sizes.sh holds that both fragments draw rows only through these.
 */
object StoreRow {
    const val PER_LINE = 4
    const val ACTIONS = "store-row:actions"

    @Composable
    fun Card(modifier: Modifier = Modifier, tag: String? = null, content: @Composable ColumnScope.() -> Unit) {
        Column(modifier.tagged(tag).padding(vertical = sdp(StoreDensity.S2)).background(Ink.CARD.ink()).fillMaxWidth(), content = content)
    }

    /**
     * THE Store row, whole: the card, the head (state dot left, name, meta right, the collapsed quick verb,
     * chevron), whatever [below] the head even when collapsed (an error area), and - only while [open] - the
     * [detail] lines with the row's buttons. Cloud (fleet apps) and Phone (installed / declared apps) both draw
     * every row through this one function; a page differs only in the values it passes and the extra buttons
     * and filters it adds.
     */
    @Composable
    fun Item(
        label: String, open: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier, tag: String? = null,
        dot: String = "·", dotColor: Int = Ink.DIM, meta: String = "", metaColor: Int = Ink.DIM,
        quick: StoreQuick? = null, headTag: String? = null, metaTag: String? = null, dotTag: String? = null,
        below: @Composable ColumnScope.() -> Unit = {},
        detail: @Composable ColumnScope.() -> Unit,
    ) {
        Card(modifier, tag) {
            Head(label, open, onToggle, dot = dot, dotColor = dotColor, meta = meta, metaColor = metaColor,
                quick = quick, headTag = headTag, metaTag = metaTag, dotTag = dotTag)
            below()
            if (open) Column(Modifier.padding(start = sdp(StoreDensity.S12), end = sdp(StoreDensity.S12), bottom = sdp(StoreDensity.S8)),
                content = detail)
        }
    }

    /** The one summary line over a list ("12 total  ·  2 updates"), the same on every Store list page. */
    @Composable
    fun Summary(text: String, tag: String = "store-summary") {
        Text(text, color = Ink.DIM.ink(), fontSize = StoreDensity.T_META.sp,
            modifier = Modifier.testTag(tag).padding(top = sdp(StoreDensity.S2), bottom = sdp(StoreDensity.S6)))
    }

    /**
     * The row's head. [open] / [onToggle] keep the page's memory of the chevron; [meta] is the one-line
     * status slot (state, or the size on the Phone page) and [quick] the verb beside it, if any.
     */
    @Composable
    fun Head(
        label: String, open: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier,
        dot: String = "·", dotColor: Int = Ink.DIM, meta: String = "", metaColor: Int = Ink.DIM,
        quick: StoreQuick? = null, headTag: String? = null, metaTag: String? = null, dotTag: String? = null,
        nameTag: String? = null,
    ) {
        // The head is ONE tap target (it merges its texts into the node [headTag] names); a test reads the
        // dot / name / meta by their own tags from the UNMERGED tree.
        com.diegonmarcos.superapp.uikit.KitStoreRowLine(modifier.tagged(headTag), onToggle) {
            com.diegonmarcos.superapp.uikit.KitStoreDot(dot, dotColor.ink(), Modifier.tagged(dotTag))
            com.diegonmarcos.superapp.uikit.KitStoreName(label, Modifier.weight(1f).tagged(nameTag), Ink.WHITE.ink())
            Text(meta, color = metaColor.ink(), fontSize = StoreDensity.T_CAPTION.sp, maxLines = 1,
                modifier = Modifier.tagged(metaTag))
            if (quick != null) Text(quick.label, color = Ink.WHITE.ink(), fontSize = StoreDensity.T_BODY.sp,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                modifier = Modifier.padding(start = sdp(StoreDensity.S8)).background(quick.fill.ink())
                    .tap(quick.onClick)
                    .padding(horizontal = sdp(StoreDensity.S12), vertical = sdp(StoreDensity.S4)))
            Text(if (open) "⌄" else "›", color = Ink.DIM.ink(), fontSize = StoreDensity.T_TITLE.sp,
                modifier = Modifier.padding(start = sdp(StoreDensity.S8)))
        }
    }

    /**
     * The row's buttons, all visible: lines of at most [perLine] buttons sharing the width. [dividers]
     * holds the verb indices (in the whole [verbs] list) to draw the owner's `│` rule before — used by
     * the Cloud page's grouped menu (Open/Stop │ Perms/Routes/Libs/About); the Phone page passes none.
     * A caller that wants ONE visual line per declared group passes [perLine] = that group's size.
     */
    @Composable
    fun Actions(verbs: List<StoreVerb>, modifier: Modifier = Modifier, perLine: Int = PER_LINE, dividers: Set<Int> = emptySet()) {
        Column(modifier.testTag(ACTIONS)) {
            var i = 0
            verbs.chunked(perLine.coerceAtLeast(1)).forEach { line ->
                Row {
                    line.forEach { v ->
                        if (i in dividers) Text("│", color = Ink.RULE.ink(), fontSize = StoreDensity.T_TITLE.sp,
                            modifier = Modifier.testTag("$ACTIONS:divider:$i").padding(horizontal = sdp(StoreDensity.S4)))
                        StoreBar.Button(v.label, v.fill, v.onClick, Modifier.weight(1f), v.tag,
                            alpha = v.alpha, live = v.live, description = v.description)
                        i++
                    }
                }
            }
        }
    }

    /** One line of a row's detail or under its head (facts, a reason, an install's progress), caption scale. */
    @Composable
    fun Line(text: String, modifier: Modifier = Modifier, color: Int = Ink.DIM, tag: String? = null,
             mono: Boolean = false, maxLines: Int = Int.MAX_VALUE, overflow: TextOverflow = TextOverflow.Clip) {
        Text(text, color = color.ink(), fontSize = StoreDensity.T_CAPTION.sp, maxLines = maxLines, overflow = overflow,
            fontFamily = if (mono) FontFamily.Monospace else null,
            modifier = modifier.tagged(tag).padding(horizontal = sdp(StoreDensity.S12), vertical = sdp(StoreDensity.S2)))
    }

    /** One line of the shared filter chips, painted for [selected]; [tags] (optional) tags each chip. */
    @Composable
    fun Filters(
        labels: List<String>, selected: Int, onPick: (Int) -> Unit, modifier: Modifier = Modifier,
        tags: List<String?> = emptyList(),
    ) {
        val style = rememberStoreControls().filter
        Row(modifier.fillMaxWidth().padding(bottom = sdp(StoreDensity.S6))) {
            labels.forEachIndexed { i, label ->
                StoreBar.Chip(label, i == selected, { onPick(i) }, Modifier.weight(1f), first = i == 0,
                    tag = tags.getOrNull(i), style = style)
            }
        }
    }
}

/** The Store's page frame: the tab strip on top (when this page draws it) and the page's scrolling
 *  content below it; a page's action bar is the first thing IN that content, under the tabs, as the
 *  Cloud page's header is (#896.3). */
object StorePage {
    @Composable
    fun Frame(strip: (@Composable () -> Unit)?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
        Column(modifier.fillMaxWidth()) {
            if (strip != null) Box(Modifier.padding(start = sdp(StoreDensity.S8), top = sdp(StoreDensity.S8), end = sdp(StoreDensity.S8))) { strip() }
            Column(Modifier.weight(1f), content = content)
        }
    }
}

/**
 * THE Store's top tab strip - one builder for every Store page that has tabs.
 *
 * It is what StoreCloudFragment.tabBar / tabButton / paintTabs were, lifted out so Phone
 * (Installed | Declared) draws the SAME strip as Cloud instead of
 * a second copy (#896). A strip is a list of lines, each a list of [StoreControls.Control]s
 * wearing the style their declaration names; a line whose controls all `stretch` fills the
 * width (a segmented control), any other wraps and scrolls (a set of pages). The running
 * index across the lines is the page's one tab ordering, which is what `selected`
 * and the caller's `onSelect` speak.
 *
 * No minimum height: a Store tab is as tall as its text and padding (data-dense; test-store-app.sh holds it).
 */
object StoreTabs {

    const val TAG = "store-tabs"
    fun tabTag(index: Int) = "$TAG:$index"

    /** The strip: [lines] as one column, each line one row. */
    @Composable
    fun Bar(lines: List<List<StoreControls.Control>>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
        Column(modifier.fillMaxWidth().padding(bottom = sdp(StoreDensity.S8)).testTag(TAG)) {
            var index = 0
            var drawn = 0
            for (line in lines) {
                if (line.isEmpty()) continue
                // Only BETWEEN lines, so a page with one line draws no stray rule.
                if (drawn > 0) Divider()
                drawn++
                val first = index
                index += line.size
                val stretch = line.all { it.style.stretch }
                val strip: @Composable () -> Unit = {
                    Row {
                        line.forEachIndexed { i, control ->
                            if (control.label == StoreStrip.SEPARATOR) {
                                // the "|" slot is a divider, not a page: plain text, no surface, no tap
                                Text(control.label, Modifier.padding(end = sdp(StoreDensity.S8), top = sdp(StoreDensity.S6)).testTag(tabTag(first + i)),
                                    color = Ink.RULE.ink(), fontSize = StoreDensity.T_BODY.sp, maxLines = 1)
                            } else Tab(control, first + i == selected, tabTag(first + i), stretch) { onSelect(first + i) }
                        }
                    }
                }
                // A wrapping line scrolls rather than clipping its last chip on a
                // narrow phone; a stretched line fills the width by definition.
                if (stretch) strip() else Box(Modifier.horizontalScroll(rememberScrollState())) { strip() }
            }
        }
    }

    /** The hairline plus the real space that makes a second line a second table. */
    @Composable
    fun Divider() {
        Box(Modifier.fillMaxWidth().padding(top = sdp(StoreDensity.S12), bottom = sdp(StoreDensity.S8))
            .height(sdp(StoreDensity.S1)).background(Ink.RULE.ink()))
    }

    /**
     * THE ONE tab-button builder. A stretching style (weight 1f) is a partition filling its
     * bar; a wrapping one sits left at its own width, because a set of pages is not a partition.
     * The icon leads and the chevron trails only when the style declares them. The active look is
     * whatever that style's `_active` fields say.
     */
    @Composable
    private fun RowScope.Tab(
        control: StoreControls.Control, on: Boolean, tag: String, stretch: Boolean, onClick: () -> Unit,
    ) {
        val style = control.style
        val text = listOf(control.icon, control.label, style.chevron).filter { it.isNotEmpty() }.joinToString("  ")
        val base = if (stretch) Modifier.weight(1f) else Modifier.padding(end = sdp(StoreDensity.S8))
        Box(
            base.storeSurface(style, on).testTag(tag).semantics(mergeDescendants = true) { role = Role.Tab }
                .tap(onClick)
                .padding(
                    horizontal = if (stretch) sdp(StoreDensity.S4) else sdp(StoreDensity.S12),
                    vertical = sdp(StoreDensity.S6)),
            contentAlignment = if (stretch) Alignment.Center else Alignment.CenterStart,
        ) {
            Text(text, color = (if (on) style.textActive else style.text).ink(), fontSize = StoreDensity.T_BODY.sp,
                fontWeight = weightOf(style), maxLines = 1)
        }
    }
}
