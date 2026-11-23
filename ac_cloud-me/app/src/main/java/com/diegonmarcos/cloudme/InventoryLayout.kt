package com.diegonmarcos.cloudme

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.ceil

/**
 * #1861 Inventory 3D, the pure half (InventoryLayoutTest, test/test-inventory-3d.sh): the size of every item, the Storage
 * room packing, the Apartment blueprint and the orbit projection. No Android, no Compose, no JSON: [Rec] maps in, boxes
 * out, so the tests run on the plain JVM. All lengths are whole centimetres, y is up, x is the width, z is the depth.
 */
data class InvItem(
    val id: String, val collection: String, val rid: String, val name: String, val category: String,
    val wCm: Int, val dCm: Int, val hCm: Int, val weightKg: Double, val photo: String,
) {
    val volumeCm3: Long get() = wCm.toLong() * dCm * hCm
}

/** One placed box, [x]/[y]/[z] local to its zone or room, with its own [w]/[d]/[h] (the item, turned when the room needs it). */
data class PlacedBox(val item: InvItem, val x: Int, val y: Int, val z: Int, val w: Int, val d: Int, val h: Int)

enum class Zone(val label: String, val baseW: Int) {
    DESK("Desk (Electronics)", 160),
    RACK("Garment racks (cabides e cavaletes)", 200),
    FLOOR("Floor bays (bike, transport, leisure)", 400),
    SHELF("Shelves", 240),
    OTHERS("Others", 160),
}

data class ZonePlan(val zone: Zone, val ox: Int, val oz: Int, val w: Int, val d: Int, val h: Int, val boxes: List<PlacedBox>)

data class StorageLayout(
    val zones: List<ZonePlan>, val roomW: Int, val roomD: Int,
    /** Items without a complete width, depth and height: listed in the ledger but not drawn. */
    val unsized: Int,
) {
    val itemVolumeM3: Double get() = zones.sumOf { z -> z.boxes.sumOf { it.item.volumeCm3 } } / 1_000_000.0
    val storageVolumeM3: Double get() = zones.sumOf { it.w.toLong() * it.d * it.h } / 1_000_000.0
    val footprintM2: Double get() = zones.sumOf { it.w.toLong() * it.d } / 10_000.0
    val roomFootprintM2: Double get() = roomW.toLong() * roomD / 10_000.0
    val weightKg: Double get() = zones.sumOf { z -> z.boxes.sumOf { it.item.weightKg } }
    val count: Int get() = zones.sumOf { it.boxes.size }
}

enum class RoomId(val label: String) { LEFT("Left room"), LIVING("Living room"), RIGHT("Right room"), ENTRY("Entry") }

data class Room(val id: RoomId, val x: Int, val z: Int, val w: Int, val d: Int)

/** The entry door: a gap of [w] in the BOTTOM wall (z = the apartment depth), starting at [x]. */
data class Door(val x: Int, val w: Int, val z: Int)

data class RoomPlan(val room: Room, val boxes: List<PlacedBox>)

data class ApartmentLayout(val rooms: List<RoomPlan>, val door: Door, val overflow: List<String>) {
    val count: Int get() = rooms.sumOf { it.boxes.size }
}

/** A box of a drawn scene in world centimetres. [wire] draws edges only (walls, blueprint style); [id] is blank when it cannot be tapped. */
data class SceneBox(val id: String, val label: String, val color: Long, val x: Int, val y: Int, val z: Int, val w: Int, val d: Int, val h: Int, val wire: Boolean = false)

object InventoryLayout {
    const val GAP = 2
    const val DESK_TOP = 75
    const val RAIL = 190
    const val RACK_H = 200
    const val SHELF_DEPTH = 60
    const val ZONE_GAP = 30
    const val WALL = 10
    const val WALL_H = 250
    const val LARGE_LEISURE_CM = 60

    /** The `inv_*` collections that the Storage room knows by name; everything else falls to [Zone.OTHERS]. */
    private val SHELF_LEDGERS = setOf("inv_household", "inv_housing", "inv_care", "inv_food", "inv_education")

    private fun dim(v: String?): Int? =
        v?.trim()?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it > 0 }?.let { ceil(it).toInt().coerceAtLeast(1) }

    /** Sized items of every collection, plus how many records have no complete size. Keys are collections, order kept. */
    fun items(rows: Map<String, List<Rec>>): Pair<List<InvItem>, Int> {
        var unsized = 0
        val out = ArrayList<InvItem>()
        for ((c, rs) in rows) {
            if (!c.startsWith("inv_")) continue
            for (r in rs) {
                val w = dim(r["width_cm"]); val d = dim(r["depth_cm"]); val h = dim(r["height_cm"])
                val rid = r["id"].orEmpty()
                if (w == null || d == null || h == null || rid.isBlank()) { unsized++; continue }
                out += InvItem("$c:$rid", c, rid, r["name"].orEmpty(), r["category"].orEmpty(), w, d, h,
                    r["weight_kg"]?.trim()?.replace(',', '.')?.toDoubleOrNull() ?: 0.0, r["photo"].orEmpty())
            }
        }
        return out to unsized
    }

    /** Storage zone of one item, by ledger first, then by size. Unknown ledgers land in Others. */
    fun zoneFor(i: InvItem): Zone = when (i.collection) {
        "inv_electronics" -> Zone.DESK
        "inv_clothing" -> Zone.RACK
        "inv_transport" -> Zone.FLOOR
        "inv_leisure" -> if (max(i.wCm, max(i.dCm, i.hCm)) >= LARGE_LEISURE_CM) Zone.FLOOR else Zone.SHELF
        in SHELF_LEDGERS -> Zone.SHELF
        else -> Zone.OTHERS
    }

    internal class Packed(val boxes: List<PlacedBox>, val depth: Int, val height: Int)

    /** Row packer: widest rows first fill [w], rows grow in depth; with [layerDepth] a full layer stacks the next one on top (shelf levels). */
    internal fun pack(items: List<InvItem>, w: Int, layerDepth: Int?, turn: Boolean = false): Packed {
        val src = items.map { i ->
            if (turn && i.wCm > w && i.dCm <= w) PlacedBox(i, 0, 0, 0, i.dCm, i.wCm, i.hCm) else PlacedBox(i, 0, 0, 0, i.wCm, i.dCm, i.hCm)
        }.sortedWith(compareByDescending<PlacedBox> { it.d }.thenByDescending { it.w }.thenBy { it.item.id })
        val limit = layerDepth?.let { max(it, src.maxOfOrNull { b -> b.d } ?: 0) }
        var x = 0; var y = 0; var z = 0; var rowD = 0; var layerH = 0; var depth = 0; var height = 0
        val out = ArrayList<PlacedBox>()
        for (b in src) {
            if (x > 0 && x + b.w > w) { z += rowD + GAP; x = 0; rowD = 0 }
            if (limit != null && z > 0 && z + b.d > limit) { y += layerH + GAP; z = 0; x = 0; rowD = 0; layerH = 0 }
            out += b.copy(x = x, y = y, z = z)
            x += b.w + GAP; rowD = max(rowD, b.d); layerH = max(layerH, b.h)
            depth = max(depth, z + b.d); height = max(height, y + b.h)
        }
        return Packed(out, depth, height)
    }

    /** Every sized item placed once in its zone; zones in two rows (desk, racks, shelves, others / floor bays). */
    fun storage(rows: Map<String, List<Rec>>): StorageLayout {
        val (items, unsized) = items(rows)
        val by = items.groupBy { zoneFor(it) }
        val plans = Zone.values().map { zone ->
            val its = by[zone].orEmpty()
            val w = max(zone.baseW, its.maxOfOrNull { it.wCm } ?: 0)
            val p = pack(its, w, if (zone == Zone.SHELF) SHELF_DEPTH else null)
            val d = max(SHELF_DEPTH, p.depth)
            val boxes = p.boxes.map { b ->
                when (zone) {
                    Zone.DESK -> b.copy(y = b.y + DESK_TOP)
                    Zone.RACK -> b.copy(y = (RAIL - b.h).coerceAtLeast(0))
                    else -> b
                }
            }
            val h = max(boxes.maxOfOrNull { it.y + it.h } ?: 0, when (zone) { Zone.DESK -> DESK_TOP; Zone.RACK -> RACK_H; else -> 40 })
            ZonePlan(zone, 0, 0, w, d, h, boxes)
        }
        val top = plans.filter { it.zone != Zone.FLOOR }
        var x = 0
        val placed = top.map { zp -> zp.copy(ox = x).also { x += zp.w + ZONE_GAP } }
        val rowW = max(0, x - ZONE_GAP)
        val rowD = placed.maxOfOrNull { it.d } ?: 0
        val floor = plans.first { it.zone == Zone.FLOOR }.copy(ox = 0, oz = rowD + ZONE_GAP)
        val all = (placed + floor).sortedBy { it.zone.ordinal }
        return StorageLayout(all, max(rowW, floor.w), floor.oz + floor.d, unsized)
    }

    val APARTMENT_W = 1000
    val APARTMENT_D = 800
    val ROOMS = listOf(
        Room(RoomId.LEFT, 0, 0, 300, 500), Room(RoomId.LIVING, 300, 0, 400, 500),
        Room(RoomId.RIGHT, 700, 0, 300, 500), Room(RoomId.ENTRY, 300, 500, 400, 300),
    )
    val DOOR = Door(450, 100, APARTMENT_D)

    /** Room of a household or housing item: beds to the left room, kitchen and appliances to the right, tools to the entry, the rest to the living room. */
    fun roomFor(i: InvItem): RoomId {
        val n = i.name.lowercase()
        return when {
            "bed" in n || "mattress" in n || "wardrobe" in n -> RoomId.LEFT
            i.category in setOf("Kitchen", "Appliance", "Fixtures") -> RoomId.RIGHT
            i.category == "Tools" -> RoomId.ENTRY
            else -> RoomId.LIVING
        }
    }

    /** Housing and household items placed once each, inside their room's walls; items too big for the room are reported in [ApartmentLayout.overflow]. */
    fun apartment(rows: Map<String, List<Rec>>): ApartmentLayout {
        val (items, _) = items(rows.filterKeys { it == "inv_housing" || it == "inv_household" })
        val by = items.groupBy { roomFor(it) }
        val over = ArrayList<String>()
        val plans = ROOMS.map { room ->
            val iw = room.w - 2 * WALL; val id = room.d - 2 * WALL
            val p = pack(by[room.id].orEmpty(), iw, id, turn = true)
            p.boxes.forEach { b -> if (b.x + b.w > iw || b.z + b.d > id) over += b.item.id }
            RoomPlan(room, p.boxes)
        }
        return ApartmentLayout(plans, DOOR, over)
    }

    private val ZONE_COLOR = mapOf(Zone.DESK to 0xFF5B8DEFL, Zone.RACK to 0xFFB05BEFL, Zone.FLOOR to 0xFF3DBE8BL, Zone.SHELF to 0xFFEFA83DL, Zone.OTHERS to 0xFF9AA4B2L)
    private val ROOM_COLOR = mapOf(RoomId.LEFT to 0xFFB05BEFL, RoomId.LIVING to 0xFF5B8DEFL, RoomId.RIGHT to 0xFFEFA83DL, RoomId.ENTRY to 0xFF3DBE8BL)

    /** The Storage room as drawable boxes: each zone's floor slab (wire), its posts, and every item (tappable by its id). */
    fun storageScene(s: StorageLayout): List<SceneBox> = buildList {
        for (zp in s.zones) {
            val c = ZONE_COLOR.getValue(zp.zone)
            add(SceneBox("", zp.zone.label, c, zp.ox, 0, zp.oz, zp.w, zp.d, 1, wire = true))
            when (zp.zone) {
                Zone.DESK -> add(SceneBox("", "Desk top", c, zp.ox, DESK_TOP - 3, zp.oz, zp.w, zp.d, 3))
                Zone.RACK -> add(SceneBox("", "Rail", c, zp.ox, RAIL, zp.oz + zp.d / 2, zp.w, 2, 2))
                Zone.SHELF -> add(SceneBox("", "Shelf frame", c, zp.ox, 0, zp.oz, zp.w, zp.d, zp.h, wire = true))
                else -> {}
            }
            for (b in zp.boxes) add(SceneBox(b.item.id, b.item.name, c, zp.ox + b.x, b.y, zp.oz + b.z, b.w, b.d, b.h))
        }
    }

    /** The Apartment blueprint: room floors and walls as wire boxes, the door as a gap marker, and the items. */
    fun apartmentScene(a: ApartmentLayout): List<SceneBox> = buildList {
        add(SceneBox("", "Apartment", 0xFF6FA8DCL, 0, 0, 0, APARTMENT_W, APARTMENT_D, 1, wire = true))
        for (rp in a.rooms) {
            val r = rp.room; val c = ROOM_COLOR.getValue(r.id)
            add(SceneBox("", r.id.label, c, r.x, 0, r.z, r.w, r.d, WALL_H, wire = true))
            for (b in rp.boxes) add(SceneBox(b.item.id, b.item.name, c, r.x + WALL + b.x, b.y, r.z + WALL + b.z, b.w, b.d, b.h))
        }
        add(SceneBox("", "Door", 0xFFFFFFFFL, a.door.x, 0, a.door.z - WALL, a.door.w, WALL, 210, wire = true))
    }

    /** Placeholder glyph for an item without a photo, by ledger and category. */
    fun icon(collection: String, category: String): String = when (collection) {
        "inv_electronics" -> when (category) { "Phone" -> "📱"; "Display" -> "🖥"; "OrbitCamera" -> "📷"; "Drone" -> "🚁"; "Audio" -> "🎧"; "Storage" -> "💾"; else -> "💻" }
        "inv_clothing" -> "👕"
        "inv_leisure" -> "🎽"
        "inv_housing" -> "🏠"
        "inv_household" -> "🛋"
        "inv_transport" -> when (category) { "Motorcycle" -> "🏍"; "Campervan" -> "🚐"; "Boat", "Berth" -> "⛵"; else -> "🚲" }
        else -> "📦"
    }
}

/** Orbit camera: [yawDeg] turns the scene about the vertical axis, [pitchDeg] tilts it (35.26 with yaw 45 is the isometric view). */
data class OrbitCamera(val yawDeg: Double = 45.0, val pitchDeg: Double = 35.264, val zoom: Double = 1.0, val cx: Double = 0.0, val cy: Double = 0.0,
                  val ox: Double = 0.0, val oy: Double = 0.0, val oz: Double = 0.0) {
    private val yaw get() = yawDeg * PI / 180
    private val pitch get() = pitchDeg * PI / 180

    /** Screen x, screen y (down) and nearness (larger is closer to the viewer) of a world point. */
    fun project(x: Double, y: Double, z: Double): Triple<Double, Double, Double> {
        val dx = x - ox; val dy = y - oy; val dz = z - oz
        val x1 = dx * cos(yaw) - dz * sin(yaw)
        val z1 = dx * sin(yaw) + dz * cos(yaw)
        return Triple(cx + zoom * x1, cy + zoom * (z1 * sin(pitch) - dy * cos(pitch)), z1 * cos(pitch) + dy * sin(pitch))
    }

    /** Nearness of the unit [n] after the same turn: a face is seen when it is positive. */
    fun facing(nx: Double, ny: Double, nz: Double): Double {
        val z1 = nx * sin(yaw) + nz * cos(yaw)
        return z1 * cos(pitch) + ny * sin(pitch)
    }
}

object Orbit {
    /** The 8 corners of a box, index = x + 2*y + 4*z of the low/high bit. */
    fun corners(b: SceneBox): List<Triple<Double, Double, Double>> = (0 until 8).map { i ->
        Triple((b.x + (i and 1) * b.w).toDouble(), (b.y + ((i shr 1) and 1) * b.h).toDouble(), (b.z + ((i shr 2) and 1) * b.d).toDouble())
    }

    /** Faces as corner indices with their outward normal: +y top, -x/+x, -z/+z sides (the bottom is never seen from above). */
    val FACES: List<Pair<IntArray, Triple<Double, Double, Double>>> = listOf(
        intArrayOf(2, 3, 7, 6) to Triple(0.0, 1.0, 0.0),
        intArrayOf(0, 2, 6, 4) to Triple(-1.0, 0.0, 0.0),
        intArrayOf(1, 3, 7, 5) to Triple(1.0, 0.0, 0.0),
        intArrayOf(0, 1, 3, 2) to Triple(0.0, 0.0, -1.0),
        intArrayOf(4, 5, 7, 6) to Triple(0.0, 0.0, 1.0),
    )

    /** The faces of [b] that face the viewer. */
    fun visible(cam: OrbitCamera): List<IntArray> = FACES.filter { (_, n) -> cam.facing(n.first, n.second, n.third) > 0 }.map { it.first }

    fun inPolygon(px: Double, py: Double, poly: List<Pair<Double, Double>>): Boolean {
        var inside = false; var j = poly.size - 1
        for (i in poly.indices) {
            val (xi, yi) = poly[i]; val (xj, yj) = poly[j]
            if ((yi > py) != (yj > py) && px < (xj - xi) * (py - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /** Id of the nearest tappable box under the screen point, or null. */
    fun pick(boxes: List<SceneBox>, cam: OrbitCamera, px: Double, py: Double): String? {
        var best: String? = null; var bestNear = Double.NEGATIVE_INFINITY
        for (b in boxes) {
            if (b.id.isBlank()) continue
            val pts = corners(b).map { cam.project(it.first, it.second, it.third) }
            for (f in visible(cam)) {
                if (inPolygon(px, py, f.map { pts[it].first to pts[it].second })) {
                    val near = pts.maxOf { it.third }
                    if (near > bestNear) { bestNear = near; best = b.id }
                }
            }
        }
        return best
    }

    /** Boxes far to near, so a painter draws the near ones over the far ones. */
    fun sorted(boxes: List<SceneBox>, cam: OrbitCamera): List<SceneBox> = boxes.sortedBy { b ->
        cam.project(b.x + b.w / 2.0, b.y + b.h / 2.0, b.z + b.d / 2.0).third
    }
}
