package com.diegonmarcos.superapp.wifi

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1263 libs:wifi: channel math, security parsing, the OUI and channel-plan assets, rating, tracking, throttle, tool parsers, export. */
class WifiLibTest {
    private val assets = File("../../ab_cloud-libs-shared/libs/wifi/src/main/assets/wifi")
    private fun asset(n: String) = File(if (n == "oui.tsv") pack else assets, if (n == "oui.tsv") "wifi-oui.tsv" else n).readText()
    private val pack = File("../../data/fleet/packs")   // #1547 the OUI registry is a downloadable pack, not a lib asset

    private fun ap(bssid: String, ssid: String, freq: Int, rssi: Int, width: Int = 0, cf0: Int = 0, caps: String = "[WPA2-PSK-CCMP][ESS]", now: Long = 1_000_000L): AccessPoint =
        ApTracker().ingest(listOf(RawScan(bssid, ssid, freq, cf0, 0, width, rssi, caps)), now).first()

    // ---- channel math ----

    @Test fun `frequency to channel and band, both ways`() {
        assertEquals(1, ChannelMath.channel(2412)); assertEquals(13, ChannelMath.channel(2472)); assertEquals(14, ChannelMath.channel(2484))
        assertEquals(36, ChannelMath.channel(5180)); assertEquals(165, ChannelMath.channel(5825)); assertEquals(177, ChannelMath.channel(5885))
        assertEquals(1, ChannelMath.channel(5955)); assertEquals(2, ChannelMath.channel(5935)); assertEquals(233, ChannelMath.channel(7115))
        assertEquals(Band.B24, ChannelMath.band(2437)); assertEquals(Band.B5, ChannelMath.band(5500)); assertEquals(Band.B6, ChannelMath.band(6115))
        for (c in 1..13) assertEquals(c, ChannelMath.channel(ChannelMath.freq(Band.B24, c)))
        for (c in 36..177 step 4) assertEquals(c, ChannelMath.channel(ChannelMath.freq(Band.B5, c)))
        for (c in 1..233 step 4) assertEquals(c, ChannelMath.channel(ChannelMath.freq(Band.B6, c)))
        assertEquals(-1, ChannelMath.channel(1234))
    }

    @Test fun `width codes and occupied spans`() {
        assertEquals(listOf(20, 40, 80, 160, 160, 320), (0..5).map { ChannelMath.widthMhz(it) })
        assertEquals("80+80 MHz", ChannelMath.widthLabel(4))
        assertEquals(listOf(5170..5250), ChannelMath.spans(5180, 5210, 0, 2, Band.B5))
        // old-style 160: cf0 = primary 80, cf1 = whole centre
        assertEquals(listOf(5170..5330), ChannelMath.spans(5180, 5210, 5250, 3, Band.B5))
        assertEquals(2, ChannelMath.spans(5180, 5210, 5530, 4, Band.B5).size)
        assertEquals(listOf(2401..2423), ChannelMath.spans(2412, 0, 0, 0, Band.B24))
        assertEquals(0, ChannelMath.overlap(ChannelMath.range(2412, 22), ChannelMath.range(2437, 22)))
        assertTrue(ChannelMath.overlap(ChannelMath.range(2412, 22), ChannelMath.range(2422, 22)) > 0)
        assertEquals(listOf(36, 40, 44, 48), ChannelMath.coveredChannels(ChannelMath.spans(5180, 5210, 0, 2, Band.B5), Band.B5))
        assertEquals(5210, ChannelMath.centre(5180, 5210, 0, 2, Band.B5))
    }

    @Test fun `standard from code, else inferred`() {
        assertEquals(WifiStd.BE, WifiStd.fromCode(8, Band.B5, 80)); assertEquals(WifiStd.AX6E, WifiStd.fromCode(6, Band.B6, 160))
        assertEquals(WifiStd.AX, WifiStd.fromCode(6, Band.B5, 80)); assertEquals(WifiStd.AC, WifiStd.fromCode(0, Band.B5, 80))
        assertEquals(WifiStd.N, WifiStd.fromCode(0, Band.B24, 40)); assertEquals(WifiStd.BE, WifiStd.infer(Band.B6, 320))
    }

    // ---- security ----

    @Test fun `security strings`() {
        val wpa2 = SecurityParser.parse("[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS][WPS]")
        assertEquals("WPA2", wpa2.label); assertTrue(wpa2.wps); assertEquals(3, wpa2.level); assertEquals(Pmf.None, wpa2.pmf)
        val trans = SecurityParser.parse("[RSN-PSK+SAE-CCMP][ESS][MFPC]")
        assertEquals("WPA2/WPA3", trans.label); assertEquals(Pmf.Capable, trans.pmf); assertEquals(4, trans.level)
        val sae = SecurityParser.parse("[RSN-SAE-CCMP][ESS][MFPR][MFPC]")
        assertEquals("WPA3", sae.label); assertEquals(Pmf.Required, sae.pmf)
        assertEquals("OWE", SecurityParser.parse("[RSN-OWE-CCMP][ESS][MFPR][MFPC]").label)
        val ent = SecurityParser.parse("[WPA2-EAP-CCMP][RSN-EAP-CCMP][ESS]")
        assertTrue(ent.enterprise); assertEquals("WPA2-Enterprise", ent.label)
        assertEquals("WPA3-Enterprise", SecurityParser.parse("[RSN-EAP/SHA256-CCMP][MFPR][MFPC]").label)
        assertEquals("WPA3-Enterprise 192", SecurityParser.parse("[RSN-EAP-SUITE-B-192-GCMP-256][MFPR]").label)
        val wep = SecurityParser.parse("[WEP][ESS]"); assertTrue(wep.weak); assertEquals(1, wep.level)
        val open = SecurityParser.parse("[ESS]"); assertTrue(open.open); assertEquals("Open", open.label)
        assertTrue(SecurityParser.parse("[WPA-PSK-TKIP][ESS]").weak)
        assertTrue(SecurityParser.parse("[RSN-FT/PSK-CCMP][ESS]").ft)
        assertEquals(listOf("GCMP-256"), SecurityParser.parse("[RSN-SAE_EXT_KEY-GCMP-256][MFPR]").ciphers)
    }

    // ---- OUI ----

    @Test fun `OUI table is the IEEE registry, sorted, and looks up vendors`() {
        val t = OuiTable.parse(asset("oui.tsv"))
        assertTrue("registry too small: ${t.size}", t.size > 30_000)
        assertTrue(t.lookup("F0:D1:A9:12:34:56")!!.contains("Apple"))
        assertTrue(t.lookup("b8-27-eb-00-00-01")!!.contains("Raspberry"))
        assertEquals(t.lookup("f0d1a9123456") + " (virtual)", t.lookup("f2:d1:a9:12:34:56"))
        assertEquals("Private (randomized)", t.label("fe:ff:ff:00:00:01")); assertEquals("Unknown", t.label("not a mac"))
        assertNull(t.lookup("zz"))
        assertTrue(OuiTable.isLocallyAdministered("02:00:00:00:00:00")); assertFalse(OuiTable.isLocallyAdministered("00:1a:11:00:00:00"))
        val keys = asset("oui.tsv").lineSequence().filter { it.length > 7 && it[0] != '#' }.map { it.substring(0, 6) }.toList()
        assertEquals("keys sorted and unique", keys.sorted().distinct(), keys)
    }

    @Test fun `OUI is a downloadable pack, not a lib asset, and the descriptor matches the blob`() {
        assertFalse("the .aar must carry no OUI table", File(assets, "oui.tsv").exists())
        assertTrue(File("../../ab_cloud-libs-shared/libs/wifi/src/main/java/com/diegonmarcos/superapp/wifi/WifiHub.kt").readText().contains("FleetPack.text("))
        val blob = File(pack, "wifi-oui.tsv").readBytes()
        val d = org.json.JSONObject(File(pack, "wifi-oui.json").readText())
        assertEquals(blob.size.toLong(), d.getLong("bytes"))
        assertEquals(java.security.MessageDigest.getInstance("SHA-256").digest(blob).joinToString("") { "%02x".format(it) }, d.getString("sha256"))
        assertTrue(d.getString("url").endsWith("/data/fleet/packs/wifi-oui.tsv"))
    }

    // ---- plans + config ----

    @Test fun `channel plans parse and map countries`() {
        val p = ChannelPlans.parse(asset("channel-plans.json"))
        assertEquals("EU", p.forCountry("es").id); assertEquals("US", p.forCountry("US").id); assertEquals("WORLD", p.forCountry("ZZ").id)
        assertEquals("WORLD", p.forCountry(null).id)
        assertEquals(59, p.byId("US").channels(Band.B6).size); assertEquals(24, p.byId("EU").channels(Band.B6).size)
        assertTrue(p.byId("EU").channels(Band.B5).first { it.ch == 52 }.dfs); assertFalse(p.byId("EU").channels(Band.B5).first { it.ch == 36 }.dfs)
        for (plan in p.plans) for ((b, chs) in plan.bands) {
            assertEquals("${plan.id} $b duplicates", chs.size, chs.map { it.ch }.toSet().size)
            chs.forEach { assertTrue("${plan.id} ${b.id}/${it.ch}", ChannelMath.freq(b, it.ch) > 0 && ChannelMath.band(ChannelMath.freq(b, it.ch)) == b) }
        }
        val all = p.plans.flatMap { it.countries }; assertEquals("a country in two plans", all.size, all.toSet().size)
    }

    @Test fun `config defaults merge with the SuperApp build json`() {
        val bj = JSONObject(File("build.json").takeIf { it.exists() }?.readText() ?: File("../build.json").readText())
        val over = bj.getJSONObject("wifi_analyzer")
        val c = WifiConfig.parse(asset("wifi-config.json"), over.toString())
        assertEquals(over.getJSONObject("throughput").getString("download_url"), c.downloadUrl)
        assertTrue(c.downloadUrlFor(1000).endsWith("bytes=1000"))
        assertEquals(listOf("scan", "channels", "link", "tools", "log"), c.tabs)
        assertEquals(WifiTab.entries.map { it.id }, c.tabs)
        assertEquals(4, c.throttleLimit); assertEquals(120_000L, c.throttleWindowMs)
        assertTrue(c.nsdTypes.contains("_googlecast._tcp")); assertEquals("https", c.portNames[443])
        assertTrue(PortSpec.parse(c.portDefaultSpec, c.portMax) != null)
        val o = WifiConfig.parse(asset("wifi-config.json"), """{"ping":{"count":3},"_doc":"x"}""")
        assertEquals(3, o.pingCount); assertEquals("example.com", o.pingDefaultHost)
        assertEquals(20, WifiConfig.parse(asset("wifi-config.json"), "not json").pingCount)
    }

    // ---- rating ----

    @Test fun `crowded channel 1 recommends 6 or 11, congestion and interference rise with neighbours`() {
        val plan = ChannelPlans.parse(asset("channel-plans.json")).byId("US")
        val crowd = (1..5).map { ap("00:00:00:00:00:0$it", "n$it", 2412, -45) } + ap("00:00:00:00:01:00", "six", 2437, -80)
        val scores = ChannelRating.rate(crowd, plan, Band.B24)
        assertEquals(11, scores.size)
        val best = ChannelRating.best(scores)!!
        assertEquals(11, best.channel)
        assertTrue(scores.first { it.channel == 1 }.rating < scores.first { it.channel == 11 }.rating)
        assertTrue(ChannelRating.congestion(crowd, plan, Band.B24).score > ChannelRating.congestion(crowd.takeLast(1), plan, Band.B24).score)
        assertTrue(ChannelRating.interference(crowd[0], crowd) > ChannelRating.interference(crowd.last(), crowd))
        val empty5 = ChannelRating.rate(emptyList(), plan, Band.B5)
        assertFalse("an empty band prefers a non-DFS channel", ChannelRating.best(empty5)!!.dfs)
        // an 80 MHz neighbour loads all four of its 20 MHz channels
        val wide = listOf(ap("aa:00:00:00:00:01", "w", 5180, -40, 2, 5210))
        val s5 = ChannelRating.rate(wide, plan, Band.B5).associateBy { it.channel }
        listOf(36, 40, 44, 48).forEach { assertTrue(s5[it]!!.aps == 1) }; assertEquals(0, s5[52]!!.aps)
        assertTrue(ChannelRating.weight(-30) > ChannelRating.weight(-80))
    }

    // ---- tracking, filter, throttle ----

    @Test fun `tracker smooths, keeps history on fresh results only, drops stale APs`() {
        val t = ApTracker(alpha = 0.5, historyMax = 3, dropMs = 10_000) { "Vendor" }
        t.ingest(listOf(RawScan("AA:BB:CC:00:00:01", "\"home\"", 5180, rssi = -60, timestampUs = 1)), 1000)
        var a = t.ingest(listOf(RawScan("aa:bb:cc:00:00:01", "home", 5180, rssi = -40, timestampUs = 2)), 2000).single()
        assertEquals(-50.0, a.smoothed, 0.01); assertEquals(2, a.history.size); assertEquals("home", a.ssid); assertEquals("Vendor", a.vendor)
        a = t.ingest(listOf(RawScan("aa:bb:cc:00:00:01", "home", 5180, rssi = -40, timestampUs = 2)), 3000).single()
        assertEquals("a cached result is not a new sample", 2, a.history.size)
        repeat(5) { a = t.ingest(listOf(RawScan("aa:bb:cc:00:00:01", "home", 5180, rssi = -40, timestampUs = 10L + it)), 4000L + it).single() }
        assertEquals(3, a.history.size)
        assertTrue(t.ingest(listOf(RawScan("aa:bb:cc:00:00:02", "", 2412, rssi = -70, timestampUs = 1)), 20_000).none { it.bssid.endsWith("01") })
        assertTrue(t.get("aa:bb:cc:00:00:02")!!.hidden)
    }

    @Test fun `filter, sort and group`() {
        val aps = listOf(ap("00:00:00:00:00:01", "alpha", 2412, -70), ap("00:00:00:00:00:02", "beta", 5180, -50, caps = "[ESS]"),
            ap("00:00:00:00:00:03", "alpha", 5200, -60), ap("00:00:00:00:00:04", "", 5955, -80))
        assertEquals(listOf("beta", "alpha", "alpha", ""), ApFilter.apply(aps, ApQuery(), 1_000_000).map { it.ssid })
        assertEquals(2, ApFilter.apply(aps, ApQuery(text = "ALP"), 1_000_000).size)
        assertEquals(1, ApFilter.apply(aps, ApQuery(openOnly = true), 1_000_000).size)
        assertEquals(3, ApFilter.apply(aps, ApQuery(hideHidden = true), 1_000_000).size)
        assertEquals(1, ApFilter.apply(aps, ApQuery(bands = setOf(Band.B24)), 1_000_000).size)
        assertEquals(2, ApFilter.apply(aps, ApQuery(minRssi = -65), 1_000_000).size)
        assertEquals(listOf("beta", "alpha", "(hidden)"), ApFilter.groups(ApFilter.apply(aps, ApQuery(), 1_000_000)).map { it.first })
    }

    @Test fun `scan throttle is four per two minutes, unless switched off`() {
        val t = ScanThrottle(4, 120_000)
        repeat(4) { assertTrue(t.canScan(1000L + it)); t.record(1000L + it) }
        assertFalse(t.canScan(2000)); assertEquals(0, t.remaining(2000)); assertEquals(121_000L, t.nextAllowedAt(2000))
        assertTrue(t.canScan(121_000)); assertEquals(1, t.remaining(121_000))
        t.enabled = false; assertTrue(t.canScan(121_001)); assertEquals(121_001L, t.nextAllowedAt(121_001))
    }

    @Test fun `thresholds grade, quality and SNR`() {
        val th = RssiThresholds()
        assertEquals(4, th.grade(-50)); assertEquals(0, th.grade(-90)); assertEquals(100, th.quality(-40)); assertEquals(0, th.quality(-100)); assertEquals(30, th.snr(-65))
    }

    // ---- tools ----

    @Test fun `ping output parses to replies, stats and hops`() {
        val out = """
            PING 192.168.1.1 (192.168.1.1) 56(84) bytes of data.
            64 bytes from 192.168.1.1: icmp_seq=1 ttl=64 time=2.00 ms
            64 bytes from 192.168.1.1: icmp_seq=2 ttl=64 time=4.00 ms
            64 bytes from 192.168.1.1: icmp_seq=4 ttl=64 time=3.00 ms

            --- 192.168.1.1 ping statistics ---
            4 packets transmitted, 3 received, 25% packet loss, time 3004ms
        """.trimIndent()
        val s = PingParser.stats(out)
        assertEquals(4, s.sent); assertEquals(3, s.received); assertEquals(25.0, s.lossPct, 0.01)
        assertEquals(2.0, s.min, 0.001); assertEquals(3.0, s.avg, 0.001); assertEquals(4.0, s.max, 0.001); assertEquals(1.5, s.jitter, 0.001)
        assertEquals(PingReply(1, 64, 2.0, "192.168.1.1"), PingParser.reply("64 bytes from 192.168.1.1: icmp_seq=1 ttl=64 time=2.00 ms"))
        assertEquals("1.1.1.1", PingParser.reply("64 bytes from one.one.one.one (1.1.1.1): icmp_seq=1 ttl=57 time=11.2 ms")!!.from)
        val hop = PingParser.hop(2, "From 10.0.0.1 icmp_seq=1 Time to live exceeded", 5.0)
        assertEquals(Hop(2, "10.0.0.1", 5.0, false), hop)
        assertTrue(PingParser.hop(9, "64 bytes from 8.8.8.8: icmp_seq=1 ttl=118 time=9.1 ms", 9.5).reached)
        assertNull(PingParser.hop(3, "", 1000.0).ip)
        assertEquals(100.0, PingStats.of(5, emptyList()).lossPct, 0.0)
        assertTrue(PingParser.safeHost("router.lan")); assertTrue(PingParser.safeHost("fe80::1"))
        assertFalse(PingParser.safeHost("-c 99")); assertFalse(PingParser.safeHost("a;reboot")); assertFalse(PingParser.safeHost(""))
    }

    @Test fun `ipv4 sweep is bounded to a 24`() {
        assertEquals(253, Ipv4.sweepHosts("192.168.1.20", 24).size)
        assertFalse(Ipv4.sweepHosts("192.168.1.20", 24).contains("192.168.1.20"))
        assertEquals("a /16 is clamped to its /24", 253, Ipv4.sweepHosts("10.1.2.3", 16).size)
        assertEquals(listOf("10.0.0.1"), Ipv4.sweepHosts("10.0.0.2", 30))
        assertEquals("192.168.1.1", Ipv4.fromLittleEndian(0x0101A8C0)); assertEquals("255.255.255.0", Ipv4.prefixToMask(24))
        assertTrue(Ipv4.isPrivate("172.20.1.1")); assertFalse(Ipv4.isPrivate("8.8.8.8")); assertNull(Ipv4.toInt("1.2.3"))
    }

    @Test fun `port specs are bounded`() {
        assertEquals(listOf(22, 80, 8000, 8001, 8002), PortSpec.parse("80, 22,8000-8002"))
        assertNull(PortSpec.parse("1-2000", 1024)); assertNull(PortSpec.parse("0")); assertNull(PortSpec.parse("10-5")); assertNull(PortSpec.parse("http"))
    }

    @Test fun `ssdp and netbios packets`() {
        assertTrue(Ssdp.mSearch().startsWith("M-SEARCH * HTTP/1.1\r\n") && Ssdp.mSearch().endsWith("\r\n\r\n"))
        val h = Ssdp.headers("HTTP/1.1 200 OK\r\nSERVER: Linux UPnP/1.0 Sonos/70\r\nST: upnp:rootdevice\r\nLOCATION: http://192.168.1.9:1400/xml\r\n\r\n")
        assertEquals("upnp:rootdevice", h["ST"]); assertTrue(h["SERVER"]!!.contains("Sonos")); assertTrue(Ssdp.headers("garbage").isEmpty())
        val q = NetBios.nodeStatusRequest(0x1234)
        assertEquals(50, q.size); assertEquals("CKAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", String(q, 13, 32, Charsets.US_ASCII)); assertEquals(0x21, q[47].toInt())
        // a node status answer: header, the echoed name, type / class / ttl / rdlength, 2 names, then the MAC
        val name = byteArrayOf(32) + ByteArray(32) { 'A'.code.toByte() } + byteArrayOf(0)
        fun entry(n: String, suffix: Int, flags: Int) = n.padEnd(15).toByteArray() + byteArrayOf(suffix.toByte(), flags.toByte(), 0)
        val rr = byteArrayOf(0, 0x21, 0, 1, 0, 0, 0, 0, 0, 0) + byteArrayOf(2) + entry("WORKGROUP", 0, 0x80) + entry("NAS-01", 0, 0x04) + ByteArray(6)
        val resp = byteArrayOf(0x12, 0x34, 0x84.toByte(), 0, 0, 0, 0, 1, 0, 0, 0, 0) + name + rr
        assertEquals("NAS-01", NetBios.parseName(resp)); assertNull(NetBios.parseName(ByteArray(10)))
    }

    @Test fun `locator, heatmap and roaming`() {
        assertTrue(Locator.intervalMs(-40.0) < Locator.intervalMs(-80.0)); assertEquals(80L, Locator.intervalMs(-20.0)); assertEquals(1400L, Locator.intervalMs(-99.0))
        assertEquals(1, Locator.trend(listOf(-80.0, -79.0, -70.0, -68.0))); assertEquals(-1, Locator.trend(listOf(-60.0, -61.0, -70.0, -72.0))); assertEquals(0, Locator.trend(listOf(-60.0)))
        val hm = Heatmap(3, 3, "x").record(0, 0, -50).record(0, 0, -60).record(2, 2, -80)
        assertEquals(-55.0, hm.cells[0]!!.mean, 0.01); assertEquals(0, hm.best!!.row); assertTrue(hm.clear().cells.isEmpty())
        val a = LinkSnapshot(ssid = "home", bssid = "aa:aa:aa:aa:aa:01", rssi = -70, freqMhz = 2412)
        val b = a.copy(bssid = "aa:aa:aa:aa:aa:02", rssi = -50, freqMhz = 5180)
        assertEquals("roam", Roaming.detect(1, a, b)!!.kind); assertNull(Roaming.detect(1, a, a))
        assertEquals("connect", Roaming.detect(1, null, a)!!.kind); assertEquals("disconnect", Roaming.detect(1, a, null)!!.kind)
        assertEquals(Band.B5, b.band); assertEquals(36, b.channel); assertTrue(b.connected); assertFalse(LinkSnapshot(bssid = "02:00:00:00:00:00").connected)
    }

    // ---- export ----

    @Test fun `only the wrapping quote pair is stripped from an ssid`() {
        assertEquals("home", Ssid.unquote("\"home\""))
        assertEquals("caf\u00e9, \"2\"", Ssid.unquote("caf\u00e9, \"2\""))
        assertEquals("\"x\"", Ssid.unquote("\"\"x\"\""))
        assertEquals("\"", Ssid.unquote("\""))
    }

    @Test fun `csv, json and pcap exports`() {
        val aps = listOf(ap("f0:d1:a9:00:00:01", "café, \"2\"", 5180, -55, 2, 5210, "[RSN-SAE-CCMP][MFPR][MFPC]"), ap("00:11:22:33:44:55", "", 2437, -70, caps = "[ESS]"))
        val csv = Export.csv(aps).trimEnd().lines()
        assertEquals(Export.CSV_HEADER.joinToString(","), csv[0]); assertEquals(3, csv.size); assertTrue(csv[1].startsWith("\"café, \"\"2\"\"\""))
        val j = JSONObject(Export.json(aps, null, emptyList(), 5))
        assertEquals(2, j.getJSONArray("access_points").length()); assertEquals(5L, j.getLong("exported_at_ms"))
        val pcap = Export.pcap(aps)
        assertEquals(listOf(0xd4, 0xc3, 0xb2, 0xa1), (0..3).map { pcap[it].toInt() and 255 })
        assertEquals(127, pcap[20].toInt() and 255)
        val b = Export.beacon(aps[0])
        assertEquals(0x80, b[13].toInt() and 255) // frame control after the 13-byte radiotap header
        assertEquals(5180, (b[8].toInt() and 255) or ((b[9].toInt() and 255) shl 8))
        assertEquals(24 + aps.sumOf { 16 + Export.beacon(it).size }, pcap.size)
        assertTrue(String(b, Charsets.ISO_8859_1).contains("caf"))
        assertTrue("RSN IE present for WPA3", b.indexOf(48.toByte()) > 0)
    }

    // ---- i18n ----

    @Test fun `en and es strings carry the same keys`() {
        val key = Regex("""<string name="([a-z0-9_]+)"""")
        val en = key.findAll(File("../../ab_cloud-libs-shared/libs/wifi/src/main/res/values/strings.xml").readText()).map { it.groupValues[1] }.toSet()
        val es = key.findAll(File("../../ab_cloud-libs-shared/libs/wifi/src/main/res/values-es/strings.xml").readText()).map { it.groupValues[1] }.toSet()
        assertTrue(en.size > 100); assertEquals(en, es)
        assertNotNull(en.firstOrNull { it == "wifi_throttle_body" })
    }
}
