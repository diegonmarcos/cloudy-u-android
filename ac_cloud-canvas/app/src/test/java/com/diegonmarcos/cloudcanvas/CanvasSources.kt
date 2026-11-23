package com.diegonmarcos.cloudcanvas

import java.io.File
import org.json.JSONObject

/**
 * #1865 where Cloud Canvas's Kotlin lives: the app module (the shell, Decl, the Enhancers) and every section library build.json::modules
 * declares as libs:canvas-* (canvas-core, canvas-draw, canvas-painter, canvas-play, by their `dir`). The source scans of the suite read all of
 * them, so a rule that held over app/src/main before the split still holds over every file the split moved.
 */
object CanvasSources {
    fun root(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "build.json").isFile) dir = dir.parentFile
        return dir!!
    }

    /** app/src/main and each canvas library's src/main. */
    fun mainDirs(): List<File> {
        val app = root()
        val modules = JSONObject(File(app, "build.json").readText()).getJSONObject("modules")
        val libs = modules.keys().asSequence().filter { it.startsWith("libs:canvas-") }.sorted()
            .map { File(app, modules.getJSONObject(it).getString("dir") + "/src/main").canonicalFile }.toList()
        return listOf(File(app, "app/src/main")) + libs
    }

    /** Every Kotlin source of the app and of its canvas libraries. */
    fun kotlin(): List<File> = mainDirs().flatMap { d -> d.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
}
