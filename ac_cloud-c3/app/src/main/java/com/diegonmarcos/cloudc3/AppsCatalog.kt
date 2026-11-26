package com.diegonmarcos.cloudc3

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The Apps tab's Services and Tools, as pure data (JVM-testable, no Android types).
 *
 * Two inputs, both baked: ui.apps_page (WHICH tools exist and which composite uses which) and
 * data/infra_slice.json (WHAT they are, derived by gen_infra_slice.py from cloud-infra's
 * consolidated export). Nothing about a host, zone or rule is spelled in this file; the only
 * knowledge here is how to lay a view out, one renderer per declared view id.
 */
object AppsCatalog {

    data class Tool(val id: String, val source: String) {
        /** Derived from the id, never stored (#351): caddy-public -> "Caddy Public". */
        val display: String get() = id.split('-', '_').joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
    }

    data class Service(val id: String, val label: String, val icon: String, val tools: List<String>, val views: List<String>)

    data class Block(val title: String, val rows: List<Pair<String, String>>)

    data class Decl(val tools: List<Tool>, val services: List<Service>) {
        fun tool(id: String) = tools.firstOrNull { it.id == id }
        fun service(id: String) = services.firstOrNull { it.id == id }
        /** The inverse of services[].tools - derived, so it cannot disagree. */
        fun usedBy(toolId: String): List<Service> = services.filter { toolId in it.tools }
    }

    private fun JsonObject?.str(k: String) = (this?.get(k) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject?.obj(k: String) = this?.get(k) as? JsonObject
    private fun JsonObject?.arr(k: String): JsonArray = (this?.get(k) as? JsonArray) ?: JsonArray(emptyList())
    private fun JsonArray.objs() = mapNotNull { it as? JsonObject }
    private fun JsonArray.strs() = mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    fun parse(text: String): Decl {
        val o = Declarations.element(text) as? JsonObject ?: return Decl(emptyList(), emptyList())
        val tools = o.arr("tools").objs().mapNotNull { t ->
            val id = t.str("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Tool(id, t.str("source").orEmpty())
        }
        val services = o.arr("services").objs().mapNotNull { s ->
            val id = s.str("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Service(id, s.str("label") ?: id, s.str("icon").orEmpty(), s.arr("tools").strs(), s.arr("views").strs())
        }
        return Decl(tools, services)
    }

    /** The view ids a renderer exists for. test-c3-shell.sh T9 diffs this against build.json. */
    val VIEW_IDS = listOf(
        "authelia_policies", "wg_peers", "caddy_security", "vm_firewalls", "container_mesh", "docker_mgmt",
        "hickory_zones", "cloudflare_dns", "caddy_routes",
        "mail_routing", "mail_signatures", "jmap_config", "mail_hosts",
    )

    private fun JsonObject.hosts() = arr("hosts").objs()

    /** One declared view of a service, laid out over the slice. */
    fun view(slice: JsonObject, id: String): List<Block> = when (id) {
        "authelia_policies" -> {
            val byAuth = slice.hosts().groupBy { it.str("auth") ?: "default" }
            listOf(Block("Access policy per declared host (${slice.hosts().size})",
                byAuth.toSortedMap().map { (a, hs) -> a to hs.joinToString(", ") { it.str("host").orEmpty() } }))
        }
        "wg_peers" -> {
            val w = slice.obj("wireguard")
            listOfNotNull("wg0" to "wg0 (private mesh)", "wg_public" to "wg-public").mapNotNull { (k, label) ->
                val m = w.obj(k) ?: return@mapNotNull null
                Block("$label - ${m.str("subnet")} udp/${m.str("port")} hub ${m.str("hub")}",
                    m.arr("peers").objs().map { (it.str("name") ?: "?") to listOfNotNull(it.str("wg_ip"), it.str("role"), "key: declared (not shown)").joinToString(" - ") })
            }
        }
        "caddy_security" -> {
            val c = slice.obj("caddy")
            val rows = slice.hosts().map {
                (it.str("host") ?: "?") to ((if (it["wg_only"].toString() == "true") "wg_only" else "public") + " - auth " + (it.str("auth") ?: "default"))
            }
            listOfNotNull(
                c?.let { Block("Global rules", listOfNotNull(
                    "mesh CIDRs" to it.str("wg_cidrs").orEmpty(),
                    it.obj("rate_limit")?.let { r -> "rate limit" to "${r.str("events")} per ${r.str("window")}" },
                    "bot blocker" to "${it.str("bot_agents")} user agents",
                    "scanner blocker" to "${it.str("scanner_paths")} paths",
                    "HSTS max-age" to it.str("hsts_max_age").orEmpty())) },
                Block("Per host (${rows.size})", rows))
        }
        "vm_firewalls" -> {
            val fw = slice.obj("firewalls")
            val g = fw.obj("global")
            listOfNotNull(
                g?.let { Block("Global", it.entries.filter { e -> e.value is JsonPrimitive }.map { e -> e.key to (e.value as JsonPrimitive).content }) }
            ) + (fw.obj("vms")?.entries.orEmpty().map { (vm, rules) ->
                Block("$vm (${(rules as JsonArray).size} rules)", rules.objs().map {
                    "${it.str("port")}/${it.str("proto")}" to "${it.str("source")} - ${it.str("desc")}" })
            })
        }
        "container_mesh" -> listOf(
            Block("Containers per VM", slice.obj("containers_per_vm")?.entries.orEmpty().map { it.key to (it.value as JsonPrimitive).content }),
            Block("Internal .app names (${slice.obj("dns").arr("entries")?.size ?: 0})",
                slice.obj("dns").arr("entries").objs().take(200).map { (it.str("name") ?: "?") to (it.str("value") ?: "") }),
        )
        "docker_mgmt" -> slice.obj("tools").obj("docker")?.let {
            listOf(Block("Docker", listOfNotNull(
                it.str("description")?.let { d -> "description" to d }, it.str("subnet")?.let { s -> "subnet" to s },
                it.str("iptables")?.let { s -> "docker-managed iptables" to s })))
        }.orEmpty() + listOf(Block("Containers per VM", slice.obj("containers_per_vm")?.entries.orEmpty().map { it.key to (it.value as JsonPrimitive).content }))
        "hickory_zones" -> {
            val d = slice.obj("dns")
            val ents = d.arr("entries").objs()
            listOfNotNull(
                d?.let { Block("Resolvers", listOf("primary" to d.str("primary").orEmpty(), "note" to d.str("description").orEmpty())) },
                Block("Records (${ents.size})", ents.map { (it.str("name") ?: "?") to "${it.str("type")} ${it.str("value")}" }))
        }
        "cloudflare_dns" -> listOf(Block("Cloudflare DNS",
            listOf("records declared" to (slice.obj("dns")?.get("cloudflare_records")?.let { (it as? JsonPrimitive)?.intOrNull }?.toString() ?: "?"),
                "public hosts routed" to slice.hosts().count { it["wg_only"].toString() != "true" }.toString())))
        "caddy_routes" -> listOf(Block("Routes (${slice.hosts().size})",
            slice.hosts().map { (it.str("host") ?: "?") to "${it.str("service")} @ ${it.str("vm")}" }))
        "mail_routing" -> {
            val m = slice.obj("mail")
            listOfNotNull(
                m?.let { Block("Outbound routes", it.arr("routes").objs().map { r ->
                    (r.str("name") ?: "?") to "${r.str("address")}:${r.str("port")}" + (if (r["default"].toString() == "true") " - default" else "") }) },
                slice.obj("tools").obj("stalwart")?.let { Block("Inbound listeners (stalwart)", it.arr("ports").objs().map { p -> "${p.str("port")} ${p.str("service")}" to p.str("desc").orEmpty() }) },
                slice.obj("tools").obj("maddy")?.let { Block("Inbound listeners (maddy)", it.arr("ports").objs().map { p -> "${p.str("port")} ${p.str("service")}" to p.str("desc").orEmpty() }) })
        }
        "mail_signatures" -> {
            val m = slice.obj("mail")
            val s = m.obj("mta_sts")
            listOfNotNull(
                m?.let { Block("DKIM", listOf("signing key secret names" to it.arr("dkim_secret_names").strs().joinToString(", ").ifBlank { "none declared" })) },
                s?.takeIf { it.isNotEmpty() }?.let { Block("MTA-STS", listOf("domain" to it.str("domain").orEmpty(), "mode" to it.str("mode").orEmpty(),
                    "max age" to it.str("max_age").orEmpty(), "mx" to it.arr("mx").strs().joinToString(", "))) },
                Block("SPF / DMARC", listOf("declared" to "not in cloud-infra's consolidated export (DNS TXT records are not declared there)")))
        }
        "jmap_config" -> slice.obj("tools").obj("stalwart")?.let {
            listOf(Block("JMAP (stalwart)", listOfNotNull(
                it.str("domain")?.let { d -> "host" to d }, it.str("port")?.let { p -> "port" to p },
                "well-known (caddy)" to slice.obj("tools").obj("caddy").arr("well_known").strs().joinToString(", ").ifBlank { "none" })))
        }.orEmpty()
        "mail_hosts" -> listOf(Block("Mail hosts", slice.obj("mail").arr("hosts").objs().map {
            (it.str("service") ?: "?") to "${it.str("host") ?: "-"} @ ${it.str("vm") ?: "-"}" }))
        else -> emptyList()
    }

    /** One engine on its own: config, URLs, declared health, then its listeners. */
    fun toolBlocks(slice: JsonObject, id: String): List<Block> {
        val t = slice.obj("tools").obj(id) ?: return emptyList()
        val rows = listOfNotNull(
            t.str("description")?.takeIf { it.isNotBlank() }?.let { "about" to it },
            t.str("vm")?.let { "vm" to it }, t.str("dns")?.let { "mesh name" to it },
            t.str("port")?.let { "port" to it },
            t.arr("images").strs().takeIf { it.isNotEmpty() }?.let { "image" to it.joinToString(", ") },
        )
        val urls = listOfNotNull(
            t.str("domain")?.let { "url" to "https://$it" },
            t.str("auth")?.let { "auth" to it },
            t["wg_only"]?.toString()?.takeIf { it == "true" }?.let { "exposure" to "wg_only (mesh only)" },
        ) + t.arr("well_known").strs().map { "well-known" to it }
        val health = listOf("declared check" to (t.str("health") ?: "none declared"),
            "live status" to "see Ops > Health > Health")
        return listOfNotNull(Block("Config", rows), urls.takeIf { it.isNotEmpty() }?.let { Block("URLs", it) }, Block("Health", health),
            t.arr("ports").objs().takeIf { it.isNotEmpty() }?.let { Block("Listeners", it.map { p -> "${p.str("port")} ${p.str("service")}" to p.str("desc").orEmpty() }) },
            t.arr("secret_names").strs().takeIf { it.isNotEmpty() }?.let { Block("Secrets (names only)", it.map { n -> n to "" }) })
    }
}
