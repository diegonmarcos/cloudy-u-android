package com.diegonmarcos.superapp.cad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1542 the Models table: filters derived from the data, sort, cell texts and the model page's full data rows. */
class ModelTableTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val c = "c".repeat(64)
    private val json = """{"schema":2,"categories":[{"id":"boats","label":"Boats"},{"id":"civil","label":"Civil Engineering"},{"id":"mechanics","label":"Mechanics"}],"models":[
      {"id":"yacht","name":"Yacht","library":"boats","category":"boats","software":"Rhino","format":"iges","path":"boats/iges/y.igs","size":59000000,"sha256":"$a","updated":"2026-10-01",
       "units":"m","bbox":{"min":[0,-4,0],"max":[40,4,55]},"layer_count":32,"material_count":1,"stats":{"vertices":195709,"triangles":254076},
       "view":{"path":"boats/glb/y.view.glb","format":"glb","size":7700000,"sha256":"$b","tool":"occt","vertices":195709,"triangles":254076},
       "conversion_log":["brep2glb ok","254076 triangles"]},
      {"id":"museum","name":"Campidoglio","library":"architecture","category":"civil","software":"SketchUp","format":"skp","path":"architecture/skp/m.skp","size":4000000,"sha256":"$c","updated":"2026-11-05",
       "layers":["Layer0","Layer1"],"layer_count":2,"component_count":16,
       "view":{"path":"architecture/glb/m.view.glb","format":"glb","size":480000,"sha256":"$b","tool":"openskp 1.5.0 (MIT)","vertices":10138,"triangles":6444},
       "derived":[{"path":"architecture/ifc/m.view.ifc","format":"ifc","size":570000,"sha256":"$a","tool":"openskp 1.5.0 (MIT)"}]}
    ]}"""
    private val models = ModelManifest.parse(json)
    private val cats = ModelManifest.categories(json)

    @Test fun categoriesAreDeclaredAndKeepAnEmptyOne() {
        assertEquals(listOf("all", "boats", "civil", "mechanics"), ModelTable.categoryOptions(models, cats).map { it.first })
        assertEquals("Civil Engineering", ModelTable.categoryOptions(models, cats)[2].second)
        // no declaration: derived from the models
        assertEquals(listOf("all", "boats", "civil"), ModelTable.categoryOptions(models, emptyList()).map { it.first })
    }

    @Test fun extensionAndSoftwareFiltersComeFromTheData() {
        assertEquals(listOf("all", "glb", "ifc", "igs", "skp"), ModelTable.extensionOptions(models).map { it.first })
        assertEquals(".skp", ModelTable.extensionOptions(models).last().second)
        assertEquals(listOf("all", "Rhino", "SketchUp"), ModelTable.softwareOptions(models).map { it.first })
    }

    @Test fun filtersCombineAndAllIsTheDefault() {
        assertEquals(listOf("yacht", "museum"), ModelTable.filter(models, ModelTable.Filters()).map { it.id })
        assertEquals(listOf("museum"), ModelTable.filter(models, ModelTable.Filters(category = "civil")).map { it.id })
        assertEquals(listOf("yacht", "museum"), ModelTable.filter(models, ModelTable.Filters(extension = "glb")).map { it.id })
        assertEquals(listOf("museum"), ModelTable.filter(models, ModelTable.Filters(extension = "ifc")).map { it.id })
        assertEquals(listOf("yacht"), ModelTable.filter(models, ModelTable.Filters(software = "Rhino")).map { it.id })
        assertTrue(ModelTable.filter(models, ModelTable.Filters(category = "civil", software = "Rhino")).isEmpty())
        assertTrue(ModelTable.filter(models, ModelTable.Filters(category = "mechanics")).isEmpty())
    }

    @Test fun sortFlipsOnASecondTapAndNumbersSortByValue() {
        assertEquals(listOf("museum", "yacht"), ModelTable.sort(models, ModelTable.Sort(ModelTable.Column.NAME)).map { it.id })
        val bySize = ModelTable.toggle(ModelTable.Sort(), ModelTable.Column.SIZE)
        assertEquals(ModelTable.Sort(ModelTable.Column.SIZE, false), bySize)
        assertEquals(listOf("museum", "yacht"), ModelTable.sort(models, bySize).map { it.id })
        assertEquals(listOf("yacht", "museum"), ModelTable.sort(models, ModelTable.toggle(bySize, ModelTable.Column.SIZE)).map { it.id })
        assertEquals(listOf("museum", "yacht"), ModelTable.sort(models, ModelTable.Sort(ModelTable.Column.GEOMETRY)).map { it.id })
        assertEquals(listOf("yacht", "museum"), ModelTable.sort(models, ModelTable.Sort(ModelTable.Column.UPDATED)).map { it.id })
        assertEquals(listOf("museum", "yacht"), ModelTable.sort(models, ModelTable.Sort(ModelTable.Column.SOFTWARE, true)).map { it.id })
    }

    @Test fun cellTexts() {
        assertEquals("195.7k / 254.1k", ModelTable.geometryText(models[0]))
        assertEquals("10.1k / 6.4k", ModelTable.geometryText(models[1]))
        assertEquals("59.0 MB", ModelTable.sizeText(models[0].size))
        assertEquals("-", ModelTable.sizeText(-1))
        assertEquals("igs", ModelTable.extension(models[0]))
        assertEquals("Civil Engineering", ModelTable.categoryLabel(models[1], cats))
    }

    @Test fun modelPageShowsEveryDeclaredFact() {
        val keys = ModelDetail.rows(models[0], cats).map { it.key }
        listOf("name", "category", "extension", "software", "size", "updated", "geometry", "dimensions", "bbox", "units", "materials", "layers",
            "sha256", "view_sha256", "view_tool", "log").forEach { assertTrue(it, it in keys) }
        val dims = ModelDetail.rows(models[0], cats).first { it.key == "dimensions" }.value
        assertEquals("40.00 x 8.00 x 55.00 m", dims)
        val sk = ModelDetail.rows(models[1], cats)
        assertEquals("2: Layer0, Layer1", sk.first { it.key == "layers" }.value)
        assertEquals("16", sk.first { it.key == "components" }.value)
        assertTrue(sk.any { it.key == "derived_sha256" && it.value.startsWith("ifc ") })
        // an undeclared fact is left out, never invented
        assertTrue(sk.none { it.key == "units" || it.key == "dimensions" })
    }
}
