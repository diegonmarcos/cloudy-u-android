package com.diegonmarcos.capitalfinterm

import java.io.File

/** The declared data and build.json as the JVM suite reads them: the module's working directory is app/, the data lives one up. */
object DataFiles {
    private fun find(vararg candidates: String): File = candidates.map { File(it) }.first { it.isFile }

    val sourcesJson: String get() = find("../data/sources.json", "data/sources.json").readText()
    val dashboardsJson: String get() = find("../data/dashboards.json", "data/dashboards.json").readText()
    val controlJson: String get() = find("../data/control.json", "data/control.json").readText()
    val universeJson: String get() = find("../data/universe.json", "data/universe.json").readText()
    val pmJson: String get() = find("../data/pm.json", "data/pm.json").readText()
    val cbCountriesJson: String get() = find("../data/cb-countries.json", "data/cb-countries.json").readText()
    val cbIndicatorsJson: String get() = find("../data/cb-indicators.json", "data/cb-indicators.json").readText()
    val cbSnapshotJson: String get() = find("../data/cb-snapshot.json", "data/cb-snapshot.json").readText()
    fun cbFixture(name: String): String = find("src/test/resources/cb/$name", "app/src/test/resources/cb/$name").readText()
    val quotesJson: String get() = find("../data/quotes.json", "data/quotes.json").readText()
    val quotesSnapshotJson: String get() = find("../data/quotes-snapshot.json", "data/quotes-snapshot.json").readText()
    fun quotesFixture(name: String): String = find("src/test/resources/quotes/$name", "app/src/test/resources/quotes/$name").readText()
    val buildJson: String get() = find("../build.json", "build.json").readText()
    val engineFixtureJson: String get() = find("src/test/resources/engine-fixture.json", "app/src/test/resources/engine-fixture.json").readText()
}
