package com.diegonmarcos.cgomniverse

/**
 * #1784 the Voxelverse tab, pure part: the declaration (build.json ui.stacks.voxelverse + string resources), the addresses it implies, and the readers of the
 * plan document (markdown, fetched at run time from the owner's repo) and of the repo's status (the repo's commit feed). Nothing of the plan
 * is stored here: the page shows what the repo holds now. Pure, so the JVM suite covers it.
 */
data class VoxSection(val id: String, val heading: String, val title: String)
data class VoxPhase(val title: String, val summary: String, val gate: String)
data class VoxCommit(val sha: String, val date: String, val subject: String)
data class VoxBlock(val title: String, val bullets: List<String>, val paragraphs: List<String>)

/**
 * The Voxelverse declaration: [repoUrl] is the declared repo's page (build.json ui.stacks.voxelverse: repo + path resolved through
 * the SuperApp's repo_base, so no address is typed here), [planPath] the plan document in it, [sections] which `## ` headings are
 * drawn, [text] the page's words (string resources). Every address below derives from [repoUrl]; no API key and no GraphQL.
 */
class VoxelverseDecl(
    val repoUrl: String, val planPath: String, val sections: List<VoxSection>, val roadmapHeading: String, val roadmapTitle: String,
    val engineMarker: String, val text: Map<String, String>,
) {
    fun t(key: String): String = text[key].orEmpty()
    private val repo: String = repoUrl.substringBefore("/tree/").trimEnd('/')
    val repoName: String get() = repo.substringAfter("//").substringAfter('/')
    val planUrl: String get() = "$repo/raw/main/$planPath"
    val planWebUrl: String get() = "$repo/blob/main/$planPath"
    val repoWebUrl: String get() = repo
    /** The repo's commit feed (Atom): its first entry is the latest commit. */
    val commitUrl: String get() = "$repo/commits/main.atom"
    /** A file at the repo root: 200 when it exists (the Rust workspace's Cargo.toml). */
    val markerUrl: String get() = "$repo/raw/main/$engineMarker"
}

object Voxelverse {
    /** The sections and roadmap this page draws: plan headings (the repo's own words) and the labels shown for them. */
    fun decl(repoUrl: String, planPath: String, text: Map<String, String>): VoxelverseDecl = VoxelverseDecl(
        repoUrl, planPath,
        listOf(VoxSection("goals", "Goals and non-goals", text["goals"].orEmpty()),
            VoxSection("architecture", "Architecture", text["architecture"].orEmpty()),
            VoxSection("risks", "Risks, open questions, rejected options", text["risks"].orEmpty())),
        "Roadmap", text["milestones"].orEmpty(), "Cargo.toml", text,
    )

    /** The `## ` sections of a markdown document: heading to body, in order (text before the first `## ` is dropped). */
    fun sections(md: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var head: String? = null
        val body = StringBuilder()
        for (line in md.lines()) {
            if (line.startsWith("## ")) {
                head?.let { out.add(it to body.toString().trim()) }
                head = line.removePrefix("## ").trim(); body.clear()
            } else if (head != null) body.append(line).append('\n')
        }
        head?.let { out.add(it to body.toString().trim()) }
        return out
    }

    /** Markdown inline marks out: bold, code, escapes, links keep their text, embed placeholders vanish. */
    fun clean(s: String): String = s
        .replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1")
        .replace("**", "").replace("`", "")
        .replace(Regex("\\\\([_*\\[\\]#])"), "$1")
        .trim()

    /** One section body as a block: bullets (`- `) and paragraphs, tables and embed placeholders skipped. */
    fun block(title: String, body: String): VoxBlock {
        val bullets = ArrayList<String>()
        val paras = ArrayList<String>()
        for (raw in body.lines()) {
            val l = raw.trim()
            when {
                l.isEmpty() || l.startsWith("|") || l.startsWith("&#91;") || l.startsWith("---") -> {}
                l.startsWith("- ") || l.startsWith("* ") -> bullets.add(clean(l.substring(2)))
                Regex("^\\d+\\. ").containsMatchIn(l) -> bullets.add(clean(l.replace(Regex("^\\d+\\. "), "")))
                else -> paras.add(clean(l))
            }
        }
        return VoxBlock(title, bullets, paras)
    }

    /** The blocks the declaration names, in its order, from the fetched document; a missing heading yields no block. */
    fun plan(decl: VoxelverseDecl, md: String): List<VoxBlock> {
        val secs = sections(md).toMap()
        return decl.sections.mapNotNull { s -> secs[s.heading]?.let { block(s.title, it) } }
    }

    /** The roadmap's numbered list `N. **Title (time)** - summary. Gate: ...` as phases. */
    fun phases(decl: VoxelverseDecl, md: String): List<VoxPhase> {
        val body = sections(md).toMap()[decl.roadmapHeading] ?: return emptyList()
        val re = Regex("^\\d+\\.\\s+\\*\\*(.+?)\\*\\*\\s*[—-]?\\s*(.*)$")
        return body.lines().mapNotNull { re.find(it.trim()) }.map { m ->
            val rest = clean(m.groupValues[2])
            val i = rest.indexOf("Gate:")
            VoxPhase(clean(m.groupValues[1]), (if (i >= 0) rest.substring(0, i) else rest).trim().trimEnd('.', ' '), if (i >= 0) rest.substring(i + 5).trim() else "")
        }
    }

    /** The newest entry of the repo's commit Atom feed: short id, date, first line of the message; null when it is not a feed. */
    fun commit(atom: String): VoxCommit? = runCatching {
        val e = atom.substringAfter("<entry>")
        val id = Regex("<id>[^<]*?([0-9a-f]{7,40})</id>").find(e)!!.groupValues[1].take(7)
        val date = Regex("<updated>(\\d{4}-\\d{2}-\\d{2})").find(e)!!.groupValues[1]
        val title = Regex("<title[^>]*>([^<]*)</title>").find(e)!!.groupValues[1].trim()
        VoxCommit(id, date, title.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'"))
    }.getOrNull()
}
