package com.diegonmarcos.superapp.cad

/**
 * Format -> viewer lib routing (#1404). Each open format family has ONE loader lib (assets/cad/<lib>/loader.js); a closed
 * format with no open reader is store-only and says why. A model with a derived `view` is shown through the view's format.
 */
object CadFormats {
    const val LIB_MESH = "mesh"
    const val LIB_BREP = "brep"
    const val LIB_DXF = "dxf"
    const val LIB_3DM = "3dm"
    const val LIB_IFC = "ifc"

    private val ALIASES = mapOf("stp" to "step", "igs" to "iges", "xt" to "x_t")

    /** The viewer lib of every format that has one. */
    val VIEWERS: Map<String, String> = mapOf(
        "glb" to LIB_MESH, "gltf" to LIB_MESH, "obj" to LIB_MESH, "fbx" to LIB_MESH,
        "step" to LIB_BREP, "iges" to LIB_BREP,
        "dxf" to LIB_DXF,
        "3dm" to LIB_3DM,
        "ifc" to LIB_IFC,
    )

    /** Formats kept in the library with no viewer, each with its reason. */
    val STORE_ONLY: Map<String, String> = mapOf(
        "skp" to "Store-only here: the app has no in-app SketchUp reader and SketchUp's own SDK is proprietary. An open-source reader does exist (OpenSKP, MIT); a SketchUp model is shown through the GLB the CI derives from it.",
        "rvt" to "Store-only: Revit is a closed format with no open reader. Export IFC from Revit.",
        "x_t" to "Store-only: Parasolid X_T is a closed format with no open reader. Export STEP from the authoring tool.",
        "dwg" to "Store-only: no open DWG reader under a fleet-compatible licence (LibreDWG is GPL-3). Save as DXF.",
    )

    const val UNKNOWN_REASON = "Store-only: no viewer for this format."

    fun normalize(ext: String): String = ext.trim().lowercase().removePrefix(".").let { ALIASES[it] ?: it }

    sealed class Route {
        data class Viewer(val lib: String) : Route()
        data class StoreOnly(val reason: String) : Route()
    }

    fun route(format: String): Route {
        val f = normalize(format)
        VIEWERS[f]?.let { return Route.Viewer(it) }
        return Route.StoreOnly(STORE_ONLY[f] ?: UNKNOWN_REASON)
    }

    sealed class Plan {
        /** Show [path] (repo-relative) with [lib]; [format] is the file's own format, passed to the loader. A non-blank [asset] is bundled in the APK: shown from there, no fetch. */
        data class Show(val lib: String, val path: String, val format: String, val size: Long, val sha256: String, val derived: Boolean, val asset: String = "") : Plan()
        data class StoreOnly(val reason: String) : Plan()
    }

    /** What the viewer does with [m]: its derived view first (phones), else the original, else the store-only reason. */
    fun plan(m: Model): Plan {
        m.view?.let { v -> (route(v.format) as? Route.Viewer)?.let { return Plan.Show(it.lib, v.path, normalize(v.format), v.size, v.sha256, true, v.asset) } }
        return when (val r = route(m.format)) {
            is Route.Viewer -> Plan.Show(r.lib, m.path, normalize(m.format), m.size, m.sha256, false)
            is Route.StoreOnly -> Plan.StoreOnly(if (m.conversion.isBlank()) r.reason else r.reason + " " + m.conversion)
        }
    }
}
