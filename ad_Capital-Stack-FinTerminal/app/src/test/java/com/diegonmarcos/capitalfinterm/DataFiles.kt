package com.diegonmarcos.capitalfinterm

import java.io.File

/** The declared data and build.json as the JVM suite reads them: the module's working directory is app/, the data lives one up. */
object DataFiles {
    private fun find(vararg candidates: String): File = candidates.map { File(it) }.first { it.isFile }

    val sourcesJson: String get() = find("../data/sources.json", "data/sources.json").readText()
    val dashboardsJson: String get() = find("../data/dashboards.json", "data/dashboards.json").readText()
    val controlJson: String get() = find("../data/control.json", "data/control.json").readText()
    val buildJson: String get() = find("../build.json", "build.json").readText()
    val engineFixtureJson: String get() = find("src/test/resources/engine-fixture.json", "app/src/test/resources/engine-fixture.json").readText()
}
