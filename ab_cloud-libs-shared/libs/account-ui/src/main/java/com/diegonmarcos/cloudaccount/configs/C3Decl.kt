package com.diegonmarcos.cloudaccount.configs

/**
 * #1438 Configs > C3, as pure parsing (no Android, no network): what a declaration says, with every VALUE dropped at
 * parse time so nothing downstream can draw one.
 *
 * [ComposeDecl] reads a docker-compose file (the generated dist/ compose of cloudy-u-containers) and keeps, per service:
 * the env NAMES (`- KEY=value` and `KEY: value` both give KEY only), the secret names, the env files, the volumes and
 * the bind mounts. [TfDecl] reads Terraform and keeps the provider names, the resource types and names and the
 * variable NAMES, never an assignment's right-hand side.
 */
class ServiceDecl(
    val name: String,
    val image: String,
    val envNames: List<String>,
    val secretNames: List<String>,
    val envFiles: List<String>,
    val volumes: List<String>,
)

object ComposeDecl {
    private val KEY = Regex("^[A-Za-z_][A-Za-z0-9_.-]*$")

    private fun indent(l: String) = l.length - l.trimStart().length
    private fun unquote(s: String) = s.trim().removeSurrounding("\"").removeSurrounding("'")

    /** The NAME of an environment entry, list form (`KEY=value`, `KEY`) or map form (`KEY: value`); null when it is no name. */
    fun envName(item: String): String? {
        val t = unquote(item)
        val n = t.substringBefore('=').substringBefore(':').trim()
        return n.takeIf { KEY.matches(it) }
    }

    /** A volume as `source:target[:mode]`; an env interpolation in it (`${X}`) is kept as its name only. */
    private fun volume(item: String): String = unquote(item).replace(Regex("\\$\\{([A-Za-z_][A-Za-z0-9_]*)[^}]*}")) { "\${" + it.groupValues[1] + "}" }

    /** Generated compose files are often JSON (valid YAML): read those with org.json, names only, like [parse]. */
    private fun parseJson(text: String): List<ServiceDecl> {
        val svcs = runCatching { org.json.JSONObject(text).optJSONObject("services") }.getOrNull() ?: return emptyList()
        fun strs(v: Any?, key: String? = null): List<String> = when (v) {
            is org.json.JSONArray -> (0 until v.length()).mapNotNull { i ->
                when (val x = v.opt(i)) { is org.json.JSONObject -> key?.let { x.optString(it).ifEmpty { null } }; null -> null; else -> x.toString() }
            }
            is String -> listOf(v)
            else -> emptyList()
        }
        return svcs.keys().asSequence().sorted().map { name ->
            val o = svcs.optJSONObject(name) ?: org.json.JSONObject()
            val env = when (val e = o.opt("environment")) {
                is org.json.JSONObject -> e.keys().asSequence().toList()
                is org.json.JSONArray -> strs(e).mapNotNull(::envName)
                else -> emptyList()
            }
            val vols = (o.optJSONArray("volumes")?.let { a -> (0 until a.length()).map { i ->
                when (val x = a.opt(i)) { is org.json.JSONObject -> listOfNotNull(x.optString("source").ifEmpty { null }, x.optString("target").ifEmpty { null }).joinToString(":"); else -> volume(x.toString()) }
            } } ?: emptyList()).filter { it.isNotEmpty() }
            ServiceDecl(name, o.optString("image").substringBefore('@'), env.filter { KEY.matches(it) }.distinct(),
                strs(o.opt("secrets"), "source").distinct(), strs(o.opt("env_file"), "path").distinct(), vols.distinct())
        }.toList()
    }

    fun parse(text: String): List<ServiceDecl> {
        if (text.trimStart().startsWith("{")) return parseJson(text)
        val lines = text.lines().filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
        val out = mutableListOf<ServiceDecl>()
        var i = lines.indexOfFirst { indent(it) == 0 && it.trimEnd() == "services:" }
        if (i < 0) return out
        i++
        val svcIndent = lines.getOrNull(i)?.let(::indent) ?: return out
        while (i < lines.size && indent(lines[i]) >= svcIndent && indent(lines[i]) > 0) {
            val head = lines[i]
            if (indent(head) != svcIndent || !head.trimEnd().endsWith(":")) { i++; continue }
            val name = head.trim().removeSuffix(":")
            var image = ""
            val env = mutableListOf<String>(); val secrets = mutableListOf<String>(); val files = mutableListOf<String>(); val vols = mutableListOf<String>()
            i++
            var key = ""; var keyIndent = -1; var itemIndent = -1
            var longVol: MutableMap<String, String>? = null
            fun flushVol() { longVol?.let { m -> vols += listOfNotNull(m["source"], m["target"]).joinToString(":") }; longVol = null }
            while (i < lines.size && indent(lines[i]) > svcIndent) {
                val l = lines[i]; val t = l.trim(); val ind = indent(l)
                if (keyIndent < 0 || ind <= keyIndent) {
                    flushVol()
                    keyIndent = ind; itemIndent = -1
                    key = t.substringBefore(':').trim()
                    val rest = t.substringAfter(':', "").trim()
                    when (key) {
                        "image" -> image = unquote(rest).substringBefore('@')
                        "env_file" -> if (rest.isNotEmpty() && !rest.startsWith("[")) files += unquote(rest)
                            else if (rest.startsWith("[")) files += rest.trim('[', ']').split(',').map(::unquote).filter { it.isNotEmpty() }
                        "secrets" -> if (rest.startsWith("[")) secrets += rest.trim('[', ']').split(',').map(::unquote).filter { it.isNotEmpty() }
                    }
                } else if (itemIndent >= 0 && ind > itemIndent && key != "volumes") {
                    // a continuation (block scalar, nested map) of an entry: part of a VALUE, never a name
                } else when (key.also { if (itemIndent < 0) itemIndent = ind }) {
                    "environment" -> (if (t.startsWith("- ")) envName(t.removePrefix("- ")) else envName(t))?.let { env += it }
                    "secrets" -> when {
                        t.startsWith("- source:") -> secrets += unquote(t.removePrefix("- source:"))
                        t.startsWith("- ") -> secrets += unquote(t.removePrefix("- "))
                    }
                    "env_file" -> when {
                        t.startsWith("- path:") -> files += unquote(t.removePrefix("- path:"))
                        t.startsWith("- ") -> files += unquote(t.removePrefix("- "))
                    }
                    "volumes" -> when {
                        t.startsWith("- ") && t.removePrefix("- ").let { it.contains(": ") || it.endsWith(":") } -> {
                            flushVol(); longVol = mutableMapOf()
                            val kv = t.removePrefix("- "); longVol!![kv.substringBefore(':').trim()] = unquote(kv.substringAfter(':'))
                        }
                        t.startsWith("- ") -> { flushVol(); vols += volume(t.removePrefix("- ")) }
                        longVol != null && t.contains(':') -> longVol!![t.substringBefore(':').trim()] = unquote(t.substringAfter(':'))
                    }
                }
                i++
            }
            flushVol()
            out += ServiceDecl(name, image, env.distinct(), secrets.distinct(), files.distinct(), vols.distinct())
        }
        return out
    }
}

class TfSummary(val providers: List<String>, val resources: List<String>, val variables: List<String>)

object TfDecl {
    private val PROVIDER = Regex("""(?m)^\s*provider\s+"([^"]+)"""")
    /** A required_providers `source = "namespace/name"` (two parts: a module source has three, or a path). */
    private val REQUIRED = Regex("""\bsource\s*=\s*"([A-Za-z0-9-]+/[A-Za-z0-9-]+)"""")
    private val RESOURCE = Regex("""(?m)^\s*(?:resource|data)\s+"([^"]+)"\s+"([^"]+)"""")
    private val VARIABLE = Regex("""(?m)^\s*variable\s+"([^"]+)"""")

    fun parse(text: String): TfSummary = TfSummary(
        (PROVIDER.findAll(text).map { it.groupValues[1] } + REQUIRED.findAll(text).map { it.groupValues[1] }).distinct().toList(),
        RESOURCE.findAll(text).map { "${it.groupValues[1]}.${it.groupValues[2]}" }.distinct().toList(),
        VARIABLE.findAll(text).map { it.groupValues[1] }.distinct().toList(),
    )
}

/** A path tree for Configs > Desktop: directories first, then files, each sorted. */
class TreeNode(val name: String, val path: String) {
    val children = sortedMapOf<String, TreeNode>()
    val isDir get() = children.isNotEmpty()

    fun add(rel: String) {
        var n = this
        val parts = rel.split('/').filter { it.isNotEmpty() }
        for ((ix, part) in parts.withIndex()) {
            n = n.children.getOrPut(part) { TreeNode(part, parts.take(ix + 1).joinToString("/")) }
        }
    }

    /** Depth-first rows (depth, node), [open] deciding which directories show their children. */
    fun rows(open: (String) -> Boolean, depth: Int = 0): List<Pair<Int, TreeNode>> {
        val kids = children.values.sortedWith(compareBy({ !it.isDir }, { it.name }))
        return kids.flatMap { k -> listOf(depth to k) + if (k.isDir && open(k.path)) k.rows(open, depth + 1) else emptyList() }
    }

    fun fileCount(): Int = if (!isDir) 1 else children.values.sumOf { it.fileCount() }

    companion object {
        /** The tree of [paths] under [root] whose name matches [match], minus any path through an [exclude]d directory. */
        fun of(paths: List<String>, root: String, match: Regex, exclude: List<String>): TreeNode {
            val t = TreeNode(root, root)
            val prefix = root.trimEnd('/') + "/"
            for (p in paths) {
                if (!p.startsWith(prefix)) continue
                val rel = p.removePrefix(prefix)
                if (rel.split('/').any { seg -> exclude.any { seg.equals(it, ignoreCase = true) } }) continue
                if (!match.containsMatchIn(rel.substringAfterLast('/'))) continue
                t.add(rel)
            }
            return t
        }
    }
}
