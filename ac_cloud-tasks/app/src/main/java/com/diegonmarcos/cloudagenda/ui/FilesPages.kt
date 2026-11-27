package com.diegonmarcos.cloudagenda.ui

import androidx.compose.foundation.background
import com.diegonmarcos.superapp.uiicons.UiIcons
import com.diegonmarcos.cloudagenda.data.TripRoadmap
import com.diegonmarcos.cloudagenda.data.TripBlock
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudagenda.data.Backlog
import com.diegonmarcos.cloudagenda.data.BacklogSnapshot
import com.diegonmarcos.cloudagenda.data.BacklogTicket
import com.diegonmarcos.cloudagenda.data.MockTask
import com.diegonmarcos.cloudagenda.data.ProjectSelection
import com.diegonmarcos.cloudagenda.data.RoadProject
import com.diegonmarcos.cloudagenda.data.Structure
import com.diegonmarcos.superapp.uikit.KitDensity
import com.diegonmarcos.superapp.uikit.KitFileTree
import com.diegonmarcos.superapp.uikit.KitFileTreeInk
import com.diegonmarcos.superapp.uikit.KitStoreRow
import com.diegonmarcos.superapp.uikit.kitFileTree
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import java.time.LocalDate

object FilesTags {
    const val BACKLOG = "backlog_page"
    const val COUNT = "backlog_count"
    const val REASON = "backlog_reason"
    const val ROADMAP = "roadmap_page"
    const val ASSETS = "assets_page"
    const val TRIP = "roadmap_trip"
    fun tripLink(label: String) = "trip_link_$label"
    fun tripCard(label: String) = "trip_card_$label"
    const val ASSETS_TREE = "assets_tree"
    fun mode(id: String) = "backlog_mode_$id"
    fun status(id: String) = "backlog_status_$id"
    fun ticket(id: String) = "backlog_ticket_$id"
    fun project(name: String) = "roadmap_project_$name"
    fun assetsMode(id: String) = "assets_mode_$id"
}

private const val SHOWN = 300

private fun sizeOf(b: Long): String = when {
    b >= 1_048_576 -> "%.1f MB".format(b / 1_048_576.0)
    b >= 1024 -> "%d KB".format(b / 1024)
    else -> "$b B"
}

@Composable
private fun Reason(snap: BacklogSnapshot) {
    val p = LocalKitPalette.current
    snap.reason?.let { Text(it, color = p.textSecondary, fontSize = KitStoreRow.caption, modifier = Modifier.padding(horizontal = KitStoreRow.padH, vertical = KitStoreRow.tight).testTag(FilesTags.REASON)) }
}

/**
 * #1974 Tasks > Backlog: the Product Backlog's tickets of the Home selection (filtered on the scope each ticket carries; "All" lists every one),
 * with a status filter, and (Structure) the Cloud Me tree this page showed before, unchanged.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BacklogPage(snap: BacklogSnapshot, sel: ProjectSelection, structure: Structure, scoped: List<MockTask>, today: LocalDate, modifier: Modifier = Modifier) {
    val p = LocalKitPalette.current
    var mode by rememberSaveable { mutableStateOf("tickets") }
    val shown = remember { mutableStateListOf("open", "doing") }
    val kept = remember(snap, sel) { Backlog.filter(snap.tickets, sel) }
    val rows = remember(kept, shown.toList()) {
        kept.filter { t -> if (t.done) "done" in shown else if (t.inProgress) "doing" in shown else "open" in shown }
    }
    Column(modifier.fillMaxSize().testTag(FilesTags.BACKLOG)) {
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = KitStoreRow.padH, vertical = KitStoreRow.tight), horizontalArrangement = Arrangement.spacedBy(KitStoreRow.gap), verticalArrangement = Arrangement.spacedBy(KitStoreRow.gap)) {
            Chip("Tickets", mode == "tickets", Modifier.testTag(FilesTags.mode("tickets"))) { mode = "tickets" }
            Chip("Structure", mode == "structure", Modifier.testTag(FilesTags.mode("structure"))) { mode = "structure" }
            if (mode == "tickets") for ((id, label) in listOf("open" to "Open", "doing" to "In progress", "done" to "Done"))
                Chip(label, id in shown, Modifier.testTag(FilesTags.status(id))) { if (id in shown) shown.remove(id) else shown.add(id) }
        }
        if (mode == "structure") CollectionPage("backlogs", structure, scoped, today, Modifier.weight(1f))
        else {
            Reason(snap)
            Text("Showing " + rows.size + " of " + snap.tickets.size + " tickets", color = p.textSecondary, fontSize = KitStoreRow.caption,
                modifier = Modifier.padding(horizontal = KitStoreRow.padH).testTag(FilesTags.COUNT))
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(rows.take(SHOWN), key = { it.id }) { t -> TicketRow(t) }
                if (rows.size > SHOWN) item { Text("+ " + (rows.size - SHOWN) + " more (narrow the project selection or the status)", color = p.textSecondary, fontSize = KitStoreRow.caption, modifier = Modifier.padding(KitStoreRow.padH)) }
            }
        }
    }
}

@Composable
private fun TicketRow(t: BacklogTicket) {
    val p = LocalKitPalette.current
    Column(Modifier.fillMaxWidth().padding(horizontal = KitStoreRow.padH, vertical = KitStoreRow.tight).testTag(FilesTags.ticket(t.id))) {
        Text("#" + t.id + "  " + t.subject.removePrefix("#" + t.id).trim(), color = p.textPrimary, fontSize = KitStoreRow.body, maxLines = 2)
        Text(
            listOf(t.status, t.project, t.familyName).filter { it.isNotBlank() }.joinToString("  ·  "),
            color = when { t.done -> KitStoreRow.UP; t.inProgress -> KitStoreRow.UPDATE; else -> KitStoreRow.MISSING }, fontSize = KitStoreRow.caption,
        )
    }
}

/** #1974 Files > RoadMap: every project of the selection with its counts, its families and a timeline of ticket-number buckets (newest first), built from the backlog. */
@Composable
fun RoadMapPage(snap: BacklogSnapshot, sel: ProjectSelection, trip: TripRoadmap = TripRoadmap.EMPTY, onTarget: (String) -> Unit = {}, modifier: Modifier = Modifier) {
    val p = LocalKitPalette.current
    val projects = remember(snap, sel) { Backlog.roadmap(Backlog.filter(snap.tickets, sel)) }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = KitStoreRow.padH, vertical = KitStoreRow.padV).testTag(FilesTags.ROADMAP)) {
        TripSection(trip, onTarget)
        Reason(snap)
        if (projects.isEmpty()) Text("No tickets for this selection.", color = p.textSecondary, fontSize = KitStoreRow.body)
        for (r in projects) RoadCard(r)
    }
}

@Composable
private fun RoadCard(r: RoadProject) {
    val p = LocalKitPalette.current
    val shape = RoundedCornerShape(KitDensity.small)
    Column(Modifier.fillMaxWidth().padding(top = KitStoreRow.gap).clip(shape).background(p.surface).padding(KitStoreRow.padH).testTag(FilesTags.project(r.name))) {
        Text(r.name, color = p.textPrimary, fontSize = KitStoreRow.title, fontWeight = FontWeight.Bold)
        Text(r.total.toString() + " tickets  ·  " + r.done + " done  ·  " + r.inProgress + " in progress  ·  " + r.open + " open", color = p.textSecondary, fontSize = KitStoreRow.caption)
        Bar(r.done, r.inProgress, r.open, Modifier.padding(vertical = KitStoreRow.gap))
        if (r.families.isNotEmpty()) Text("Families: " + r.families.take(6).joinToString(", ") { it.first + " (" + it.second + ")" }, color = p.textSecondary, fontSize = KitStoreRow.caption)
        for (e in r.epochs.take(8)) Row(Modifier.fillMaxWidth().padding(top = KitStoreRow.tight)) {
            Text("#" + e.from + "-" + e.to, color = p.textPrimary, fontSize = KitStoreRow.caption, modifier = Modifier.padding(end = KitStoreRow.gap))
            Bar(e.done, e.inProgress, e.open, Modifier.weight(1f).padding(top = 4.dp))
            Text("  " + e.done + "/" + e.inProgress + "/" + e.open, color = p.textSecondary, fontSize = KitStoreRow.caption)
        }
        for (t in r.active.take(5)) Text("#" + t.id + "  " + t.subject.removePrefix("#" + t.id).trim(), color = p.textPrimary, fontSize = KitStoreRow.caption, maxLines = 1, modifier = Modifier.padding(top = KitStoreRow.tight))
    }
}

@Composable
private fun Bar(done: Int, doing: Int, open: Int, modifier: Modifier = Modifier) {
    val p = LocalKitPalette.current
    Row(modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(p.hairline.copy(alpha = 0.4f))) {
        if (done > 0) Box(Modifier.weight(done.toFloat()).height(6.dp).background(KitStoreRow.UP))
        if (doing > 0) Box(Modifier.weight(doing.toFloat()).height(6.dp).background(KitStoreRow.UPDATE))
        if (open > 0) Box(Modifier.weight(open.toFloat()).height(6.dp).background(KitStoreRow.MISSING))
    }
}

/**
 * #1974 Files > Assets: the backlog repo's 4___ASSETS___ tree (collapsible), and Library, the notes-vault tree the Library page showed before it left Home
 * (re-homed here so none of its content is lost).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AssetsPage(snap: BacklogSnapshot, structure: Structure, scoped: List<MockTask>, today: LocalDate, modifier: Modifier = Modifier) {
    val p = LocalKitPalette.current
    var mode by rememberSaveable { mutableStateOf("assets") }
    val open = remember { mutableStateListOf<String>() }
    val rows = KitFileTree.rows(snap.assets, open.toSet())
    val ink = KitFileTreeInk(p.textPrimary, p.textSecondary, p.accent)
    Column(modifier.fillMaxSize().testTag(FilesTags.ASSETS)) {
        Row(Modifier.padding(horizontal = KitStoreRow.padH, vertical = KitStoreRow.tight), horizontalArrangement = Arrangement.spacedBy(KitStoreRow.gap)) {
            Chip(Backlog.ASSETS_DIR, mode == "assets", Modifier.testTag(FilesTags.assetsMode("assets"))) { mode = "assets" }
            Chip("Library", mode == "library", Modifier.testTag(FilesTags.assetsMode("library"))) { mode = "library" }
        }
        if (mode == "library") CollectionPage("library", structure, scoped, today, Modifier.weight(1f))
        else {
            Reason(snap)
            if (snap.assets.isEmpty()) Text("No files in " + Backlog.ASSETS_DIR + ".", color = p.textSecondary, fontSize = KitStoreRow.body, modifier = Modifier.padding(KitStoreRow.padH))
            else LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag(FilesTags.ASSETS_TREE)) {
                kitFileTree(rows, ink, size = ::sizeOf, date = { "" }, onToggle = { k -> if (k in open) open.remove(k) else open.add(k) }, onOpen = {}, tagPrefix = FilesTags.ASSETS_TREE)
            }
        }
    }
}

/** #1975 The trip roadmap (moved here from Cloud Me's Geo page): the "Trip management" link grid, the "Before you go" note and the four checklist cards. */
@Composable
fun TripSection(trip: TripRoadmap, onTarget: (String) -> Unit) {
    if (trip.blocks.isEmpty()) return
    val p = LocalKitPalette.current
    val shape = RoundedCornerShape(KitDensity.small)
    Column(Modifier.fillMaxWidth().testTag(FilesTags.TRIP)) {
        Text("Trip roadmap", color = p.textPrimary, fontSize = KitStoreRow.title, fontWeight = FontWeight.Bold)
        for (b in trip.blocks) when (b) {
            is TripBlock.Grid -> {
                Text(b.title, color = p.textPrimary, fontSize = KitStoreRow.body, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = KitStoreRow.gap))
                Text(b.subtitle, color = p.textSecondary, fontSize = KitStoreRow.caption)
                Row(Modifier.fillMaxWidth().padding(top = KitStoreRow.gap), horizontalArrangement = Arrangement.spacedBy(KitStoreRow.gap)) {
                    for (l in b.links) Column(
                        Modifier.weight(1f).clip(shape).background(p.surface).clickable { onTarget(l.target) }.padding(KitStoreRow.gap).testTag(FilesTags.tripLink(l.label)),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(UiIcons.vector(l.icon), null, tint = p.accent, modifier = Modifier.size(24.dp))
                        Text(l.label, color = p.textPrimary, fontSize = KitStoreRow.caption)
                    }
                }
            }
            is TripBlock.Note -> {
                Text(b.title, color = p.textPrimary, fontSize = KitStoreRow.body, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = KitStoreRow.padH))
                Text(b.body, color = p.textSecondary, fontSize = KitStoreRow.caption)
            }
            is TripBlock.Cards -> for (c in b.items) {
                val ink = runCatching { Color(android.graphics.Color.parseColor(c.accent)) }.getOrDefault(p.accent)
                Row(Modifier.fillMaxWidth().padding(top = KitStoreRow.gap).clip(shape).background(p.surface).clickable { onTarget(c.target) }.testTag(FilesTags.tripCard(c.label))) {
                    Box(Modifier.width(5.dp).height(48.dp).background(ink))
                    Column(Modifier.padding(KitStoreRow.padH)) {
                        Text(c.label, color = p.textPrimary, fontSize = KitStoreRow.body, fontWeight = FontWeight.Bold)
                        Text(c.subtitle, color = p.textSecondary, fontSize = KitStoreRow.caption)
                    }
                }
            }
        }
        Text("Backlog roadmap", color = p.textPrimary, fontSize = KitStoreRow.title, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = KitStoreRow.padH))
    }
}
