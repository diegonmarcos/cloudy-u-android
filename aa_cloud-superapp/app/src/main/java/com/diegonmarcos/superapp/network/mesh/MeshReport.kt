package com.diegonmarcos.superapp.network.mesh

import java.util.TimeZone

/**
 * #1153 The whole Status page as text, for Copy all and Export all. Public keys are kept; nothing secret
 * is ever read into it (no private key, no pre-shared key field is written) and [redact] strips any
 * `private key` / `preshared key` value that reached a free-text line (a journal entry, a notice).
 */
object MeshReport {

    private val SECRET = Regex("(?i)((?:private|preshared|pre-shared)[ _-]?key|psk)(\\s*[=:]\\s*)\\S+")

    fun redact(text: String): String = SECRET.replace(text) { "${it.groupValues[1]}${it.groupValues[2]}[redacted]" }

    /** #1845 The status text as Markdown for Export to Md: the title line, one `##` heading per `==` section, the rows fenced so the columns stay aligned. */
    fun markdown(plain: String): String = com.diegonmarcos.superapp.fleetendpoints.errors.ReportMarkdown.fromPlain(plain)

    /** #1928 [dump] cut to the `==` sections whose heading is in [titles] (a heading's trailing "(n)" count ignored), under its title line. */
    fun pick(dump: String, titles: List<String>): String {
        val out = mutableListOf<String>()
        var keep = false
        for ((i, l) in dump.lines().withIndex()) {
            if (l.startsWith("== ")) keep = l.removePrefix("== ").substringBefore(" (").trim() in titles
            if (i == 0 || keep) out += l
        }
        return out.joinToString("\n")
    }

    /** #1928 The route table as dump lines: verdict, IPv4 coverage, NAT64 prefixes, then one line per allowed-IP row. */
    fun routeLines(peers: List<PeerCfg>, nat64Marker: String): List<String> {
        val t = Routes.derive(peers, nat64Marker)
        fun kv(k: String, v: String) = "${k.padEnd(14)} ${v.ifBlank { "-" }}"
        return listOf(kv("mode", t.mode.name), kv("v4 coverage", "%.1f %%".format(java.util.Locale.ROOT, t.v4Coverage * 100)), kv("nat64", t.nat64.joinToString(", "))) +
            t.rows.map { kv(it.peer, "${it.cidr} v${it.family} ${it.kind.name}") }
    }

    fun build(
        s: MeshSnapshot?,
        dnsPreset: String,
        provider: String,
        transport: TransportView?,
        probes: List<ProbeLine>,
        reach: List<MeshReach.Result>,
        journal: List<LogEvent>,
        nowMs: Long,
        tz: TimeZone = TimeZone.getDefault(),
        /** #1797 Cloud C3's mesh profile: the per-mesh table and the profiler's steps; null = not probed yet. */
        profile: com.diegonmarcos.superapp.fleetxray.mesh.C3MeshProbe.Report? = null,
        /** #1900 The Prober matrix (groups A..F, each row with its route profile); empty = not run. */
        prober: List<MeshProber.Row> = emptyList(),
        /** #1928 The DNS servers Android actually resolves with (the active network's), and the route table's lines. */
        dnsServers: List<String> = emptyList(),
        routeLines: List<String> = emptyList(),
    ): String {
        val out = StringBuilder()
        fun line(t: String = "") { out.append(t).append('\n') }
        fun kv(k: String, v: String) = line("${k.padEnd(14)} ${v.ifBlank { "-" }}")
        line("Cloud Mesh status @ ${MeshFormat.clock(nowMs, tz)} (epoch ms $nowMs)")
        if (s == null) line("(no reading yet)")
        else {
            val c = s.cfg
            line("== Tunnel")
            kv("state", when { !s.engineInstalled -> "engine not installed"; s.up -> "UP (${s.light})"; else -> "DOWN" })
            kv("tunnel", c.tunnelName); kv("profile", c.activeProfile.substringAfterLast('/')); kv("provider", provider)
            line("== Interface")
            kv("address", c.address); kv("mtu", c.mtu); kv("listen port", c.listenPort); kv("public key", c.publicKey)
            line("== DNS")
            kv("in effect", c.dnsInEffect); kv("fleet", c.dns); kv("preset", dnsPreset)
            kv("servers in use", dnsServers.joinToString(", "))
            line("== Transport")
            kv("path", transport?.path.orEmpty()); kv("mode", transport?.mode.orEmpty()); kv("detail", transport?.detail.orEmpty()); kv("relay", transport?.relay.orEmpty())
            for (p in probes) kv("probe ${p.id}", when (p.ok) { true -> "PASS"; false -> "FAIL"; null -> "n/a" } + " " + p.detail)
            line("== Peers")
            for (p in s.peers) {
                kv(p.cfg.name.ifBlank { MeshFormat.shortKey(p.cfg.publicKey) },
                    "${p.health} hs ${MeshFormat.age(p.handshakeAgeS)} ep ${p.cfg.endpoint} allowed ${p.cfg.allowedIps} rx ${p.rx} tx ${p.tx} lat ${MeshFormat.latency(p.latencyMs)}")
                kv("  public key", p.cfg.publicKey)
                kv("  endpoint in use", MeshEndpoint.inUse(p.cfg.endpoint, transport?.path.orEmpty()))
            }
            line("== Traffic")
            kv("down", MeshFormat.bytes(s.rx)); kv("up", MeshFormat.bytes(s.tx)); kv("hub latency", MeshFormat.latency(s.hubLatencyMs))
            line("== Routes")
            for (l in routeLines) line(l)
        }
        // #1797 additions: the per-mesh table and Cloud C3's profile (every step, every detail)
        line("== Meshes")
        if (profile == null) line("(not probed)")
        else line(com.diegonmarcos.superapp.fleetxray.mesh.C3MeshProbe.text(profile))
        line("== Reachability")
        if (reach.isEmpty()) line("(not run)") else line(MeshReach.text(reach))
        // #1900 the Prober matrix: route profile, probe result and status of every row, groups A..F
        line("== Prober")
        line(MeshProber.text(prober))
        line("== Log (${journal.size})")
        for (e in journal) line("${MeshFormat.clock(e.atMs, tz)} ${MeshJournal.levelOf(e.kind).uppercase().padEnd(5)} ${e.kind.padEnd(9)} ${e.text}")
        return redact(out.toString().trimEnd())
    }
}
