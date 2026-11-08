package com.diegonmarcos.superapp.xray

/** The privileged shell as the exporter sees it. */
interface XrayShell {
    val channel: String
    /** Output of `sh -c <cmd>`, or null when the channel could not serve it. */
    fun exec(cmd: String): String?
}

/** Everything one export needs, with the effects behind seams so the whole thing runs in a unit test. */
class XrayInputs(
    val device: String,
    val appVersion: String,
    val now: Long,
    /** The X-Ray page's own groups and sections, walked in declared order (source: app). */
    val walk: List<XSection>,
    /** The privileged channel, or null for a partial report. */
    val shell: XrayShell?,
    val partialReason: String = "",
    /** App-sandbox readers by [Probe.appKey]; null when unavailable. */
    val app: (String) -> String? = { null },
    /** Further app-side sections (battery history, trend). */
    val extra: List<XSection> = emptyList(),
    val probes: List<Probe> = XrayProbes.order,
    /** The declared pages of Configs ▸ Observability that this export walks. */
    val pages: List<String> = XrayTree.PAGES,
)

/** #1504 Builds the report: every probe at shell uid (or its sandbox fallback, or skipped), the walked page, the ranking. */
object XrayExporter {

    fun build(i: XrayInputs): XReport {
        val raw = LinkedHashMap<String, String>()
        val probed = ArrayList<Pair<Probe, XSection>>()
        for (p in i.probes) {
            val shell = i.shell?.let { s -> runCatching { s.exec(p.cmd) }.getOrNull() }
            val sec = when {
                shell != null -> { raw[p.id] = shell; XSection(p.group, p.title, SRC_SHELL, cap(shell, p.cap), id = p.id) }
                p.appKey != null && i.app(p.appKey) != null -> XSection(p.group, p.title, SRC_APP, cap(i.app(p.appKey).orEmpty(), p.cap), id = p.id)
                i.shell == null -> XSection(p.group, p.title, SRC_SKIPPED, "not read: no adb shell (${PARTIAL_LABEL})", ok = false, id = p.id)
                else -> XSection(p.group, p.title, SRC_SHELL, "the shell channel could not serve this command", ok = false, id = p.id)
            }
            probed += p to sec
        }
        val partial = i.shell == null
        val suspects = XrayAnalyze.suspects(raw)
        val fleet = XrayAnalyze.fleetLines(raw)
        val ordered = ArrayList<XSection>()
        ordered += XrayTree.manifest(i.pages, i.walk.isNotEmpty())
        val groups = (i.probes.map { it.group } + i.extra.map { it.group }).distinct()
        for (g in groups) {
            ordered += i.extra.filter { it.group == g }
            ordered += probed.filter { it.first.group == g }.map { it.second }
        }
        ordered += i.walk
        val clean = ordered.map { it.copy(text = XrayRedact.scrub(it.text)) }
        return XReport(
            device = i.device, appVersion = i.appVersion, generatedAt = i.now, partial = partial,
            partialReason = if (partial) i.partialReason.ifEmpty { "no adb shell channel" } else "",
            adbChannel = i.shell?.channel ?: "none", suspects = suspects, fleet = fleet.map { XrayRedact.scrub(it) }, sections = clean,
        )
    }

    private fun cap(s: String, n: Int): String = if (s.length <= n) s else s.take(n) + "\n…(truncated ${s.length - n} chars)"
}
