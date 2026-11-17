package com.diegonmarcos.superapp.locationclient

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1770 the client lib NEVER triggers a fix: no location API, no Play Services location, no location permission in its sources. */
class LocationClientNeverLocatesTest {
    private val banned = Regex("""requestLocationUpdates|requestSingleUpdate|getCurrentLocation|getLastKnownLocation|getLastLocation|FusedLocationProviderClient|LocationServices|GeofencingClient|ActivityRecognition|android\.location|gms\.location|LocationManager|LocationRequest|ACCESS_(FINE|COARSE|BACKGROUND)_LOCATION""")

    private fun code(f: File): String = f.readText().replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    @Test fun `no source or manifest of the lib references a location API`() {
        val files = File("src/main").walkTopDown().filter { it.isFile && (it.extension == "kt" || it.name == "AndroidManifest.xml") }.toList()
        assertTrue("the lib sources were not found from ${File(".").absolutePath}", files.any { it.name == "NavLocationClient.kt" })
        val hits = files.flatMap { f -> banned.findAll(code(f).replace(Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL), "")).map { "${f.name}: ${it.value}" }.toList() }
        assertTrue("location API referenced: $hits", hits.isEmpty())
    }
}
