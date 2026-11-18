package com.diegonmarcos.cloudkeyboard.grabber

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1793 the Grabber Place card: the place / not-a-place decision, the great-circle distance, the flight estimate, the map zoom,
 * the rate gate and the cache the free services ask for, and the parsers of OSRM, Wikipedia and Wikimedia Commons. Pure JVM.
 */
class GrabberPlaceTest {
    private fun hit(name: String, category: String, importance: Double, short: String = "", rank: Int = 16, type: String = "", at: String = "") =
        GeoHit(1.0, 2.0, name, category = category, type = type, addressType = at, importance = importance, placeRank = rank, shortName = short)

    // ── place or not ────────────────────────────────────────────────────────

    @Test fun noGeocoderHitIsNeverAPlace() {
        SelectionKind.entries.forEach { assertFalse(it.name, PlaceDecision.isPlace(it, "anything", null)) }
    }

    @Test fun anAddressOrAPlaceTheClassifierSawIsAPlaceOnceTheGeocoderKnowsIt() {
        assertTrue(PlaceDecision.isPlace(SelectionKind.ADDRESS, "1600 Pennsylvania Avenue NW, Washington, DC 20500", hit("White House, 1600, Pennsylvania Avenue NW", "building", 0.1, rank = 30)))
        assertTrue(PlaceDecision.isPlace(SelectionKind.PLACE, "Eiffel Tower", hit("Tour Eiffel, Paris", "man_made", 0.6, "Tour Eiffel")))
    }

    @Test fun aWordIsAPlaceOnlyWhenTheBestHitIsAnImportantMappedPlaceOfThatName() {
        assertTrue(PlaceDecision.isPlace(SelectionKind.WORD, "Paris", hit("Paris, Île-de-France, France", "boundary", 0.88, "Paris", 12)))
        assertTrue("case and accents do not matter", PlaceDecision.isPlace(SelectionKind.WORD, "zurich", hit("Zürich, Schweiz", "place", 0.7, "Zürich")))
        assertTrue(PlaceDecision.isPlace(SelectionKind.PHRASE, "mont blanc", hit("Mont Blanc, Chamonix", "natural", 0.62, "Mont Blanc", 18)))
        assertTrue("no name of its own: the display name's first part", PlaceDecision.isPlace(SelectionKind.WORD, "Lisbon", hit("Lisbon, Portugal", "place", 0.8)))
    }

    @Test fun aWordCarriedBySomeShopOrHamletIsNotAPlace() {
        assertFalse("a café named like the word", PlaceDecision.isPlace(SelectionKind.WORD, "serendipity", hit("Serendipity, Main St", "amenity", 0.9, "Serendipity")))
        assertFalse("an unimportant hamlet", PlaceDecision.isPlace(SelectionKind.WORD, "apple", hit("Apple, Somewhere", "place", 0.18, "Apple")))
        assertFalse("a different name", PlaceDecision.isPlace(SelectionKind.WORD, "Paris", hit("Paris Hilton Hotel, Las Vegas", "tourism", 0.5, "Paris Hilton Hotel")))
        assertFalse(PlaceDecision.isPlace(SelectionKind.EMPTY, "", hit("x", "place", 1.0, "x")))
        assertFalse("a point that is not a number", PlaceDecision.isPlace(SelectionKind.ADDRESS, "a", GeoHit(Double.NaN, 1.0, "a")))
    }

    @Test fun theDecisionReadsTheGeocoderAnswerFields() {
        val g = GrabberParsers.firstGeo("""[{"lat":"48.8566","lon":"2.3522","display_name":"Paris, Île-de-France, France","category":"boundary",
            "type":"administrative","addresstype":"city","importance":0.88,"place_rank":12,"name":"Paris"}]""")!!
        assertEquals("boundary", g.category); assertEquals("administrative", g.type); assertEquals("city", g.addressType)
        assertEquals(0.88, g.importance, 1e-9); assertEquals(12, g.placeRank); assertEquals("Paris", g.shortName)
        assertTrue(PlaceDecision.isPlace(SelectionKind.WORD, "Paris", g))
        val info = PlaceDecision.info(g)
        assertEquals("CITY", info.type)
        assertEquals("boundary/administrative", info.detail)
        assertEquals("Paris, Île-de-France, France", info.address)
        // the jsonv1 key `class` is read when `category` is absent
        assertEquals("natural", GrabberParsers.firstGeo("""[{"lat":"1","lon":"2","display_name":"x","class":"natural"}]""")!!.category)
    }

    @Test fun theZoomFollowsThePlaceRank() {
        assertEquals(4, PlaceDecision.zoom(SelectionKind.WORD, 4))     // a country
        assertEquals(9, PlaceDecision.zoom(SelectionKind.WORD, 12))    // a county
        assertEquals(11, PlaceDecision.zoom(SelectionKind.WORD, 16))   // a city
        assertEquals(16, PlaceDecision.zoom(SelectionKind.PLACE, 30))  // a building
        assertEquals(16, PlaceDecision.zoom(SelectionKind.ADDRESS, 16)) // an address is always close in
        assertEquals(14, PlaceDecision.zoom(SelectionKind.PLACE, 0))
    }

    // ── distances and the flight estimate ───────────────────────────────────

    @Test fun greatCircleDistances() {
        assertEquals(0.0, GeoMath.haversineKm(48.0, 2.0, 48.0, 2.0), 1e-9)
        assertEquals(343.5, GeoMath.haversineKm(48.8566, 2.3522, 51.5074, -0.1278), 1.0)      // Paris - London
        assertEquals(3936.0, GeoMath.haversineKm(40.7128, -74.0060, 34.0522, -118.2437), 6.0) // New York - Los Angeles
        assertEquals(Math.PI / 2 * GeoMath.EARTH_RADIUS_KM, GeoMath.haversineKm(0.0, 0.0, 0.0, 90.0), 1e-6)
        assertEquals(Math.PI * GeoMath.EARTH_RADIUS_KM, GeoMath.haversineKm(0.0, 0.0, 0.0, 180.0), 1e-6) // antipodes, no NaN
        assertEquals("symmetric", GeoMath.haversineKm(10.0, 20.0, -30.0, 40.0), GeoMath.haversineKm(-30.0, 40.0, 10.0, 20.0), 1e-9)
    }

    @Test fun theFlightIsDistanceAt800KmhPlusOneHour() {
        assertEquals(1.0, GeoMath.flightHours(0.0), 1e-9)
        assertEquals(2.0, GeoMath.flightHours(800.0), 1e-9)
        assertEquals(1.0 + 3936.0 / 800.0, GeoMath.flightHours(3936.0), 1e-9)
        val p = TripPlan.of(48.8566, 2.3522, 51.5074, -0.1278, "MOCK")
        assertEquals(GeoMath.haversineKm(48.8566, 2.3522, 51.5074, -0.1278), p.airKm, 1e-9)
        assertEquals(p.airKm / 800.0 + 1.0, p.flightHours, 1e-9)
        assertEquals("MOCK", p.fromNote)
    }

    @Test fun theFixAgeIsSaidAndOver30MinutesItIsStale() {
        assertEquals("from your position just now", FixAge.text(20_000))
        assertEquals("from your position 12 min ago", FixAge.text(12 * 60_000L))
        assertEquals("from your position 3 h ago", FixAge.text(3 * 3_600_000L))
        assertEquals("from your position 4 d ago", FixAge.text(4 * 86_400_000L))
        assertFalse(FixAge.stale(30 * 60_000L))
        assertTrue(FixAge.stale(30 * 60_000L + 1))
        assertTrue("a newer fix recomputes", FixAge.newer(1_000, 2_000))
        assertFalse(FixAge.newer(2_000, 2_000))
        val p = TripPlan.of(0.0, 0.0, 1.0, 1.0, "x", stale = true, fixTs = 5)
        assertTrue(p.stale); assertEquals(5L, p.fixTs)
        // the model watches Cloud Nav for a newer fix when there is none or it is stale, and the card offers the refresh
        val dir = File("src/main/java/com/diegonmarcos/cloudkeyboard/grabber")
        val model = File(dir, "GrabberModel.kt").readText()
        assertTrue(model.contains("if (where.stale) watchForFix(gen, to, where.ts)"))
        assertTrue(model.contains("FixAge.newer(shownTs, w.ts)"))
        assertTrue(File(dir, "GrabberUi.kt").readText().contains("KeyChip(\"Open Cloud Nav to refresh\""))
    }

    @Test fun distancesAndDurationsReadable() {
        assertEquals("850 m", GeoMath.km(850.0))
        assertEquals("12.3 km", GeoMath.km(12345.0))
        assertEquals("344 km", GeoMath.km(343_560.0))
        assertEquals("1 min", GeoMath.duration(42.0))
        assertEquals("1 h 02 min", GeoMath.duration(3720.0))
        assertEquals("2 d 0 h", GeoMath.duration(172_800.0))
    }

    @Test fun eachRoutedModeHasItsFreeOsrmServerAndAirIsNeverRouted() {
        val foot = TravelMode.FOOT.routeUrl(48.8566, 2.3522, 51.5074, -0.1278)
        assertTrue(foot, foot.startsWith("https://routing.openstreetmap.de/routed-foot/route/v1/foot/2.352200,48.856600;-0.127800,51.507400?"))
        assertTrue(TravelMode.CAR.routeUrl(0.0, 0.0, 1.0, 1.0).contains("/routed-car/route/v1/driving/"))
        assertTrue(TravelMode.BIKE.routeUrl(0.0, 0.0, 1.0, 1.0).contains("/routed-bike/route/v1/bike/"))
        assertFalse(TravelMode.AIR.routed)
        assertTrue(TravelMode.AIR.label.contains("ESTIMATE"))
        assertEquals(3, TravelMode.entries.count { it.routed })
    }

    @Test fun osrmAnswers() {
        assertEquals(Leg(463_120.5, 16_873.2), PlaceParsers.osrmLeg("""{"code":"Ok","routes":[{"distance":463120.5,"duration":16873.2,"legs":[]}],"waypoints":[]}"""))
        assertNull(PlaceParsers.osrmLeg("""{"code":"NoRoute","message":"Impossible route between points"}"""))
        assertNull(PlaceParsers.osrmLeg("""{"code":"Ok","routes":[]}"""))
        assertNull(PlaceParsers.osrmLeg("<html>502</html>"))
    }

    // ── an image for a term ─────────────────────────────────────────────────

    @Test fun wikipediaSummaryGivesTheThumbnailWithAttribution() {
        val t = PlaceParsers.wikipediaSummary("""{"type":"standard","title":"Serendipity","thumbnail":{"source":"https://upload.wikimedia.org/x/320px-S.jpg","width":320,"height":200},
            "content_urls":{"desktop":{"page":"https://en.wikipedia.org/wiki/Serendipity"}}}""")
        assertNotNull(t)
        assertEquals("https://upload.wikimedia.org/x/320px-S.jpg", t!!.thumbUrl)
        assertEquals("https://en.wikipedia.org/wiki/Serendipity", t.pageUrl)
        assertEquals("WIKIPEDIA", t.source)
        assertTrue(t.attribution.contains("Wikipedia") && t.attribution.contains("licence"))
        assertNull("a disambiguation page is not the term", PlaceParsers.wikipediaSummary("""{"type":"disambiguation","title":"Apple","thumbnail":{"source":"https://u/x.jpg"}}"""))
        assertNull("no thumbnail", PlaceParsers.wikipediaSummary("""{"type":"standard","title":"Apple"}"""))
        assertNull(PlaceParsers.wikipediaSummary("not json"))
        val noPage = PlaceParsers.wikipediaSummary("""{"type":"standard","title":"Mont Blanc","thumbnail":{"source":"https://u/m.jpg"}}""", "fr")!!
        assertEquals("https://fr.wikipedia.org/wiki/Mont_Blanc", noPage.pageUrl)
    }

    @Test fun commonsGivesTheFirstFileWithItsAuthorAndLicence() {
        val json = """{"query":{"pages":{"-2":{"title":"File:B.jpg","index":2,"imageinfo":[{"thumburl":"https://u/b.jpg","descriptionurl":"https://c/B"}]},
            "-1":{"title":"File:A cat.jpg","index":1,"imageinfo":[{"thumburl":"https://u/a.jpg","url":"https://u/a-full.jpg","descriptionurl":"https://commons.wikimedia.org/wiki/File:A_cat.jpg",
            "extmetadata":{"Artist":{"value":"<a href=\"//x\">Jane Doe</a>"},"LicenseShortName":{"value":"CC BY-SA 4.0"}}}]}}}}"""
        val t = PlaceParsers.commonsFirst(json)!!
        assertEquals("https://u/a.jpg", t.thumbUrl)
        assertEquals("A cat.jpg", t.title)
        assertEquals("WIKIMEDIA COMMONS", t.source)
        assertEquals("Jane Doe · CC BY-SA 4.0 · Wikimedia Commons", t.attribution)
        assertEquals("https://commons.wikimedia.org/wiki/File:A_cat.jpg", t.pageUrl)
        assertNull(PlaceParsers.commonsFirst("""{"batchcomplete":""}"""))
        assertTrue(PlaceParsers.commonsFirst("""{"query":{"pages":{"1":{"title":"File:Z","imageinfo":[{"thumburl":"https://u/z.jpg"}]}}}}""")!!.attribution.contains("unknown author"))
    }

    // ── the courtesy the free services ask for ──────────────────────────────

    @Test fun theRateGateSpacesRequestsOneIntervalApart() {
        val g = RateGate(1000)
        assertEquals(0L, g.reserve(0))
        assertEquals(900L, g.reserve(100))   // the second waits for 1000
        assertEquals(1500L, g.reserve(500))  // the third for 2000
        assertEquals(0L, g.reserve(5000))    // a quiet spell resets nothing to wait for
    }

    @Test fun theCacheKeepsTheNewestAndEvictsTheLeastRecentlyUsed() {
        val c = Lru<String, String>(2)
        c.put("a", "1"); c.put("b", "2")
        assertEquals("1", c.get("a"))
        c.put("c", "3")
        assertNull("b was the least recently used", c.get("b"))
        assertEquals("1", c.get("a")); assertEquals("3", c.get("c"))
        assertEquals(2, c.size())
    }

    // ── what the app really does with these rules ───────────────────────────

    @Test fun theModelGeocodesThenDecidesAndOnlyAPlaceCardAsksForThePosition() {
        val dir = File("src/main/java/com/diegonmarcos/cloudkeyboard/grabber")
        val model = File(dir, "GrabberModel.kt").readText()
        assertTrue(model.contains("GrabberNet.geocode(app, query)"))
        assertTrue(model.contains("if (PlaceDecision.isPlace(kind, query, hit)) GrabberNet.placeMap(hit!!, kind, query) else GrabberNet.termImage(query)"))
        assertTrue(model.contains("if (m is MediaResult.MapView) loadTrip(gen, m)"))
        val net = File(dir, "GrabberNet.kt").readText()
        assertTrue("Nominatim: one request a second", net.contains("RateGate(1100)"))
        assertTrue("Nominatim: cached", net.contains("GeoCache.get(ctx, key)"))
        assertTrue("a User-Agent naming the app, nothing personal", Regex("""APP_UA = "CloudKeyboard-Grabber/[0-9.]+ \(Android keyboard app; open source\)"""").containsMatchIn(net))
        assertFalse("no owner or account in the services' User-Agent", Regex("""APP_UA = "[^"]*(github|@|diegonmarcos)""").containsMatchIn(net))
        // the keyboard never locates (#1770): the position is Cloud Nav's, read through libs:location-client
        val loc = File(dir, "GrabberLocation.kt").readText()
        assertTrue(loc.contains("NavLocationClient.ask(ctx, now)"))
        assertFalse(Regex("""\b(requestLocationUpdates|getLastKnownLocation|getCurrentLocation|LocationManager)\b""").containsMatchIn(loc.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")))
        assertFalse(File("src/main/AndroidManifest.xml").readText().contains("ACCESS_FINE_LOCATION"))
    }
}
