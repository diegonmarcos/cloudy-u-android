package com.diegonmarcos.cloudme

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.cloudme.health.HCard
import com.diegonmarcos.cloudme.health.HHead
import com.diegonmarcos.cloudme.health.HRow
import com.diegonmarcos.cloudme.health.HText
import com.diegonmarcos.cloudme.health.Pill
import com.diegonmarcos.cloudme.health.accent
import com.diegonmarcos.cloudme.health.dim
import org.json.JSONArray
import java.io.File
import kotlin.math.max
import kotlin.math.sqrt

object InventoryTags {
    const val STORAGE = "me:inv3d:storage"
    const val APARTMENT = "me:inv3d:apartment"
    const val DETAIL = "me:inv3d:detail"
    fun thumb(collection: String, id: String) = "me:inv:thumb:$collection:$id"
}

/** #1861 Where the inventory pages come from (the declared ledgers of every inventory page) and where photos live. */
object InventoryViewsData {
    /** Every inventory page's ledger definitions, read from the bundled the inventory page files in data/ui. */
    fun defs(ctx: Context): List<LedgerDef> = runCatching {
        (ctx.assets.list("inventory") ?: emptyArray()).filter { it.endsWith(".json") }.sorted().flatMap { f ->
            val a = JSONArray(ctx.assets.open("inventory/$f").bufferedReader().use { it.readText() })
            (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("kind") == "ledger" }.flatMap { blk ->
                val l = blk.optJSONArray("ledgers") ?: JSONArray()
                (0 until l.length()).map { LedgerDef.parse(l.getJSONObject(it)) }
            }
        }
    }.getOrDefault(emptyList())

    /** Copies the picked image into the app's own files dir and returns its path, or null when it cannot be read. */
    fun copyPhoto(ctx: Context, uri: Uri): String? = runCatching {
        val dir = File(ctx.filesDir, "inventory_photos").apply { mkdirs() }
        val out = File(dir, "p${System.currentTimeMillis()}.img")
        ctx.contentResolver.openInputStream(uri)!!.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
        out.absolutePath
    }.getOrNull()
}

/** The left-hand image of an item row: its photo when it has one, else the category glyph. */
@Composable
internal fun ItemThumb(collection: String, r: Rec, sizeDp: Int = 44) {
    val path = r["photo"].orEmpty()
    val bmp = remember(path) {
        if (path.isBlank()) null else runCatching { BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 4 })?.asImageBitmap() }.getOrNull()
    }
    Box(Modifier.padding(end = 10.dp).size(sizeDp.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x22888888))
        .testTag(InventoryTags.thumb(collection, r["id"].orEmpty())), contentAlignment = Alignment.Center) {
        if (bmp != null) Image(bmp, null, Modifier.size(sizeDp.dp), contentScale = ContentScale.Crop)
        else Text(InventoryLayout.icon(collection, r["category"].orEmpty()), fontSize = (sizeDp / 2).sp)
    }
}

/** The photo field of the add and edit form: pick one image, keep a copy in the app, show it, remove it. */
@Composable
internal fun PhotoField(label: String, path: String, category: String, collection: String, onChange: (String) -> Unit) {
    val ctx = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { InventoryViewsData.copyPhoto(ctx, it) }?.let(onChange)
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        ItemThumb(collection, mapOf("photo" to path, "category" to category, "id" to "form"))
        Pill(if (path.isBlank()) "Pick $label" else "Change $label", false) { pick.launch(arrayOf("image/*")) }
        if (path.isNotBlank()) Pill("Remove $label", false) { onChange("") }
    }
}

/** Storage 3D and Apartment 3D below the Inventory tables, over every inventory collection of the store. */
@Composable
internal fun InventoryViews(defs: List<LedgerDef>, data: LedgerData, currency: String, onEdit: (String, String, Rec) -> Unit) {
    val rows = remember(data, defs) { defs.associate { it.collection to data.rows(it.collection) } }
    val storage = remember(rows) { InventoryLayout.storage(rows) }
    val apartment = remember(rows) { InventoryLayout.apartment(rows) }
    var selected by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }

    HCard(Modifier.testTag(InventoryTags.STORAGE)) {
        HHead("Storage 3D")
        HText("Every sized item of every inventory ledger as a box at its size: desk (Electronics), garment racks (cabides e cavaletes) for Clothing, floor bays for bikes, transport and large leisure, shelves for the rest, Others as the fallback. Drag to rotate, pinch to zoom, tap an item for its record.")
        HRow("Items drawn", storage.count.toString())
        if (storage.unsized > 0) HRow("Without a size", storage.unsized.toString())
        HRow("Items volume", "%.2f m³".format(storage.itemVolumeM3))
        HRow("Storage volume needed", "%.2f m³".format(storage.storageVolumeM3))
        HRow("Footprint needed", "%.2f m² (room %.2f m²)".format(storage.footprintM2, storage.roomFootprintM2))
        HRow("Weight", "%.1f kg".format(storage.weightKg))
        storage.zones.forEach { z -> HRow(z.zone.label, "${z.boxes.size} items, ${z.w} x ${z.d} cm") }
        Scene3D(remember(storage) { InventoryLayout.storageScene(storage) }, selected, 0xFF1B1F27) { selected = it; editing = false }
    }
    HCard(Modifier.testTag(InventoryTags.APARTMENT)) {
        HHead("Apartment 3D")
        HText("Blueprint of the apartment: the living room in the centre, a room on each side and the entry door at the bottom. Housing and household items stand in their rooms. Drag to rotate, pinch to zoom, tap an item for its record.")
        HRow("Items placed", apartment.count.toString())
        HRow("Apartment", "%.1f x %.1f m".format(InventoryLayout.APARTMENT_W / 100.0, InventoryLayout.APARTMENT_D / 100.0))
        if (apartment.overflow.isNotEmpty()) HRow("Too big for the room", apartment.overflow.size.toString())
        apartment.rooms.forEach { r -> HRow(r.room.id.label, "${r.boxes.size} items") }
        Scene3D(remember(apartment) { InventoryLayout.apartmentScene(apartment) }, selected, 0xFF0B2545) { selected = it; editing = false }
    }
    val sel = selected
    if (sel != null) {
        val c = sel.substringBefore(':'); val id = sel.substringAfter(':')
        val def = defs.firstOrNull { it.collection == c }
        val rec = rows[c].orEmpty().firstOrNull { it["id"] == id }
        if (def != null && rec != null) HCard(Modifier.testTag(InventoryTags.DETAIL)) {
            HHead("Record: ${rec["name"].orEmpty()}")
            if (editing) AddForm(def, rec, onCancel = { editing = false }, onSave = { onEdit(c, id, it); editing = false })
            else {
                Row { ItemThumb(c, rec, 64); androidx.compose.foundation.layout.Column { RecLines(def, rec, currency) } }
                Row {
                    Pill("Edit", true, accent()) { editing = true }
                    Pill("Close", false) { selected = null }
                }
            }
        }
    }
}

private fun shade(c: Long, f: Float): Color { val k = Color(c); return Color(k.red * f, k.green * f, k.blue * f, 1f) }

/** One orbit canvas: one finger drags to rotate (yaw and tilt), two pinch to zoom, a tap picks the nearest item. */
@Composable
private fun Scene3D(boxes: List<SceneBox>, selected: String?, background: Long, onPick: (String?) -> Unit) {
    var yaw by remember { mutableStateOf(45.0) }
    var pitch by remember { mutableStateOf(35.264) }
    var zoom by remember { mutableStateOf(1.0) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val bounds = remember(boxes) {
        val cs = boxes.flatMap { Orbit.corners(it) }
        val mx = (cs.minOf { it.first } + cs.maxOf { it.first }) / 2; val my = (cs.minOf { it.second } + cs.maxOf { it.second }) / 2
        val mz = (cs.minOf { it.third } + cs.maxOf { it.third }) / 2
        val r = cs.maxOf { sqrt((it.first - mx) * (it.first - mx) + (it.second - my) * (it.second - my) + (it.third - mz) * (it.third - mz)) }
        doubleArrayOf(mx, my, mz, max(r, 1.0))
    }
    fun cam() = Camera(yaw, pitch, zoom * 0.45 * minOf(size.width, size.height) / bounds[3], size.width / 2.0, size.height / 2.0, bounds[0], bounds[1], bounds[2])
    Canvas(Modifier.fillMaxWidth().padding(top = 10.dp).height(320.dp).clip(RoundedCornerShape(8.dp)).background(Color(background))
        .onSizeChanged { size = it }
        .pointerInput(Unit) { detectTransformGestures { _, pan, z, _ -> yaw += pan.x * 0.5; pitch = (pitch + pan.y * 0.4).coerceIn(5.0, 89.0); zoom = (zoom * z).coerceIn(0.3, 6.0) } }
        .pointerInput(boxes, size) { detectTapGestures { o -> onPick(Orbit.pick(boxes, cam(), o.x.toDouble(), o.y.toDouble())) } }) {
        val cam = cam()
        fun pt(p: Triple<Double, Double, Double>) = Offset(p.first.toFloat(), p.second.toFloat())
        val (wire, solid) = Orbit.sorted(boxes, cam).partition { it.wire }
        for (b in wire) {
            val p = Orbit.corners(b).map { pt(cam.project(it.first, it.second, it.third)) }
            val col = shade(b.color, 1f).copy(alpha = 0.7f)
            for (e in listOf(0 to 1, 2 to 3, 4 to 5, 6 to 7, 0 to 2, 1 to 3, 4 to 6, 5 to 7, 0 to 4, 1 to 5, 2 to 6, 3 to 7)) drawLine(col, p[e.first], p[e.second], 1.5f)
        }
        for (b in solid) {
            val p = Orbit.corners(b).map { pt(cam.project(it.first, it.second, it.third)) }
            for (f in Orbit.visible(cam)) {
                val n = Orbit.FACES.first { it.first.contentEquals(f) }.second
                val path = Path().apply { moveTo(p[f[0]].x, p[f[0]].y); for (i in 1 until f.size) lineTo(p[f[i]].x, p[f[i]].y); close() }
                drawPath(path, shade(b.color, if (n.second > 0) 1f else if (n.first != 0.0) 0.7f else 0.5f))
                drawPath(path, if (b.id == selected) Color.White else Color(0x66000000), style = Stroke(if (b.id == selected) 3f else 1f))
            }
        }
    }
}
