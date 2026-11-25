package com.diegonmarcos.cloudme

import java.io.File

/** The REAL declarations (build.json, data/ui), read from the source tree: a test never carries its own copy. */
object BuroLlcTestData {
    /** #1901 build.json::ui.renamed, parsed: the table LegacyRoutes reads from its asset in the app. */
    fun renamed(): LegacyRoutes.Renamed =
        LegacyRoutes.Renamed.parse(org.json.JSONObject(text("build.json")).getJSONObject("ui").getJSONObject("renamed"))

    /** Hands LegacyRoutes the declared table, as MainActivity.onCreate does from the asset. */
    fun installRenamed() = LegacyRoutes.install(renamed())

    fun text(rel: String): String {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, rel)
            if (f.isFile) return f.readText()
            dir = dir.parentFile
        }
        throw AssertionError("$rel not found above ${File("").absolutePath}")
    }
}
