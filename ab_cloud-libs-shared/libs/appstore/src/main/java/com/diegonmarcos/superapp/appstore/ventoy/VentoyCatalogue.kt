package com.diegonmarcos.superapp.appstore.ventoy

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * #1405 the Ventoy tab's catalogue, the PURE part: the declared rows (assets/appstore-ventoy.json) and the rules that
 * turn an OFFICIAL feed (a distro's SHA256SUMS, its JSON release feed, a GitHub release) into one installable image:
 * version, download URL, size and sha256. Nothing here touches the network or the drive, so VentoyCatalogueTest
 * proves every rule and store-ventoy-mutation.yml proves the suite catches a break in each one.
 */
object VentoyCatalogue {

    /** ISO: Ventoy boots it. DEVICE: a phone image, flashed with the vendor's tool, never copied to Ventoy. TOOL: a
     *  builder or a doc (Cubic, Packer, ...), linked; installable only when its source names a fetchable image. */
    enum class Kind { ISO, DEVICE, TOOL }

    /** Where a row's version, URL and checksum come from. `type`: sums | json | github | link. */
    data class Source(
        val type: String,
        val feed: String = "",
        val index: String = "",
        val file: String = "",
        val base: String = "",
        val download: String = "",
        val path: String = "",
        val select: Map<String, String> = emptyMap(),
        val versionRe: String = "",
        val item: String = "",
        val fields: Map<String, String> = emptyMap(),
        val versionFromUrl: String = "",
        val asset: String = "",
        val sumsAsset: String = "",
    ) {
        val installable get() = type != "link"
    }

    data class Row(
        val id: String,
        val name: String,
        val table: String,
        val kind: Kind,
        val note: String,
        val link: String,
        val source: Source,
        val persistenceLabel: String = "casper-rw",
        val persistenceConf: Boolean = false,
    ) {
        /** Install/Remove are offered only for an image Ventoy boots and whose checksum an official feed publishes. */
        val installable get() = kind != Kind.DEVICE && source.installable
    }

    data class Table(val id: String, val label: String, val blurb: String, val rows: List<Row>)

    data class Catalogue(val afterGroup: String, val label: String, val ventoy: Source, val installerAssets: List<String>, val tables: List<Table>) {
        val rows get() = tables.flatMap { it.rows }
    }

    /** One image an official feed describes. size = -1 when the feed does not say (a HEAD request fills it in). */
    data class Resolved(val version: String, val url: String, val sha256: String, val size: Long, val fileName: String)

    /** The four tables, in render order; the asset may not invent a fifth. */
    val TABLES = listOf("linux", "android-desktop", "android-mobile", "customised")

    private val SHA256 = Regex("^[0-9a-fA-F]{64}$")
    fun isSha256(s: String?): Boolean = s != null && SHA256.matches(s)

    /** Official sources only, and only over TLS: a plain-http or blank URL is a parse error, not a row. */
    fun isOfficialUrl(u: String): Boolean = u.startsWith("https://") && u.length > "https://x.y".length && ' ' !in u

    fun parse(json: String): Catalogue {
        val root = JSONObject(json)
        val tables = root.getJSONArray("tables")
        val seen = HashSet<String>()
        val out = (0 until tables.length()).map { i ->
            val t = tables.getJSONObject(i)
            val id = t.getString("id")
            require(id in TABLES) { "unknown table $id" }
            val rows = t.getJSONArray("rows").let { a -> (0 until a.length()).map { parseRow(a.getJSONObject(it), id) } }
            rows.forEach { require(seen.add(it.id)) { "row id ${it.id} is used twice" } }
            Table(id, t.getString("label"), t.optString("blurb"), rows)
        }
        require(out.map { it.id } == TABLES) { "the tables must be exactly $TABLES, in that order" }
        val v = root.getJSONObject("ventoy")
        val ventoy = parseSource(v.getJSONObject("source"))
        require(ventoy.type == "github" && ventoy.sumsAsset.isNotEmpty()) { "Ventoy itself comes from its GitHub release, checked against the release's sha256 file" }
        val assets = v.getJSONArray("installer_assets").let { a -> (0 until a.length()).map { a.getString(it) } }
        require(assets.isNotEmpty()) { "Copy installer needs at least one release asset" }
        return Catalogue(root.getString("after_group"), root.optString("label", "Ventoy"), ventoy, assets, out)
    }

    private fun parseRow(o: JSONObject, table: String): Row {
        val kind = Kind.valueOf(o.optString("kind", "ISO").uppercase())
        val link = o.getString("link")
        require(isOfficialUrl(link)) { "${o.optString("id")}: link is not an official https URL: $link" }
        val src = o.optJSONObject("source")?.let(::parseSource) ?: Source("link")
        // A phone build is flashed, never booted from Ventoy: the table may only hold DEVICE rows, linked.
        if (table == "android-mobile") require(kind == Kind.DEVICE && !src.installable) { "${o.optString("id")}: android-mobile rows are DEVICE links (flash, not Ventoy)" }
        if (kind == Kind.DEVICE) require(!src.installable) { "${o.optString("id")}: a DEVICE row cannot be installed onto Ventoy" }
        return Row(o.getString("id"), o.getString("name"), table, kind, o.optString("note"), link, src,
            o.optString("persistence_label", "casper-rw"), o.optBoolean("persistence_conf", false))
    }

    private fun parseSource(o: JSONObject): Source {
        fun map(k: String) = o.optJSONObject(k)?.let { m -> m.keys().asSequence().associateWith { m.getString(it) } }.orEmpty()
        val s = Source(o.getString("type"), o.optString("feed"), o.optString("index"), o.optString("file"), o.optString("base"),
            o.optString("download"), o.optString("path"), map("select"), o.optString("version_re"), o.optString("item"),
            map("fields"), o.optString("version_from_url"), o.optString("asset"), o.optString("sums_asset"))
        require(s.type in setOf("sums", "json", "github", "link")) { "unknown source type ${s.type}" }
        if (s.type == "sums" || s.type == "json") require(isOfficialUrl(s.feed.replace("{dir}", "0"))) { "feed is not an official https URL: ${s.feed}" }
        if (s.type == "sums") require(s.file.isNotEmpty() && (s.base.isNotEmpty() || s.download.isNotEmpty())) { "a sums source needs file and base or download" }
        if (s.type == "github") require(Regex("^[\\w.-]+/[\\w.-]+$").matches(s.feed) && s.asset.isNotEmpty()) { "a github source needs owner/repo and asset" }
        return s
    }

    // ── versions ──────────────────────────────────────────────────────────────────────────────────────────────────
    /** "24.04.10" > "24.04.4" > "24.04"; numbers compare as numbers, anything else is a separator. */
    fun compareVersions(a: String, b: String): Int {
        val x = Regex("\\d+").findAll(a).map { it.value.trimStart('0').ifEmpty { "0" } }.toList()
        val y = Regex("\\d+").findAll(b).map { it.value.trimStart('0').ifEmpty { "0" } }.toList()
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrNull(i) ?: return -1
            val q = y.getOrNull(i) ?: return 1
            val c = if (p.length != q.length) p.length.compareTo(q.length) else p.compareTo(q)
            if (c != 0) return c
        }
        return 0
    }

    // ── sums files ────────────────────────────────────────────────────────────────────────────────────────────────
    /** (sha256, file name) pairs of a GNU (`hash  name`, `hash *name`) or BSD (`SHA256 (name) = hash`) sums file. */
    fun sumsLines(text: String): List<Pair<String, String>> = text.lineSequence().mapNotNull { raw ->
        val l = raw.trim()
        Regex("^([0-9a-fA-F]{64})\\s+\\*?(\\S.*)$").find(l)?.let { return@mapNotNull it.groupValues[1].lowercase() to it.groupValues[2].trim() }
        Regex("^SHA256 \\((.+)\\) = ([0-9a-fA-F]{64})$").find(l)?.let { return@mapNotNull it.groupValues[2].lowercase() to it.groupValues[1] }
        null
    }.toList()

    /** The newest file the sums list names that FULLY matches [fileRe] (group 1 = its version). */
    fun fromSums(text: String, src: Source): Resolved? {
        val re = Regex(src.file)
        return sumsLines(text).mapNotNull { (h, n) -> re.matchEntire(n)?.let { m -> Triple(h, n, m.groupValues.getOrElse(1) { n }) } }
            .maxWithOrNull { a, b -> compareVersions(a.third, b.third) }
            ?.let { (h, n, v) -> Resolved(v, src.download.ifEmpty { src.base + n }, h, -1, n) }
    }

    /** The highest version directory a plain HTTP index lists (`href="22.3/"`). */
    fun newestDir(html: String): String? = Regex("href=\"(\\d+(?:\\.\\d+)*)/\"").findAll(html).map { it.groupValues[1] }
        .maxWithOrNull(::compareVersions)

    // ── JSON feeds ────────────────────────────────────────────────────────────────────────────────────────────────
    private fun walk(node: Any?, path: String): Any? = path.split('/').filter { it.isNotEmpty() }.fold(node) { n, seg ->
        when (n) {
            is JSONObject -> n.opt(seg)
            is JSONArray -> seg.toIntOrNull()?.let { n.opt(it) }
            else -> null
        }
    }

    fun fromJson(text: String, src: Source): Resolved? {
        val trimmed = text.trim()
        val root: Any = if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed)
        var node = walk(root, src.path)
        val f = { k: String -> src.fields[k] ?: k }
        if (node is JSONArray) {
            val arr = node
            val vRe = src.versionRe.takeIf { it.isNotEmpty() }?.let(::Regex)
            node = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                .filter { o -> src.select.all { (k, v) -> o.optString(k) == v } }
                .filter { o -> vRe == null || vRe.matches(o.optString(f("version"))) }
                .maxWithOrNull { a, b -> compareVersions(a.optString(f("version")), b.optString(f("version"))) }
        }
        val pick = (walk(node, src.item) as? JSONObject) ?: return null
        val url = pick.optString(f("url")).takeIf(::isOfficialUrl) ?: return null
        val sha = pick.optString(f("sha256")).takeIf(::isSha256)?.lowercase() ?: return null
        val version = src.versionFromUrl.takeIf { it.isNotEmpty() }?.let { Regex(it).find(url)?.groupValues?.get(1) }
            ?: (node as? JSONObject)?.optString(f("version"))?.takeIf { it.isNotEmpty() }
            ?: pick.optString(f("version"))
        val size = pick.opt(f("size"))?.toString()?.toLongOrNull() ?: -1
        return Resolved(version, url, sha, size, url.substringAfterLast('/'))
    }

    // ── GitHub releases ───────────────────────────────────────────────────────────────────────────────────────────
    data class Asset(val name: String, val url: String, val size: Long, val digest: String?)

    fun githubApi(repo: String) = "https://api.github.com/repos/$repo/releases/latest"

    fun githubAssets(releaseJson: String): Pair<String, List<Asset>> {
        val o = JSONObject(releaseJson)
        val a = o.optJSONArray("assets") ?: JSONArray()
        return o.optString("tag_name") to (0 until a.length()).map { a.getJSONObject(it) }.map {
            Asset(it.getString("name"), it.getString("browser_download_url"), it.optLong("size", -1),
                it.optString("digest").removePrefix("sha256:").takeIf(::isSha256)?.lowercase())
        }
    }

    /** The release asset [assetRe] names, its checksum from the release's own sums file or GitHub's asset digest. */
    fun fromGithub(releaseJson: String, assetRe: String, sumsText: String?): Resolved? {
        val (tag, assets) = githubAssets(releaseJson)
        val a = assets.firstOrNull { Regex(assetRe).matches(it.name) } ?: return null
        if (!isOfficialUrl(a.url)) return null
        val sha = sumsText?.let { t -> sumsLines(t).firstOrNull { it.second == a.name }?.first } ?: a.digest ?: return null
        return Resolved(tag.removePrefix("v"), a.url, sha, a.size, a.name)
    }

    // ── checksums ─────────────────────────────────────────────────────────────────────────────────────────────────
    /** True only for two well-formed sha256 values that are equal (case-insensitive). A blank expected never passes. */
    fun matches(actual: String?, expected: String?): Boolean =
        isSha256(actual) && isSha256(expected) && actual!!.equals(expected, ignoreCase = true)

    fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    /** Copies [input] into [out] and returns the sha256 of every byte that passed (JDK copy + DigestOutputStream: a
     *  hashing pipe, not a second download engine; test-download-resume.sh T2). [onBytes] gets the running total. */
    fun copyHashing(input: InputStream, out: OutputStream, onBytes: (Long) -> Unit = {}): String {
        val md = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val counting = object : java.io.FilterOutputStream(out) {
            override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); total += len; onBytes(total) }
            override fun write(b: Int) { out.write(b); total += 1; onBytes(total) }
        }
        input.copyTo(java.security.DigestOutputStream(counting, md), 256 * 1024)
        return hex(md.digest())
    }
}
