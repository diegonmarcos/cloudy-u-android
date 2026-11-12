package com.diegonmarcos.superapp.launcher

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.diegonmarcos.superapp.boards.Boards
import com.diegonmarcos.superapp.boards.BoardsSource
import com.diegonmarcos.superapp.boards.TrackerAgg
import com.diegonmarcos.superapp.boards.InboxGroup
import com.diegonmarcos.superapp.boards.InboxItem
import com.diegonmarcos.superapp.boards.PlacesDay
import com.diegonmarcos.superapp.boards.Read
import com.diegonmarcos.superapp.boards.HealthAgg
import com.diegonmarcos.superapp.boards.HealthBoard
import com.diegonmarcos.superapp.boards.RhrBlock
import com.diegonmarcos.superapp.boards.SleepAvg
import com.diegonmarcos.superapp.boards.SleepBlock
import com.diegonmarcos.superapp.boards.SleepDay
import com.diegonmarcos.superapp.boards.StepsBlock
import com.diegonmarcos.superapp.health.HealthBoardReader
import com.diegonmarcos.superapp.boards.TaskProject
import com.diegonmarcos.superapp.boards.TimelineBoard
import com.diegonmarcos.superapp.boards.TimelineSeg
import com.diegonmarcos.superapp.boards.ThreadIntents
import com.diegonmarcos.superapp.boards.Why
import com.diegonmarcos.superapp.notificationcenter.ReplyActions
import com.diegonmarcos.superapp.ui.AppIconCache
import com.diegonmarcos.superapp.uikit.KitAction
import com.diegonmarcos.superapp.uikit.KitDensity
import com.diegonmarcos.superapp.uikit.KitListRow
import com.diegonmarcos.superapp.uikit.KitStatTile
import com.diegonmarcos.superapp.uikit.KitState
import com.diegonmarcos.superapp.uikit.KitStatusBanner
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * The Boards panels (build.json::sections[boards]): kinds board_inbox, board_steps, board_places and
 * board_task_projects. Each reads its owner app through [Boards.source] off the main thread and draws the dense
 * ui-kit rows; a source that cannot answer draws its reason ([BoardUnavailable]) and never a placeholder number.
 */
internal object BoardTags {
    const val INBOX = "board:inbox"
    const val STEPS = "board:steps"
    const val PLACES = "board:places"
    const val TIMELINE = "board:timeline"
    const val TL_START = "tl:start"
    fun tlRow(i: Int, kind: String) = "tl:$i:$kind"
    const val PROJECTS = "board:projects"
    const val LOADING = "board:loading"
    fun unavailable(board: String) = "board:unavailable:$board"
    fun group(pkg: String) = "board:inbox:group:$pkg"
    fun project(id: String) = "board:project:$id"
}

/** One read per (board, refresh epoch), on IO. Null while loading. */
@Composable
private fun <T> rememberBoardRead(epoch: Int, key: String, read: (BoardsSource) -> T): T? {
    val ctx = LocalContext.current
    val v by produceState<T?>(null, epoch, key) {
        value = withContext(Dispatchers.IO) { read(Boards.source(ctx)) }
    }
    return v
}

@Composable
private fun BoardLoading() {
    AggCaption("Reading…", Modifier.testTag(BoardTags.LOADING))
}

@Composable
private fun BoardUnavailable(board: String, r: Read.Unavailable, fix: KitAction? = null) {
    val state = when (r.why) {
        Why.NO_PERMISSION, Why.NOT_INSTALLED, Why.OUTDATED -> KitState.WARN
        Why.FAILED -> KitState.BAD
    }
    KitStatusBanner(r.detail, state, Modifier.padding(vertical = KitDensity.small / 2), tag = BoardTags.unavailable(board), action = fix)
}

private fun installFix(onOpen: (String) -> Unit, why: Why, target: String, app: String): KitAction? = when (why) {
    Why.NOT_INSTALLED, Why.OUTDATED -> KitAction("Open $app", "fix:$target") { onOpen(target) }
    else -> null
}

// ── Inboxes ──────────────────────────────────────────────────────────

/** The id a board inbox item is read / archived under in the page state: its app, time and sender. */
private fun itemId(i: InboxItem) = "board:${i.pkg}/${i.ts}/${i.sender}"
private fun boxKey(pkg: String) = "board:$pkg"

@Composable
internal fun BoardInboxBody(state: AggregatorState, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val reads = rememberBoardRead(state.epoch, "inbox") { s -> s.fleetMail() to s.phoneNotifications() }
    Column(Modifier.fillMaxWidth().testTag(BoardTags.INBOX)) {
        if (reads == null) { BoardLoading(); return@Column }
        val (mail, phone) = reads
        if (mail is Read.Unavailable) BoardUnavailable("mail", mail, installFix(onOpen, mail.why, "extapp:cloud-mail", "Cloud Mail"))
        if (phone is Read.Unavailable) BoardUnavailable("phone", phone, if (phone.why == Why.NO_PERMISSION)
            KitAction("Grant Notification Access", "fix:notification_access") {
                // #1515 the grant lives in Configs > Perms: open it on the notification-listener row.
                runCatching { com.diegonmarcos.superapp.perms.PermsLink.open(ctx, "Boards: phone inbox", listOf("notification_listener")) }
            } else null)
        val groups = Boards.group((mail as? Read.Ok)?.value.orEmpty(), (phone as? Read.Ok)?.value.orEmpty())
        if (groups.isEmpty()) {
            if (mail is Read.Ok && phone is Read.Ok) AggStateLine("Nothing unread or recent in any inbox", Tone.OK)
            return@Column
        }
        val now = System.currentTimeMillis()
        var lastFleet: Boolean? = null
        for (g in groups) {
            if (state.isArchived(boxKey(g.pkg))) continue
            if (g.fleet != lastFleet) { AggShadeLabel(if (g.fleet) "FLEET" else "PHONE APPS"); lastFleet = g.fleet }
            InboxGroupCard(state, g, now, onOpen)
        }
        // A filed-away app keeps its way back: the Archive line lists them, each with Restore.
        val filed = groups.filter { state.isArchived(boxKey(it.pkg)) }
        if (filed.isNotEmpty()) {
            AggShadeLabel("ARCHIVE")
            for (g in filed) Row(
                Modifier.fillMaxWidth().padding(bottom = NotifCard.gap).clip(NotifCard.shape).background(NotifCard.surface)
                    .padding(start = NotifCard.pad, end = 8.dp, top = 6.dp, bottom = 6.dp).testTag(BoardTags.group(g.pkg) + ":archived"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(g.appLabel, color = NotifCard.title, fontSize = NotifCard.bodySp, fontFamily = NotifCard.font,
                    modifier = Modifier.weight(1f))
                Text(AggregatorModel.ARCHIVE_RESTORE_LABEL, color = NotifCard.title, fontSize = NotifCard.actionSp,
                    fontWeight = FontWeight.Medium, fontFamily = NotifCard.font,
                    modifier = Modifier.clip(NotifCard.actionShape).background(NotifCard.pill)
                        .clickable { state.setArchived(boxKey(g.pkg), false) }
                        .padding(horizontal = 16.dp, vertical = 8.dp).testTag(NotifActions.tag("restore", g.pkg)))
            }
        }
    }
}

/** One app's inbox as a One UI container: header, its items (swipe right = read, left = archive), and the box's
 *  Mark read / Archive / Open / Reply pills - the same NotifActionRow the Notify page draws. */
@Composable
private fun InboxGroupCard(state: AggregatorState, g: InboxGroup, now: Long, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    var expanded by remember(g.pkg) { mutableStateOf(false) }
    val icon by produceState<ImageBitmap?>(null, g.pkg) {
        value = withContext(Dispatchers.IO) {
            runCatching { AppIconCache.load(ctx, g.pkg)?.toBitmap(64, 64)?.asImageBitmap() }.getOrNull()
        }
    }
    val items = g.items.filter { itemId(it) !in state.archivedRowIds }
    val unread = items.count { it.unread && itemId(it) !in state.readIds }
    val shown = if (expanded) items else items.take(3)
    val newest = items.maxByOrNull { it.ts }
    val openApp: (() -> Unit)? = newest?.let { n -> { openItem(n, onOpen) } }
    val replyItem = items.filter { ReplyActions.has(it.threadKey) }.maxByOrNull { it.ts }
    Column(Modifier.fillMaxWidth().padding(bottom = NotifCard.gap).clip(NotifCard.shape).background(NotifCard.surface)
        .testTag(BoardTags.group(g.pkg))) {
        Row(Modifier.fillMaxWidth().padding(start = NotifCard.pad, end = NotifCard.pad, top = 12.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically) {
            AppGlyph(icon, g.appLabel)
            Text(g.appLabel, color = NotifCard.title, fontSize = NotifCard.nameSp, fontWeight = FontWeight.Medium,
                fontFamily = NotifCard.font, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp))
            if (g.newest > 0L) Text(" • " + AggregatorModel.shortAgo(now - g.newest), color = NotifCard.meta,
                fontSize = NotifCard.metaSp, fontFamily = NotifCard.font, maxLines = 1)
            Box(Modifier.weight(1f))
            if (unread > 0) Text("$unread unread", color = NotifCard.title, fontSize = NotifCard.metaSp,
                fontFamily = NotifCard.font,
                modifier = Modifier.clip(NotifCard.pillShape).background(NotifCard.pill).padding(horizontal = 8.dp, vertical = 2.dp))
        }
        for (item in shown) androidx.compose.runtime.key(itemId(item)) { InboxRow(state, item, now, onOpen) }
        if (items.size > 3) Text(if (expanded) "Show less" else AggregatorModel.moreLabel(items.size - 3),
            color = NotifCard.meta, fontSize = NotifCard.metaSp, fontWeight = FontWeight.Medium, fontFamily = NotifCard.font,
            modifier = Modifier.clickable { expanded = !expanded }
                .padding(start = NotifCard.pad, top = 2.dp, bottom = 2.dp))
        NotifActionRow(
            key = g.pkg,
            onRead = { for (i in items) state.markRead(itemId(i), true) },
            onArchive = { state.setArchived(boxKey(g.pkg), true) },
            onOpen = openApp,
            replyKey = replyItem?.threadKey,
            unread = unread > 0,
        )
    }
}

@Composable
private fun InboxRow(state: AggregatorState, item: InboxItem, now: Long, onOpen: (String) -> Unit) {
    val id = itemId(item)
    val unread = item.unread && id !in state.readIds
    Row(Modifier.fillMaxWidth()
        .rowSwipe(id, onRight = { state.markRead(id, unread) }, onLeft = { state.archiveRow(id, true) })
        .clickable { openItem(item, onOpen) }
        .padding(horizontal = NotifCard.pad, vertical = 6.dp).testTag("board:inbox:row:$id"),
        verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (unread) Box(Modifier.padding(end = 6.dp).size(6.dp).clip(CircleShape).background(NotifCard.title))
                Text(item.sender.ifBlank { item.appLabel }, color = if (unread) NotifCard.title else NotifCard.title.copy(alpha = 0.72f),
                    fontSize = NotifCard.titleSp, fontWeight = FontWeight.SemiBold, fontFamily = NotifCard.font,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(if (item.ts > 0) AggregatorModel.shortAgo(now - item.ts) else "", color = NotifCard.meta,
                    fontSize = NotifCard.metaSp, fontFamily = NotifCard.font, modifier = Modifier.padding(start = 8.dp))
            }
            if (item.preview.isNotBlank()) Text(item.preview, color = NotifCard.body, fontSize = NotifCard.bodySp,
                fontFamily = NotifCard.font, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** The thread when the posting app left one, else its route (extapp:/app:). */
private fun openItem(item: InboxItem, onOpen: (String) -> Unit) {
    val pi = ThreadIntents.get(item.threadKey)
    if (pi != null && runCatching { pi.send() }.isSuccess) return
    onOpen(item.target)
}

@Composable
private fun AppGlyph(icon: ImageBitmap?, label: String) {
    val p = LocalKitPalette.current
    if (icon != null) Image(icon, contentDescription = label, modifier = Modifier.size(NotifCard.headerIcon).clip(RoundedCornerShape(5.dp)))
    else Box(Modifier.size(NotifCard.headerIcon).clip(RoundedCornerShape(5.dp)).background(p.surfaceSelected),
        contentAlignment = Alignment.Center) { Text(label.take(1).uppercase(), color = p.textPrimary, fontSize = KitDensity.body) }
}


// ── ME: MyHealth (steps in km, resting heart rate, sleep) ───────

/** Counts ON_RESUME after the first composition: back from the Health Connect dialog, the board reads again. */
@Composable
private fun rememberResumeCount(): Int {
    val owner = LocalLifecycleOwner.current
    var n by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        var armed = false
        val o = LifecycleEventObserver { _, e -> if (armed && e == Lifecycle.Event.ON_RESUME) n++ }
        owner.lifecycle.addObserver(o)
        armed = true
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    return n
}

@Composable
internal fun BoardStepsBody(epoch: Int, onOpen: (String) -> Unit) {
    val resumed = rememberResumeCount()
    val r = rememberBoardRead(epoch, "health:$resumed") { it.health(30) }
    Column(Modifier.fillMaxWidth().testTag(BoardTags.STEPS)) {
        when (r) {
            null -> BoardLoading()
            is Read.Unavailable -> BoardUnavailable("steps", r)
            is Read.Ok -> HealthContent(r.value)
        }
    }
}

private val DAY_LETTER: (java.time.LocalDate) -> String =
    { it.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()) }

@Composable
private fun HealthContent(b: HealthBoard) {
    val ctx = LocalContext.current
    if (b.missing.isNotEmpty()) HealthPermissionCard(b.missing) {
        runCatching {
            ctx.startActivity(Intent(ctx, com.diegonmarcos.superapp.notificationcenter.HealthGrantActivity::class.java)
                .putExtra(com.diegonmarcos.superapp.notificationcenter.HealthGrantActivity.EXTRA_BOARD, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    val stepsAllowed = HealthBoardReader.STEPS !in b.missing || HealthBoardReader.DISTANCE !in b.missing
    AggShadeLabel("STEPS")
    if (!stepsAllowed) AggCaption("Not allowed yet: grant Steps and Distance above")
    else StepsSection(b.steps)
    AggShadeLabel("RESTING HEART RATE")
    if (HealthBoardReader.RESTING_HR in b.missing) AggCaption("Not allowed yet: grant Resting heart rate above")
    else RhrSection(b.rhr)
    AggShadeLabel("SLEEP")
    if (HealthBoardReader.SLEEP in b.missing) AggCaption("Not allowed yet: grant Sleep above")
    else SleepSection(b.sleep)
    AggCaption("Source: Health Connect, read on this phone" + (b.error?.let { " · a read failed ($it)" } ?: ""))
    TrackerHealthSection()
}

/** Sleep hours and walking distance as Cloud Nav's tracker records them (tracker/daily, contract v1); independent of Health Connect. */
@Composable
private fun TrackerHealthSection() {
    val r = rememberBoardRead(0, "tracker-daily") { it.daily(7) }
    AggShadeLabel("PHONE TRACKER (CLOUD NAV)")
    when (r) {
        null -> BoardLoading()
        is Read.Unavailable -> AggCaption("Tracker: ${r.detail}", Modifier.testTag(BoardTags.unavailable("tracker-daily")))
        is Read.Ok -> {
            val t = TrackerAgg.summarize(r.value, java.time.LocalDate.now())
            val ok = t.lastNightH != null || t.walkTodayKm != null || t.avgWalkKm != null
            Row(Modifier.fillMaxWidth().padding(vertical = KitDensity.small / 2), horizontalArrangement = Arrangement.spacedBy(KitDensity.small)) {
                val st = if (ok) KitState.OK else KitState.IDLE
                KitStatTile(TrackerAgg.hoursText(t.lastNightH), "Slept" + (t.sleepCity?.let { " in $it" } ?: ""), st, Modifier.weight(1f), tag = "tracker-sleep")
                KitStatTile(TrackerAgg.hoursText(t.avgSleepH), "7-day sleep average", st, Modifier.weight(1f), tag = "tracker-sleep-avg")
                KitStatTile(HealthAgg.kmText(t.walkTodayKm), "Walked today", st, Modifier.weight(1f), tag = "tracker-walk")
                KitStatTile(HealthAgg.kmText(t.avgWalkKm), "7-day walk average", st, Modifier.weight(1f), tag = "tracker-walk-avg")
            }
            AggCaption("Source: Cloud Nav tracker (segments of the day, no Health Connect needed)")
        }
    }
}

/** Which of the four reads the board has, one line each, and the one button that asks for the rest. */
@Composable
private fun HealthPermissionCard(missing: Set<String>, onGrant: () -> Unit) {
    val p = LocalKitPalette.current
    val rows = listOf("Steps" to HealthBoardReader.STEPS, "Distance" to HealthBoardReader.DISTANCE,
        "Resting heart rate" to HealthBoardReader.RESTING_HR, "Sleep" to HealthBoardReader.SLEEP)
    KitStatusBanner("MyHealth needs read access in Health Connect", KitState.WARN,
        Modifier.padding(vertical = KitDensity.small / 2), tag = BoardTags.unavailable("health-permissions"),
        action = KitAction("Grant access", "fix:health_grant", onClick = onGrant))
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("health-permissions")) {
        for ((label, perm) in rows) {
            val ok = perm !in missing
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).testTag("health-perm:$label"), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, color = p.textSecondary, fontSize = KitDensity.body)
                Text(if (ok) "Allowed" else "Not allowed", color = if (ok) p.ok else p.warn, fontSize = KitDensity.body, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun StatTriple(tag: String, today: Pair<String, String>, avg7: Pair<String, String>, avg30: Pair<String, String>, ok: Boolean) {
    val state = if (ok) KitState.OK else KitState.IDLE
    Row(Modifier.fillMaxWidth().padding(vertical = KitDensity.small / 2), horizontalArrangement = Arrangement.spacedBy(KitDensity.small)) {
        KitStatTile(today.first, today.second, state, Modifier.weight(1f), tag = "$tag-today")
        KitStatTile(avg7.first, avg7.second, state, Modifier.weight(1f), tag = "$tag-avg")
        KitStatTile(avg30.first, avg30.second, state, Modifier.weight(1f), tag = "$tag-avg30")
    }
}

@Composable
private fun StepsSection(s: StepsBlock) {
    val any = s.week.any { it.km != null } || s.avg30.km != null
    val est = s.today.estimated || s.avg7.estimated || s.avg30.estimated || s.week.any { it.estimated }
    StatTriple("steps",
        HealthAgg.kmText(s.today.km, s.today.estimated) to "Today" + (s.today.steps?.let { " (%,d steps)".format(it) } ?: ""),
        HealthAgg.kmText(s.avg7.km, s.avg7.estimated) to "7-day average" + (s.avg7.steps?.let { " (%,d steps)".format(it) } ?: ""),
        HealthAgg.kmText(s.avg30.km, s.avg30.estimated) to "30-day average" + (s.avg30.steps?.let { " (%,d steps)".format(it) } ?: ""),
        any)
    if (!any) { AggCaption("No step data in Health Connect for 30 days. If you use Samsung Health, turn on its Health Connect sync."); return }
    BarChart("steps-chart", s.week.map { it.date }, s.week.map { it.km?.toFloat() },
        s.week.map { d -> d.km?.let { String.format(Locale.US, "%.1f", it) } ?: "--" })
    AggCaption("Bars: km per day" + if (est) " · ~ marks an estimate from steps x ${"%.2f".format(Locale.US, s.strideM)} m stride" +
        (if (s.strideFromHeight) " (from your height)" else " (default; no height record)") + ", used where Health Connect has no distance" else "")
}

@Composable
private fun RhrSection(r: RhrBlock) {
    val any = r.week.any { it.bpm != null } || r.avg30 != null
    StatTriple("rhr",
        HealthAgg.bpmText(r.today) to "Today (bpm)", HealthAgg.bpmText(r.avg7) to "7-day average (bpm)",
        HealthAgg.bpmText(r.avg30) to "30-day average (bpm)", any)
    if (!any) { AggCaption("No resting heart rate in Health Connect for 30 days"); return }
    BarChart("rhr-chart", r.week.map { it.date }, r.week.map { it.bpm?.toFloat() }, r.week.map { HealthAgg.bpmText(it.bpm) })
    AggCaption("Bars: resting bpm per day")
}

@Composable
private fun SleepSection(s: SleepBlock) {
    val p = LocalKitPalette.current
    val any = s.week.any { it.night != null } || s.avg30 != null
    val last = s.lastNight
    StatTriple("sleep",
        HealthAgg.hm(last?.asleepMin?.toDouble()) to "Last night",
        HealthAgg.hm(s.avg7?.asleepMin) to "7-day average", HealthAgg.hm(s.avg30?.asleepMin) to "30-day average", any)
    if (!any) { AggCaption("No sleep sessions in Health Connect for 30 days"); return }
    // Stage table: one row per stage, one column per figure.
    val stages = listOf(
        Triple("Deep", { n: HealthBoardReader.Night -> n.deepMin.toDouble() }, { a: SleepAvg -> a.deepMin }),
        Triple("REM", { n: HealthBoardReader.Night -> n.remMin.toDouble() }, { a: SleepAvg -> a.remMin }),
        Triple("Light", { n: HealthBoardReader.Night -> n.lightMin.toDouble() }, { a: SleepAvg -> a.lightMin }),
        Triple("Awake", { n: HealthBoardReader.Night -> n.awakeMin.toDouble() }, { a: SleepAvg -> a.awakeMin }),
    )
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("sleep-stages")) {
        for ((name, night, avg) in stages) {
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).testTag("sleep-stage:$name")) {
                Box(Modifier.padding(top = 4.dp).size(KitDensity.dot).clip(CircleShape).background(stageColor(name)))
                Text(name, color = p.textSecondary, fontSize = KitDensity.body, modifier = Modifier.weight(1f).padding(start = 6.dp))
                for (v in listOf(last?.let { night(it) }, s.avg7?.let { avg(it) }, s.avg30?.let { avg(it) }))
                    Text(HealthAgg.hm(v), color = p.textPrimary, fontSize = KitDensity.body, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f))
            }
        }
    }
    val anyUnstaged = s.week.any { it.night?.staged == false }
    SleepChart(s.week)
    AggCaption("Bars: hours asleep per night, stacked by stage" +
        if (anyUnstaged) " · a night without stages from its source is drawn as light sleep" else "")
}

@Composable
private fun stageColor(stage: String): androidx.compose.ui.graphics.Color {
    val p = LocalKitPalette.current
    return when (stage) { "Deep" -> p.accent; "REM" -> p.ok; "Light" -> p.textSecondary; else -> p.warn }
}

/** A 7-bar chart: each bar's value written on top of it; a missing day writes "--" and draws no bar. */
@Composable
private fun BarChart(tag: String, dates: List<java.time.LocalDate>, values: List<Float?>, labels: List<String>) {
    val p = LocalKitPalette.current
    val max = (values.filterNotNull().maxOrNull() ?: 0f).coerceAtLeast(0.001f)
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = KitDensity.small).testTag(tag),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        dates.forEachIndexed { i, d ->
            val v = values[i]
            Column(Modifier.weight(1f).testTag("$tag:$d"), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(labels[i], color = if (v == null) p.textSecondary else p.textPrimary, fontSize = KitDensity.caption,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(bottom = 2.dp))
                Box(Modifier.width(14.dp).height(if (v == null) 2.dp else (64 * (v / max)).coerceAtLeast(2f).dp)
                    .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)).background(if (v == null) p.hairline else p.accent))
                Text(DAY_LETTER(d), color = p.textSecondary, fontSize = KitDensity.caption, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

/** Sleep as BarChart, but each bar is the night's stages stacked (deep at the foot, awake on top), hours on top. */
@Composable
private fun SleepChart(week: List<SleepDay>) {
    val p = LocalKitPalette.current
    val max = (week.maxOfOrNull { (it.night?.let { n -> n.asleepMin + n.awakeMin } ?: 0L) } ?: 0L).coerceAtLeast(1L).toFloat()
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = KitDensity.small).testTag("sleep-chart"),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        for (d in week) {
            val n = d.night
            Column(Modifier.weight(1f).testTag("sleep-chart:${d.date}"), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(n?.let { HealthAgg.hours(it.asleepMin.toDouble()) } ?: "--", color = if (n == null) p.textSecondary else p.textPrimary,
                    fontSize = KitDensity.caption, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(bottom = 2.dp))
                if (n == null) Box(Modifier.width(14.dp).height(2.dp).background(p.hairline))
                else Column(Modifier.width(14.dp).clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))) {
                    for ((name, m) in listOf("Awake" to n.awakeMin, "REM" to n.remMin, "Light" to n.lightMin, "Deep" to n.deepMin))
                        if (m > 0) Box(Modifier.fillMaxWidth().height((64 * m / max).coerceAtLeast(1f).dp).background(stageColor(name)))
                }
                Text(DAY_LETTER(d.date), color = p.textSecondary, fontSize = KitDensity.caption, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

// ── ME: places (the older tracker/places card) ─────────────────────────────────────

@Composable
internal fun BoardPlacesBody(epoch: Int, onOpen: (String) -> Unit) {
    val r = rememberBoardRead(epoch, "places") { it.places(7) }
    Column(Modifier.fillMaxWidth().testTag(BoardTags.PLACES)) {
        when (r) {
            null -> BoardLoading()
            is Read.Unavailable -> BoardUnavailable("places", r, installFix(onOpen, r.why, "extapp:cloud-nav", "Cloud Nav"))
            is Read.Ok -> {
                val days = r.value.filter { it.visits.isNotEmpty() }
                if (days.isEmpty()) {
                    AggStateLine("No places recorded in the last 7 days — Cloud Nav's tracker has no location fixes", Tone.UNKNOWN)
                } else days.forEach { PlacesDayCard(it, onOpen) }
            }
        }
    }
}

private val DAY_FMT = DateTimeFormatter.ofPattern("EEE d MMM")

@Composable
private fun PlacesDayCard(d: PlacesDay, onOpen: (String) -> Unit) {
    val p = LocalKitPalette.current
    Column(Modifier.fillMaxWidth().padding(vertical = KitDensity.small / 2).clip(RoundedCornerShape(KitDensity.corner))
        .background(p.surface).padding(horizontal = KitDensity.rowPadH, vertical = KitDensity.rowPadV)
        .testTag("places-day:${d.date}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(d.date.format(DAY_FMT), color = p.textPrimary, fontSize = KitDensity.title, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            if (d.steps > 0) Text("%,d steps".format(d.steps), color = p.textSecondary, fontSize = KitDensity.caption)
        }
        Row(Modifier.fillMaxWidth().padding(top = KitDensity.small / 2)) {
            Column(Modifier.weight(1f)) {
                for (v in d.visits) KitListRow(v.label, secondary = v.part + if (v.predicted) " · estimated" else "",
                    tag = "place:${d.date}:${v.part}", onClick = { onOpen("extapp:cloud-nav") })
            }
            MiniMap(d, Modifier.padding(start = KitDensity.small).size(88.dp))
        }
    }
}

/** The day's places as a small equirectangular sketch: dots in visit order joined by a line. Not a tile map — it shows
 *  shape and spread with no network fetch. */
@Composable
private fun MiniMap(d: PlacesDay, modifier: Modifier) {
    val p = LocalKitPalette.current
    val pts = d.visits.filter { !it.lat.isNaN() && !it.lon.isNaN() }
    Canvas(modifier.clip(RoundedCornerShape(KitDensity.small)).background(p.surfaceSelected).testTag("places-map:${d.date}")) {
        if (pts.isEmpty()) return@Canvas
        val minLat = pts.minOf { it.lat }; val maxLat = pts.maxOf { it.lat }
        val minLon = pts.minOf { it.lon }; val maxLon = pts.maxOf { it.lon }
        val span = maxOf(maxLat - minLat, (maxLon - minLon) * Math.cos(Math.toRadians((minLat + maxLat) / 2)), 1e-4)
        val pad = 10f
        fun at(lat: Double, lon: Double): Offset {
            val x = ((lon - minLon) * Math.cos(Math.toRadians((minLat + maxLat) / 2)) / span).toFloat()
            val y = ((maxLat - lat) / span).toFloat()
            return Offset(pad + x * (size.width - 2 * pad), pad + y * (size.height - 2 * pad))
        }
        val path = Path()
        pts.forEachIndexed { i, v -> val o = at(v.lat, v.lon); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
        drawPath(path, p.textSecondary, style = Stroke(width = 2f))
        pts.forEach { v -> drawCircle(p.accent, radius = 5f, center = at(v.lat, v.lon)) }
    }
}

// ── ME: MyTrips (the last 24 h timeline from Cloud Nav's store) ─────────────

private val TL_HM = DateTimeFormatter.ofPattern("HH:mm")

/** What one timeline row says. Pure, so a JVM test holds the wording. */
internal data class TlRowText(val title: String, val detail: String, val leading: String)

internal fun tlRowText(s: TimelineSeg, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): TlRowText {
    fun hm(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(zone).format(TL_HM)
    val span = hm(s.startMs) + "–" + hm(s.endMs)
    val mins = ((s.endMs - s.startMs) / 60_000L).coerceAtLeast(0)
    val dur = if (mins >= 60) "${mins / 60} h ${mins % 60} min" else "$mins min"
    val place = s.place.ifBlank { if (s.lat != null && s.lon != null) "%.4f, %.4f".format(java.util.Locale.US, s.lat, s.lon) else "unknown place" }
    val km = if (s.distanceM >= 1000) "%.1f km".format(java.util.Locale.US, s.distanceM / 1000.0) else "${s.distanceM} m"
    return when (s.kind) {
        "move" -> TlRowText("Move", "$span · $dur" + (if (s.distanceM > 0) " · $km" else "") + (if (s.steps > 0) " · %,d steps".format(s.steps) else ""), "→")
        "sleep" -> TlRowText("Sleep · $place", "$span · $dur" + (if (s.steps > 0) " · %,d steps".format(s.steps) else ""), "z")
        else -> TlRowText("Stop · $place", "$span · $dur" + (if (s.steps > 0) " · %,d steps".format(s.steps) else ""), "•")
    }
}

@Composable
internal fun BoardTimelineBody(epoch: Int, onOpen: (String) -> Unit) {
    val r = rememberBoardRead(epoch, "timeline") { it.timeline(24) }
    Column(Modifier.fillMaxWidth().testTag(BoardTags.TIMELINE)) {
        when (r) {
            null -> BoardLoading()
            is Read.Unavailable -> BoardUnavailable("timeline", r, installFix(onOpen, r.why, "extapp:cloud-nav", "Cloud Nav"))
            is Read.Ok -> TimelineCard(r.value, onOpen)
        }
    }
}

@Composable
private fun TimelineCard(b: TimelineBoard, onOpen: (String) -> Unit) {
    val p = LocalKitPalette.current
    if (b.segments.isEmpty()) {
        AggStateLine("Nothing on the timeline for the last ${b.hours} h — Cloud Nav has recorded no stop or move", Tone.UNKNOWN)
        return
    }
    val zone = java.time.ZoneId.systemDefault()
    val steps = b.segments.sumOf { it.steps }
    val km = b.segments.sumOf { it.distanceM } / 1000.0
    Column(Modifier.fillMaxWidth().padding(vertical = KitDensity.small / 2).clip(RoundedCornerShape(KitDensity.corner))
        .background(p.surface).padding(horizontal = KitDensity.rowPadH, vertical = KitDensity.rowPadV)) {
        Text("Last ${b.hours} h · %,d steps · %.1f km".format(steps, km), color = p.textSecondary, fontSize = KitDensity.caption,
            modifier = Modifier.padding(bottom = KitDensity.small / 2))
        KitListRow("Start", secondary = java.time.Instant.ofEpochMilli(b.segments.first().startMs).atZone(zone).format(TL_HM),
            leading = "◦", tag = BoardTags.TL_START)
        b.segments.forEachIndexed { i, s ->
            val t = tlRowText(s, zone)
            KitListRow(t.title, secondary = t.detail, leading = t.leading, tag = BoardTags.tlRow(i, s.kind),
                onClick = { onOpen("extapp:cloud-nav") })
        }
    }
}

// ── Tasks: cloud-tasks projects ─────────────────────────────────────

@Composable
internal fun BoardTaskProjectsBody(epoch: Int, onOpen: (String) -> Unit) {
    val r = rememberBoardRead(epoch, "projects") { it.taskProjects() }
    Column(Modifier.fillMaxWidth().testTag(BoardTags.PROJECTS)) {
        when (r) {
            null -> BoardLoading()
            is Read.Unavailable -> BoardUnavailable("projects", r, installFix(onOpen, r.why, "extapp:cloud-tasks", "Cloud Tasks"))
            is Read.Ok -> if (r.value.isEmpty()) {
                AggStateLine("Cloud Tasks declares no projects yet", Tone.UNKNOWN)
            } else r.value.forEach { ProjectRow(it, onOpen) }
        }
    }
}

@Composable
private fun ProjectRow(t: TaskProject, onOpen: (String) -> Unit) {
    val p = LocalKitPalette.current
    val next = t.nextDueName?.let { n -> "Next: $n" + (t.nextDue?.let { " · $it" } ?: "") } ?: "Nothing due"
    KitListRow(t.name, secondary = "${t.open} open · ${t.done} done · $next",
        leading = t.name.take(1).uppercase(), tag = BoardTags.project(t.id),
        onClick = { onOpen("extapp:cloud-tasks#project:${t.id}") },
        trailing = { Text("${t.open}", color = p.textPrimary, fontSize = KitDensity.title, fontWeight = FontWeight.Bold) })
}
