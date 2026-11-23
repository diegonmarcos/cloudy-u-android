package com.diegonmarcos.cloudme

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1861 Inventory 3D, pure layout logic (Robolectric only supplies org.json for the declared data): every sized item is placed exactly once, boxes stay inside their zone and never
 * overlap, the zone follows the ledger and category (Others is the fallback), the apartment rooms do not overlap and the door sits
 * on the bottom wall; and rule 16: every field and record the inventory ledgers declared before the sizes still exists.
 * The shell twin (test/test-inventory-3d.sh) proves each of these red on a broken copy of the sources.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class InventoryLayoutTest {
    private fun rec(id: String, name: String, w: Int, d: Int, h: Int, cat: String = "", kg: String = "1") =
        mapOf("id" to id, "name" to name, "category" to cat, "width_cm" to "$w", "depth_cm" to "$d", "height_cm" to "$h", "weight_kg" to kg)

    /** The real declared seeds, read through the same LedgerSeed the app uses. */
    private fun seeded(): Map<String, List<Rec>> {
        val dir = BuroLlcTestData.text("build.json").let { JSONObject(it).getJSONObject("ui").getJSONArray("sections") }
            .let { a -> (0 until a.length()).map { a.getJSONObject(it) }.single { it.getString("id") == "inventory" } }
        val pages = dir.getJSONArray("pages").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") } }
        val defs = pages.map { p ->
            val blk = JSONArray(BuroLlcTestData.text("data/ui/inventory/$p.json")).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.single { it.optString("kind") == "ledger" }
            LedgerDef.parse(blk.getJSONArray("ledgers").getJSONObject(0))
        }
        val d = LedgerSeed.applied(LedgerData(), defs)
        return defs.associate { it.collection to d.rows(it.collection) }
    }

    private fun overlap(a: PlacedBox, b: PlacedBox) = a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h && a.z < b.z + b.d && b.z < a.z + a.d

    private fun many(): Map<String, List<Rec>> = mapOf(
        "inv_electronics" to (1..12).map { rec("r$it", "E$it", 20 + it * 5, 10 + it * 3, 5 + it) },
        "inv_clothing" to (1..9).map { rec("r$it", "C$it", 40 + it, 8, 60 + it * 3) },
        "inv_transport" to listOf(rec("r1", "Bike", 180, 60, 110), rec("r2", "Van", 540, 205, 260), rec("r3", "Moto", 210, 80, 120)),
        "inv_household" to (1..15).map { rec("r$it", "H$it", 30 + it * 4, 20 + it * 2, 20 + it) },
        "inv_leisure" to listOf(rec("r1", "Board", 320, 80, 15), rec("r2", "Mat", 30, 15, 15)),
        "inv_zzz" to listOf(rec("r1", "Odd", 50, 50, 50)),
    )

    @Test fun `every sized item is placed exactly once`() {
        for (rows in listOf(many(), seeded())) {
            val (items, _) = InventoryLayout.items(rows)
            val s = InventoryLayout.storage(rows)
            assertEquals(items.map { it.id }.sorted(), s.zones.flatMap { z -> z.boxes.map { it.item.id } }.sorted())
            assertEquals(items.size, s.count)
        }
    }

    @Test fun `boxes stay inside their zone and never overlap`() {
        for (rows in listOf(many(), seeded())) for (z in InventoryLayout.storage(rows).zones) {
            for (b in z.boxes) {
                assertTrue("${b.item.name} x in ${z.zone}", b.x >= 0 && b.x + b.w <= z.w)
                assertTrue("${b.item.name} z in ${z.zone}", b.z >= 0 && b.z + b.d <= z.d)
                assertTrue("${b.item.name} y in ${z.zone}", b.y >= 0 && b.y + b.h <= z.h)
                assertEquals("box keeps the item's size", b.item.volumeCm3, b.w.toLong() * b.d * b.h)
            }
            for (i in z.boxes.indices) for (j in i + 1 until z.boxes.size) assertTrue("${z.zone} ${z.boxes[i].item.name}/${z.boxes[j].item.name}", !overlap(z.boxes[i], z.boxes[j]))
        }
    }

    @Test fun `zones do not overlap in the room`() {
        val zs = InventoryLayout.storage(many()).zones
        for (i in zs.indices) for (j in i + 1 until zs.size) {
            val a = zs[i]; val b = zs[j]
            assertTrue("${a.zone}/${b.zone}", !(a.ox < b.ox + b.w && b.ox < a.ox + a.w && a.oz < b.oz + b.d && b.oz < a.oz + a.d))
        }
    }

    @Test fun `the zone follows the ledger and category and Others is the fallback`() {
        fun z(c: String, cat: String = "", w: Int = 30, d: Int = 30, h: Int = 30) = InventoryLayout.zoneFor(InventoryLayout.items(mapOf(c to listOf(rec("r1", "x", w, d, h, cat)))).first.single())
        assertEquals(Zone.DESK, z("inv_electronics", "Computer"))
        assertEquals(Zone.RACK, z("inv_clothing", "Outdoor"))
        assertEquals(Zone.FLOOR, z("inv_transport", "Bike", 180, 60, 110))
        assertEquals(Zone.FLOOR, z("inv_leisure", "Gear", 320, 80, 15))
        assertEquals(Zone.SHELF, z("inv_leisure", "Gear", 30, 15, 15))
        assertEquals(Zone.SHELF, z("inv_household", "Tools"))
        assertEquals(Zone.SHELF, z("inv_housing", "Fixtures"))
        assertEquals(Zone.OTHERS, z("inv_unknown"))
        val s = InventoryLayout.storage(many())
        assertEquals(listOf("Odd"), s.zones.first { it.zone == Zone.OTHERS }.boxes.map { it.item.name })
        assertEquals(Zone.values().toList(), s.zones.map { it.zone })
    }

    @Test fun `totals add up and items without a size are counted not drawn`() {
        val rows = mapOf("inv_electronics" to listOf(rec("r1", "A", 100, 100, 100, kg = "2"), rec("r2", "B", 50, 100, 100, kg = "3"), mapOf("id" to "r3", "name" to "no size")))
        val s = InventoryLayout.storage(rows)
        assertEquals(1, s.unsized)
        assertEquals(1.5, s.itemVolumeM3, 1e-9)
        assertEquals(5.0, s.weightKg, 1e-9)
        assertTrue(s.footprintM2 > 0 && s.storageVolumeM3 >= s.itemVolumeM3 && s.roomFootprintM2 >= s.footprintM2)
    }

    @Test fun `the apartment has four rooms that do not overlap and the door sits on the bottom wall`() {
        val a = InventoryLayout.apartment(seeded())
        assertEquals(listOf(RoomId.LEFT, RoomId.LIVING, RoomId.RIGHT, RoomId.ENTRY), a.rooms.map { it.room.id })
        val rs = a.rooms.map { it.room }
        for (r in rs) assertTrue("$r inside", r.x >= 0 && r.z >= 0 && r.x + r.w <= InventoryLayout.APARTMENT_W && r.z + r.d <= InventoryLayout.APARTMENT_D)
        for (i in rs.indices) for (j in i + 1 until rs.size) { val p = rs[i]; val q = rs[j]; assertTrue("${p.id}/${q.id}", !(p.x < q.x + q.w && q.x < p.x + p.w && p.z < q.z + q.d && q.z < p.z + p.d)) }
        val entry = rs.single { it.id == RoomId.ENTRY }
        assertEquals(InventoryLayout.APARTMENT_D, a.door.z)
        assertEquals("the door is on the entry's bottom edge", entry.z + entry.d, a.door.z)
        assertTrue(a.door.x >= entry.x && a.door.x + a.door.w <= entry.x + entry.w)
        val living = rs.single { it.id == RoomId.LIVING }
        assertTrue("living room is central", living.x > rs.single { it.id == RoomId.LEFT }.x && living.x + living.w < rs.single { it.id == RoomId.RIGHT }.x + rs.single { it.id == RoomId.RIGHT }.w)
    }

    @Test fun `apartment items are placed once, inside the walls, without overlap`() {
        val rows = seeded()
        val a = InventoryLayout.apartment(rows)
        val (items, _) = InventoryLayout.items(rows.filterKeys { it == "inv_housing" || it == "inv_household" })
        assertTrue(items.isNotEmpty())
        assertEquals(items.map { it.id }.sorted(), a.rooms.flatMap { r -> r.boxes.map { it.item.id } }.sorted())
        assertEquals(emptyList<String>(), a.overflow)
        for (rp in a.rooms) {
            val iw = rp.room.w - 2 * InventoryLayout.WALL; val id = rp.room.d - 2 * InventoryLayout.WALL
            for (b in rp.boxes) assertTrue("${b.item.name} in ${rp.room.id}", b.x >= 0 && b.z >= 0 && b.x + b.w <= iw && b.z + b.d <= id)
            for (i in rp.boxes.indices) for (j in i + 1 until rp.boxes.size) assertTrue(!overlap(rp.boxes[i], rp.boxes[j]))
        }
        assertEquals(RoomId.LEFT, a.rooms.first { r -> r.boxes.any { it.item.name == "Bed and mattress" } }.room.id)
        assertEquals(RoomId.LIVING, a.rooms.first { r -> r.boxes.any { it.item.name == "Sofa" } }.room.id)
        assertEquals(RoomId.RIGHT, a.rooms.first { r -> r.boxes.any { it.item.name == "Washing machine" } }.room.id)
    }

    @Test fun `the seeded storage holds electronics on the desk, clothing on the racks and the bikes on the floor`() {
        val s = InventoryLayout.storage(seeded())
        fun names(z: Zone) = s.zones.first { it.zone == z }.boxes.map { it.item.name }
        assertTrue("Laptop" in names(Zone.DESK) && "Suit" in names(Zone.RACK) && "Gravel bike" in names(Zone.FLOOR) && "Stand-up paddle board" in names(Zone.FLOOR))
        assertTrue(s.count >= 20)
    }

    @Test fun `the orbit projection turns, tilts and picks the nearest box`() {
        val cam = OrbitCamera(yawDeg = 0.0, pitchDeg = 90.0)
        val (x, y, _) = cam.project(10.0, 0.0, 20.0)
        assertEquals(10.0, x, 1e-6); assertEquals(20.0, y, 1e-6)
        val iso = OrbitCamera()
        assertTrue("top is seen from above", iso.facing(0.0, 1.0, 0.0) > 0 && iso.facing(0.0, -1.0, 0.0) < 0)
        assertEquals(3, Orbit.visible(iso).size)
        val a = SceneBox("a", "a", 0, 0, 0, 0, 50, 50, 50); val b = SceneBox("b", "b", 0, 0, 0, 100, 50, 50, 50)
        val cam2 = OrbitCamera(yawDeg = 0.0, pitchDeg = 35.0, cx = 0.0, cy = 0.0)
        val (px, py, _) = cam2.project(25.0, 50.0, 125.0)
        assertEquals("b", Orbit.pick(listOf(a, b), cam2, px, py))
        assertNull(Orbit.pick(listOf(a, b), cam2, 5000.0, 5000.0))
        assertNull("walls are not tappable", Orbit.pick(listOf(a.copy(id = "")), cam2, px, py))
    }

    @Test fun `every ledger has a placeholder glyph`() {
        for (c in listOf("inv_electronics", "inv_clothing", "inv_leisure", "inv_housing", "inv_household", "inv_transport", "inv_other")) assertTrue(InventoryLayout.icon(c, "").isNotBlank())
    }

    @Test fun `every pre-existing inventory field and record is still declared (rule 16)`() {
        val base = JSONObject(BuroLlcTestData.text("test/fixtures/inventory-baseline.json"))
        assertEquals(16, base.length())
        for (c in base.keys()) {
            val b = base.getJSONObject(c); val page = b.getString("page")
            val led = JSONArray(BuroLlcTestData.text("data/ui/inventory/$page.json")).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.single { it.optString("kind") == "ledger" }.getJSONArray("ledgers").getJSONObject(0)
            val fields = led.getJSONArray("fields").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") } }
            val was = b.getJSONArray("fields").let { a -> (0 until a.length()).map { a.getString(it) } }
            assertEquals("$c keeps its fields first and in order", was, fields.take(was.size))
            for (n in listOf("photo", "width_cm", "depth_cm", "height_cm", "weight_kg")) assertTrue("$c declares $n", n in fields)
            val seed = led.getJSONArray("seed").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            val old = b.getJSONArray("seed")
            for (i in 0 until old.length()) {
                val o = old.getJSONObject(i)
                val now = seed.firstOrNull { it.optString("name") == o.getString("name") }
                assertTrue("$c record ${o.getString("name")} is gone", now != null)
                for (k in o.keys()) assertEquals("$c ${o.getString("name")}.$k", o.getString(k), now!!.optString(k))
            }
        }
    }
}
