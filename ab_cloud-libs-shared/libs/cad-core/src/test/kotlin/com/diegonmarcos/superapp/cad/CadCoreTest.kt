package com.diegonmarcos.superapp.cad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CadCoreTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val manifest = """{"schema":1,"models":[
      {"id":"hull","name":"Hull","library":"boats","format":"STP","path":"boats/step/hull.stp","size":10,"sha256":"$a","licence":"own"},
      {"id":"yacht","name":"Yacht","library":"boats","format":"igs","path":"boats/iges/y.igs","size":99,"sha256":"$a",
       "view":{"path":"boats/glb/y.view.glb","format":"glb","size":5,"sha256":"$b"}},
      {"id":"museum","name":"Museum","library":"architecture","format":"skp","path":"architecture/skp/m.skp","size":4,"sha256":"$b","conversion":"Pending export."},
      {"id":"nosha","library":"boats","format":"step","path":"x.step","sha256":"zz"},
      {"library":"boats","format":"step","path":"y.step","sha256":"$a"}
    ]}"""
    private val models = ModelManifest.parse(manifest)
    private val libs = CadLibraries.parse("""{"_doc":"x",
      "boats":{"title":"Boats","default_model":"yacht","formats":[{"ext":["step","stp"],"label":"STEP","purpose":"solids"},{"ext":["igs"],"label":"IGES","purpose":"lines"}],
               "core":{"geometry":"B-rep","need":"manufacture","standards":"STEP / IGES"}},
      "architecture":{"title":"Architecture","default_model":"yacht","formats":[{"ext":["skp"],"label":"SketchUp","purpose":"concept"}],"core":{}}}""")
    private fun lib(id: String) = libs.first { it.id == id }

    @Test fun manifestKeepsValidEntriesOnly() {
        assertEquals(listOf("hull", "yacht", "museum"), models.map { it.id })
        val hull = models[0]
        assertEquals("step", hull.format)
        assertEquals("boats/step/hull.stp", hull.path)
        assertEquals(10L, hull.size)
        assertEquals(a, hull.sha256)
        assertEquals("own", hull.licence)
        assertEquals("iges", models[1].format)
        assertEquals(ModelView("boats/glb/y.view.glb", "glb", 5, b), models[1].view)
        assertNull(hull.view)
        assertEquals("Pending export.", models[2].conversion)
        assertTrue(ModelManifest.parse("not json").isEmpty())
    }

    @Test fun formatRoutesToItsLib() {
        mapOf("glb" to "mesh", "gltf" to "mesh", "obj" to "mesh", "fbx" to "mesh", "step" to "brep", "stp" to "brep", "iges" to "brep",
            "igs" to "brep", "dxf" to "dxf", "3dm" to "3dm", "ifc" to "ifc", ".IFC" to "ifc").forEach { (f, lib) ->
            assertEquals(f, CadFormats.Route.Viewer(lib), CadFormats.route(f))
        }
    }

    @Test fun unsupportedFormatsCarryTheirReason() {
        for (f in listOf("skp", "rvt", "x_t", "dwg")) {
            val r = CadFormats.route(f)
            assertEquals(f, CadFormats.Route.StoreOnly(CadFormats.STORE_ONLY.getValue(f)), r)
        }
        assertTrue(CadFormats.STORE_ONLY.getValue("skp").contains("SketchUp"))
        assertTrue(CadFormats.STORE_ONLY.getValue("rvt").contains("IFC"))
        assertTrue(CadFormats.STORE_ONLY.getValue("x_t").contains("STEP"))
        assertEquals(CadFormats.Route.StoreOnly(CadFormats.UNKNOWN_REASON), CadFormats.route("xyz"))
        val plan = CadFormats.plan(models[2])
        assertEquals(CadFormats.Plan.StoreOnly(CadFormats.STORE_ONLY.getValue("skp") + " Pending export."), plan)
    }

    @Test fun planPrefersTheDerivedView() {
        assertEquals(CadFormats.Plan.Show("mesh", "boats/glb/y.view.glb", "glb", 5, b, true), CadFormats.plan(models[1]))
        assertEquals(CadFormats.Plan.Show("brep", "boats/step/hull.stp", "step", 10, a, false), CadFormats.plan(models[0]))
    }

    @Test fun defaultModelPerPage() {
        assertEquals("yacht", CadLibraries.defaultModel(lib("boats"), models)?.id)
        // a default naming another library's model is not this page's default
        assertNull(CadLibraries.defaultModel(lib("architecture"), models))
        assertEquals(listOf("geometry", "need", "standards"), lib("boats").core.keys.toList())
        assertEquals("STEP / IGES", lib("boats").core["standards"])
    }

    @Test fun modelsFilteredByFormat() {
        val boats = lib("boats")
        assertEquals(listOf("hull"), CadLibraries.modelsOf(boats, boats.formats[0], models).map { it.id })
        assertEquals(listOf("yacht"), CadLibraries.modelsOf(boats, boats.formats[1], models).map { it.id })
        assertEquals(listOf("museum"), CadLibraries.modelsOf(lib("architecture"), lib("architecture").formats[0], models).map { it.id })
    }
}
