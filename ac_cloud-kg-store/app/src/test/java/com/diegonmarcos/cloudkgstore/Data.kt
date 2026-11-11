package com.diegonmarcos.cloudkgstore

import org.json.JSONObject
import java.io.File

/** The declarations this app reads, from the repository itself (the JVM suite's working directory is the app module). */
object Data {
    private val app: File = File(System.getProperty("user.dir")).let { if (File(it, "src/main").isDirectory) it else File(it, "app") }
    private val module: File = app.parentFile
    private val repo: File = module.parentFile

    private fun json(f: File) = JSONObject(f.readText())

    val kgText: String get() = File(repo, "data/fleet/app/com.diegonmarcos.cloudkgstore/kg.json").readText()
    val buildJson: JSONObject get() = json(File(module, "build.json"))
    val searchJson: JSONObject get() = json(File(repo, "ac_cloud-search/build.json")).getJSONObject("search")
    val browserJson: JSONObject get() = json(File(repo, "ac_cloud-browser/build.json"))
    val accountJson: JSONObject get() = json(File(repo, "ac_cloud-account/build.json"))
    val composer: JSONObject get() = json(File(app, "src/main/assets/ai/composer.json"))
    val fleetManifest: JSONObject get() = json(File(repo, "aa_cloud-superapp/data/constellation-fleet.json"))
    val debugPorts: JSONObject get() = json(File(repo, "1_cicd/src/data/debug-ports.json")).getJSONObject("ports")

    /** A saved real response of Cloud Search's own suite, read where it lives (nothing is copied). */
    fun searchFixture(name: String): String = File(repo, "ac_cloud-search/core/src/test/resources/fixtures/$name").readText()

    fun <T> JSONObject.arr(key: String, read: (JSONObject) -> T): List<T> =
        getJSONArray(key).let { a -> (0 until a.length()).map { read(a.getJSONObject(it)) } }
}
