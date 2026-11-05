package com.diegonmarcos.cloudc3.pages

import android.os.Bundle
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.SolidColor
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudc3.Declarations
import com.diegonmarcos.cloudc3.cloud.C3HealthFragment
import com.diegonmarcos.cloudc3.cloud.C3StackFragment
import com.diegonmarcos.cloudc3.cloud.ContainerSheet
import com.diegonmarcos.cloudc3.pages.RoutesTableModel.Column as Col
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.KitPalette

/** Test tags of the Table page, for the Compose suite. */
object RoutesTags {
    const val SCREEN = "routes_screen"
    const val COUNT = "routes_count"
    const val CATEGORY_CHIP = "routes_category_chip"
    const val MENU_COPY = "routes_menu_copy"
    const val MENU_OPEN = "routes_menu_open"
    const val MENU_COPY_OPEN = "routes_menu_copy_open"
    const val SHEET = "routes_sheet"
    const val RETEST = "routes_retest"
    const val REFRESH = "routes_refresh"
    const val EXPORT = "routes_export"
    const val EXPORT_COPY = "routes_export_copy"
    const val EXPORT_CSV = "routes_export_csv"
    const val EXPORT_MD = "routes_export_md"
    const val DECL_PAGE = "routes_decl_page"
    const val DECL_RAW = "routes_decl_raw"
    const val DECL_COPY = "routes_decl_copy"
    const val DECL_BACK = "routes_decl_back"
    const val DECL_META = "routes_decl_meta"
    fun light(i: Int) = "routes_light_$i"
    fun more(i: Int) = "routes_more_$i"
    fun lightChip(id: String) = "routes_lightchip_$id"
    fun leg(id: String) = "routes_leg_$id"
    const val SEARCH = "routes_search"
    const val SEARCH_CLEAR = "routes_search_clear"
    const val LIST = "routes_list"
    fun header(c: Col) = "routes_header_${c.key}"
    fun cell(c: Col) = "routes_cell_${c.key}"
    fun authChip(id: String) = "routes_auth_$id"
    fun vmChip(vm: String) = "routes_vm_$vm"
}

/** Where the chosen sort and filters live between launches. */
interface RoutesStateStore {
    fun load(): RoutesTableModel.State
    fun save(state: RoutesTableModel.State)
}

/** SharedPreferences-backed store; every access is guarded, a failure is the default state. */
class PrefsRoutesStateStore(context: Context) : RoutesStateStore {
    private val prefs = context.applicationContext.getSharedPreferences("c3_routes_table", Context.MODE_PRIVATE)
    override fun load() = RoutesTableModel.decode(runCatching { prefs.getString(KEY, null) }.getOrNull())
    override fun save(state: RoutesTableModel.State) {
        runCatching { prefs.edit().putString(KEY, RoutesTableModel.encode(state)).apply() }
    }
    private companion object { const val KEY = "state" }
}

private val widths = mapOf(
    Col.STATUS to 56.dp,
    Col.GROUP to 84.dp, Col.CATEGORY to 140.dp, Col.CONTAINER to 150.dp, Col.PUBLIC_URL to 250.dp, Col.AUTH_TYPE to 84.dp,
    Col.PRIVATE_URL to 190.dp, Col.VM_NAME to 100.dp, Col.VM_ADDR to 130.dp,
)
private val headers = mapOf(
    Col.STATUS to "Status",
    Col.GROUP to "Group", Col.CATEGORY to "Category", Col.CONTAINER to "Container", Col.PUBLIC_URL to "Public URL", Col.AUTH_TYPE to "Auth Type",
    Col.PRIVATE_URL to "Private URL", Col.VM_NAME to "VM Name", Col.VM_ADDR to "VM IP:PORT",
)

/**
 * Containers > Table: one row per container with Category, Container, Public URL, Auth Type, Private URL, VM Name and
 * VM IP:PORT. A header tap sorts by that column (again to flip, with a triangle for the direction); Auth Type and VM
 * are multi-select chips; the count line says how many rows the filters leave. [initial] and [onState] carry the
 * sort and filters so the host can persist them.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RoutesTableScreen(
    rows: List<RoutesTableModel.Row>,
    initial: RoutesTableModel.State = RoutesTableModel.State(),
    onState: (RoutesTableModel.State) -> Unit = {},
    onOpenUrl: (String) -> Unit = {},
    onOpen: (RoutesTableModel.OpenTarget) -> Unit = {},
    /** Null = no probing (the lights stay grey). */
    prober: Prober? = null,
    store: RoutesProbeStore = remember { RoutesProbeStore() },
    /** data/declarations.json: container name -> declaration, for the More page. */
    declarations: JsonObject = JsonObject(emptyMap()),
) {
    var state by remember { mutableStateOf(initial) }
    fun update(s: RoutesTableModel.State) { state = s; onState(s) }
    val lightOf: (RoutesTableModel.Row) -> ProbeModel.Light = { ProbeModel.light(it, store.results[it.key]) }
    val shown by remember(rows) { derivedStateOf { RoutesTableModel.view(rows, state, lightOf) } }
    val scroll = rememberScrollState()
    val c = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var sheetRow by remember { mutableStateOf<RoutesTableModel.Row?>(null) }
    var exportMenu by remember { mutableStateOf(false) }
    val csvSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) saveAndShareCsv(ctx, uri, RoutesExport.csv(RoutesExport.grid(shown, lightOf)))
    }
    var detailRow by remember { mutableStateOf<RoutesTableModel.Row?>(null) }
    LaunchedEffect(rows, prober) { if (prober != null) store.run(rows, prober, force = false) }

    detailRow?.let { row ->
        BackHandler { detailRow = null }
        DeclarationPage(row, declarations[row.container] as? JsonObject, onBack = { detailRow = null })
        return
    }
    Column(Modifier.fillMaxSize().testTag(RoutesTags.SCREEN)) {
        // One vertical scroller: the filters, the sticky header and every row share the LazyColumn, so a short screen never starves the list.
        LazyColumn(Modifier.fillMaxSize().testTag(RoutesTags.LIST)) {
            item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                ChipRow("Light") {
                    ProbeModel.Light.entries.forEach { l ->
                        Chip(l.label, l.id in state.lights, RoutesTags.lightChip(l.id)) { update(RoutesTableModel.toggleLight(state, l.id)) }
                    }
                }
                ChipRow("Auth") {
                    RoutesTableModel.authOptions(rows).forEach { a ->
                        Chip(a.label, a.id in state.auth, RoutesTags.authChip(a.id)) { update(RoutesTableModel.toggleAuth(state, a.id)) }
                    }
                }
                ChipRow("VM") {
                    RoutesTableModel.vmOptions(rows).forEach { vm ->
                        Chip(vm, vm in state.vms, RoutesTags.vmChip(vm)) { update(RoutesTableModel.toggleVm(state, vm)) }
                    }
                }
                if (state.category.isNotEmpty()) ChipRow("Cat") {
                    Chip("${state.category}  \u00D7", true, RoutesTags.CATEGORY_CHIP) { update(state.copy(category = "")) }
                }
                SearchBox(state.query) { update(state.copy(query = it)) }
                Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        RoutesTableModel.countLine(shown.size, rows.size),
                        Modifier.weight(1f).testTag(RoutesTags.COUNT),
                        color = c.onSurfaceVariant, style = MaterialTheme.typography.labelMedium,
                    )
                    if (prober != null) Text(
                        if (store.running.value) "Checking\u2026" else "\u21BB Refresh",
                        Modifier.clickable(enabled = !store.running.value, role = Role.Button) {
                            scope.launch { store.run(rows, prober, force = true) }
                        }.padding(horizontal = 8.dp, vertical = 2.dp).testTag(RoutesTags.REFRESH),
                        color = c.primary, style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            }
            stickyHeader {
                Box(Modifier.fillMaxWidth().background(c.surfaceVariant)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(scroll)) {
                Col.entries.forEach { col ->
                    val active = state.sort == col
                    val mark = if (!active) "" else if (state.ascending) " \u25B2" else " \u25BC"
                    Text(
                        headers.getValue(col) + mark,
                        Modifier.width(widths.getValue(col)).height(30.dp)
                            .clickable(role = Role.Button) { update(RoutesTableModel.toggleSort(state, col)) }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .testTag(RoutesTags.header(col))
                            .semantics { selected = active },
                        color = if (active) c.primary else c.onSurface,
                        fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Text("More", Modifier.width(MORE_WIDTH + 28.dp).height(30.dp).padding(horizontal = 8.dp, vertical = 6.dp),
                    color = c.onSurface, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
                Box(Modifier.align(Alignment.CenterEnd).background(c.surfaceVariant)) {
                    Text(
                        "\u22EE",
                        Modifier.clickable(role = Role.Button) { exportMenu = true }.padding(horizontal = 10.dp, vertical = 2.dp)
                            .testTag(RoutesTags.EXPORT).semantics { contentDescription = "Export table" },
                        color = c.primary, style = MaterialTheme.typography.titleMedium,
                    )
                    DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                        DropdownMenuItem(text = { Text("Copy table") }, modifier = Modifier.testTag(RoutesTags.EXPORT_COPY),
                            onClick = { exportMenu = false; copyToClipboard(ctx, RoutesExport.tsv(RoutesExport.grid(shown, lightOf)), "Table copied") })
                        DropdownMenuItem(text = { Text("Export CSV\u2026") }, modifier = Modifier.testTag(RoutesTags.EXPORT_CSV),
                            onClick = { exportMenu = false; runCatching { csvSaver.launch("containers.csv") } })
                        DropdownMenuItem(text = { Text("Export Markdown") }, modifier = Modifier.testTag(RoutesTags.EXPORT_MD),
                            onClick = { exportMenu = false; shareText(ctx, RoutesExport.markdown(RoutesExport.grid(shown, lightOf)), "text/markdown") })
                    }
                }
                }
            }
            itemsIndexed(shown, key = { i, r -> "$i/${r.container}/${r.publicUrl}/${r.privateUrl}" }) { index, r ->
                Row(Modifier.fillMaxWidth().horizontalScroll(scroll).border(BorderStroke(0.5.dp, c.outline.copy(alpha = 0.25f))),
                    verticalAlignment = Alignment.CenterVertically) {
                    Col.entries.forEach { col ->
                        if (col == Col.STATUS) {
                            val light = lightOf(r)
                            Box(
                                Modifier.width(widths.getValue(col)).height(30.dp)
                                    .clickable(role = Role.Button) { sheetRow = r }
                                    .testTag(RoutesTags.light(index))
                                    .semantics { contentDescription = "Status ${light.id}" },
                                contentAlignment = Alignment.Center,
                            ) { Box(Modifier.size(14.dp).clip(CircleShape).background(lightColor(light))) }
                            return@forEach
                        }
                        val text = r.text(col)
                        val open = col == Col.PUBLIC_URL && text.isNotBlank()
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            Text(
                                text,
                                Modifier.width(widths.getValue(col)).padding(horizontal = 8.dp, vertical = 5.dp)
                                    .combinedClickable(
                                        role = Role.Button,
                                        onClick = { if (open) onOpenUrl("https://$text") },
                                        onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); menu = true },
                                    )
                                    .testTag(RoutesTags.cell(col)),
                                color = if (open) c.primary else c.onSurface,
                                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            val target = RoutesTableModel.openTarget(col, text)
                            fun doOpen() {
                                if (target is RoutesTableModel.OpenTarget.Category) update(state.copy(category = target.name))
                                else if (target != null) onOpen(target)
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Copy") }, enabled = text.isNotBlank(),
                                    modifier = Modifier.testTag(RoutesTags.MENU_COPY),
                                    onClick = { menu = false; copyToClipboard(ctx, text) })
                                DropdownMenuItem(text = { Text("Open") }, enabled = target != null,
                                    modifier = Modifier.testTag(RoutesTags.MENU_OPEN),
                                    onClick = { menu = false; doOpen() })
                                DropdownMenuItem(text = { Text("Copy & Open") }, enabled = target != null && text.isNotBlank(),
                                    modifier = Modifier.testTag(RoutesTags.MENU_COPY_OPEN),
                                    onClick = { menu = false; copyToClipboard(ctx, text); doOpen() })
                            }
                        }
                    }
                    Text("More", Modifier.width(MORE_WIDTH).clickable(role = Role.Button) { detailRow = r }
                        .padding(horizontal = 8.dp, vertical = 5.dp).testTag(RoutesTags.more(index)),
                        color = c.primary, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
            }
        }
    }
    sheetRow?.let { r ->
        LightSheet(r, store.results[r.key], lightOf(r), canRetest = prober != null,
            onRetest = { if (prober != null) scope.launch { store.retest(r, prober) } },
            onDismiss = { sheetRow = null })
    }
}

private val MORE_WIDTH = 72.dp

@Composable
private fun lightColor(l: ProbeModel.Light): Color = when (l) {
    ProbeModel.Light.GREEN -> Color(0xFF2E9E5B)
    ProbeModel.Light.YELLOW -> Color(0xFFE0A800)
    ProbeModel.Light.RED -> Color(0xFFD64545)
    ProbeModel.Light.GREY -> MaterialTheme.colorScheme.outline
}

private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

/** The breakdown under a row's light: each leg's DNS result, HTTP status, latency, error and checked-at time. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LightSheet(
    row: RoutesTableModel.Row, probe: ProbeModel.RowProbe?, light: ProbeModel.Light,
    canRetest: Boolean, onRetest: () -> Unit, onDismiss: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag(RoutesTags.SHEET)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).clip(CircleShape).background(lightColor(light)))
                Text("  ${row.container} \u00B7 ${light.label}", style = MaterialTheme.typography.titleMedium, color = c.onSurface)
            }
            Text(ProbeModel.summary(light, probe), Modifier.padding(top = 2.dp, bottom = 8.dp), color = c.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
            val legs = ProbeModel.legsOf(row)
            if (legs.isEmpty()) Text("No endpoint to check.", color = c.onSurface, style = MaterialTheme.typography.bodyMedium)
            legs.forEach { (leg, target) ->
                val r = probe?.legs?.get(leg)
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag(RoutesTags.leg(leg.id)).semantics(mergeDescendants = true) {}) {
                    Text("${leg.label}: ${r?.status?.name ?: "NOT CHECKED"}", fontWeight = FontWeight.SemiBold,
                        color = c.onSurface, style = MaterialTheme.typography.bodyMedium)
                    Text(target, color = c.primary, style = MaterialTheme.typography.bodySmall)
                    if (r != null) {
                        Text("DNS: ${r.dns.ifBlank { "n/a (address literal)" }}", color = c.onSurface, style = MaterialTheme.typography.bodySmall)
                        Text("HTTP: ${r.http?.toString() ?: "no reply"}   Latency: ${r.latencyMs?.let { "$it ms" } ?: "n/a"}",
                            color = c.onSurface, style = MaterialTheme.typography.bodySmall)
                        if (r.error.isNotBlank()) Text("Error: ${r.error}", color = c.onSurface, style = MaterialTheme.typography.bodySmall)
                        Text("Checked: ${timeFmt.format(Date(r.checkedAt))} (${r.source})", color = c.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (probe?.containerDown == true) Text("Fleet API: container not running", color = c.error, style = MaterialTheme.typography.bodySmall)
            Text("Re-test", Modifier.padding(vertical = 10.dp).clickable(enabled = canRetest, role = Role.Button) { onRetest() }
                .testTag(RoutesTags.RETEST), color = if (canRetest) c.primary else c.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** The More page: the container's whole declaration in readable sections, a raw-JSON toggle and copy. */
@Composable
fun DeclarationPage(row: RoutesTableModel.Row, decl: JsonObject?, onBack: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    var raw by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(c.surface).testTag(RoutesTags.DECL_PAGE)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("\u2190", Modifier.clickable(role = Role.Button) { onBack() }.padding(horizontal = 10.dp, vertical = 6.dp).testTag(RoutesTags.DECL_BACK),
                color = c.primary, style = MaterialTheme.typography.titleLarge)
            Text(row.container, Modifier.weight(1f), color = c.onSurface, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (raw) "Readable" else "Raw JSON", Modifier.clickable(role = Role.Button) { raw = !raw }.padding(8.dp).testTag(RoutesTags.DECL_RAW),
                color = c.primary, style = MaterialTheme.typography.labelLarge)
            if (decl != null) Text("Copy", Modifier.clickable(role = Role.Button) { copyToClipboard(ctx, DeclarationModel.raw(decl)) }.padding(8.dp)
                .testTag(RoutesTags.DECL_COPY), color = c.primary, style = MaterialTheme.typography.labelLarge)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            // #1148 every metadata field of the declared container (group, category, image, host ... ), from the same document as the table.
            if (row.meta.isNotEmpty()) {
                Text("Metadata", Modifier.padding(top = 4.dp, bottom = 4.dp).testTag(RoutesTags.DECL_META), color = c.primary,
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                row.meta.forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).testTag("routes_meta_$k")) {
                        Text(k, Modifier.width(120.dp), color = c.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        Text(v.ifBlank { "-" }, Modifier.weight(1f), color = c.onSurface, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            when {
                decl == null -> Text("No declaration is carried for ${row.container}.", color = c.onSurface, style = MaterialTheme.typography.bodyMedium)
                raw -> Text(DeclarationModel.raw(decl), Modifier.testTag("routes_decl_rawtext"), color = c.onSurface,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                else -> DeclarationModel.sections(decl).forEach { sec ->
                    Text(sec.title, Modifier.padding(top = 12.dp, bottom = 4.dp), color = c.primary, style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold)
                    sec.lines.forEach { (k, v) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(k, Modifier.width(120.dp), color = c.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            Text(v, Modifier.weight(1f), color = c.onSurface, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

/** Copy [text] to the system clipboard and say so. */
private fun copyToClipboard(ctx: Context, text: String, toast: String = "Copied") {
    runCatching {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("c3", text))
        Toast.makeText(ctx, toast, Toast.LENGTH_SHORT).show()
    }
}

/** Share [text] through the system share sheet. */
private fun shareText(ctx: Context, text: String, mime: String) {
    runCatching {
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, "Containers table")
        ctx.startActivity(Intent.createChooser(send, "Share table").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Write [csv] to the document the user picked, say so, and offer the share sheet for it. */
private fun saveAndShareCsv(ctx: Context, uri: Uri, csv: String) {
    val ok = runCatching { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(csv.toByteArray(Charsets.UTF_8)) } }.isSuccess
    Toast.makeText(ctx, if (ok) "CSV saved" else "Could not save CSV", Toast.LENGTH_SHORT).show()
    if (!ok) return
    runCatching {
        val send = Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "Share CSV").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Free-text search under the filters: live (debounced), with a clear button. [onQuery] gets the settled text. */
@Composable
private fun SearchBox(query: String, onQuery: (String) -> Unit) {
    val c = MaterialTheme.colorScheme
    var text by remember { mutableStateOf(query) }
    val latest by rememberUpdatedState(onQuery)
    LaunchedEffect(text) {
        if (text != query) { delay(SEARCH_DEBOUNCE_MS); latest(text) }
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(8.dp))
            .border(BorderStroke(1.dp, c.outline.copy(alpha = 0.4f)), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            if (text.isEmpty()) Text("Search all columns", color = c.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            BasicTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(color = c.onSurface),
                cursorBrush = SolidColor(c.primary),
                modifier = Modifier.fillMaxWidth().testTag(RoutesTags.SEARCH),
            )
        }
        if (text.isNotEmpty()) {
            Text(
                "\u00D7",
                Modifier.clickable(role = Role.Button) { text = ""; latest("") }.padding(horizontal = 8.dp)
                    .testTag(RoutesTags.SEARCH_CLEAR).semantics { contentDescription = "Clear search" },
                color = c.onSurface, style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

private const val SEARCH_DEBOUNCE_MS = 200L

@Composable
private fun ChipRow(label: String, chips: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(36.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        chips()
    }
}

/** The page-wide Filters row of the Containers section: All, then one chip per declared kind (data/container_projects.json). Drawn once above the project tabs; every project's Topology and Table read the selection. */
@Composable
fun KindBar(kinds: List<ContainerProjects.Kind>, selected: String?, onSelect: (String?) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp).testTag("filters"), verticalAlignment = Alignment.CenterVertically) {
        Text("Filters", Modifier.padding(end = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        Chip("All", selected == null, "kind_all") { onSelect(null) }
        kinds.forEach { k -> Chip(k.label, selected == k.id, "kind_${k.id}") { onSelect(k.id) } }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Box(
        Modifier.padding(end = 6.dp).clip(RoundedCornerShape(12.dp))
            .background(if (selected) c.primary.copy(alpha = 0.22f) else c.surfaceVariant.copy(alpha = 0.5f))
            .border(BorderStroke(1.dp, if (selected) c.primary else c.outline.copy(alpha = 0.4f)), RoundedCornerShape(12.dp))
            .clickable(role = Role.Checkbox, onClick = onClick)
            .testTag(tag).semantics { this.selected = selected; contentDescription = label },
    ) {
        Text(label, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = if (selected) c.primary else c.onSurface,
            style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

/** The Table page as a tab fragment: rows from the baked slice, state in SharedPreferences, a public URL opens in the browser. */
class RoutesTableFragment : KitComposeFragment() {

    /** The Containers sub-page's project (ContainerProjects); an id the declaration does not know is All. */
    private val project: String? get() = arguments?.getString(ARG_PROJECT)?.takeIf { Declarations.containerProjects.project(it) != null }

    override fun palette(): KitPalette = c3KitPalette(requireContext())

    @Composable
    override fun Content() {
        val ctx = LocalContext.current
        var container by remember { mutableStateOf<String?>(null) }
        val store = remember { PrefsRoutesStateStore(ctx) }
        // The SAME document the Topology page reads (ContainersSource); the API copy replaces the baked one when it answers.
        val version = ContainersSource.version
        LaunchedEffect(Unit) { ContainersSource.refresh(ApiFleetLive(ctx.applicationContext)) }
        val initial = remember { store.load() }
        val kind = ContainerFilter.kind
        RoutesTableScreen(
            rows = remember(version, project, kind) { ContainerProjects.rows(Declarations.containerProjects, ContainersSource.current(), project, kind) },
            initial = initial,
            onState = store::save,
            prober = remember { NetProber(ctx) },
            store = RoutesProbeStore.shared,
            declarations = remember { Declarations.declarations },
            onOpenUrl = { (activity as? C3HealthFragment.UrlClickListener)?.onUrlClicked(it) },
            onOpen = { t ->
                when (t) {
                    is RoutesTableModel.OpenTarget.Url -> (activity as? C3HealthFragment.UrlClickListener)?.onUrlClicked(t.url)
                    is RoutesTableModel.OpenTarget.Container -> container = t.name
                    // The VM's place is the Infra page VMs; C3 has no page per VM.
                    is RoutesTableModel.OpenTarget.Vm -> (activity as? C3StackFragment.TargetListener)?.onTargetClicked("page:c3/${InfraSections.MACHINES}")
                    is RoutesTableModel.OpenTarget.Category -> Unit // handled inside the screen: it sets the category filter
                }
            },
        )
        container?.let { name ->
            ContainerSheet.Sheet(
                target = ContainerSheet.Target(name, name, ""),
                onOpenUrl = { url -> (activity as? C3HealthFragment.UrlClickListener)?.onUrlClicked(url) },
                onDismiss = { container = null },
            )
        }
    }

    companion object {
        private const val ARG_PROJECT = "project"
        fun newInstance(project: String? = null) = RoutesTableFragment().apply {
            arguments = Bundle().apply { project?.let { putString(ARG_PROJECT, it) } }
        }
    }
}
