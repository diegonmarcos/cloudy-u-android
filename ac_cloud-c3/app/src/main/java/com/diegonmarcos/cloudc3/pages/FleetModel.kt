package com.diegonmarcos.cloudc3.pages

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The Infra section's data, as plain models: Providers, VMs, Serverless Cont (Cloud Run), Storage, Electronics, Domains.
 *
 * THE ONE SOURCE. [parse] reads data/fleet_slice.json, which data/gen_fleet_slice.py derives from the fleet's own
 * declarations in cloud-infra (VMs, regions, IPs and WireGuard IPs from config.json, buckets from the consolidated
 * export, devices from superapp-users.json, the zone's records from terraform.json, and the few facts nobody else
 * carries from inputs/fleet-assets.json). No VM, IP, device or record is spelled in Kotlin, in build.json or in a
 * test; test-c3-infra.sh T3 fails the build if one is.
 *
 * LIVE OVER DECLARED. The disk size a VM declares is shown until c3-infra-api answers GET /health/tier3/{vm}; a
 * provider's monthly cost is shown from its declared fallback until /cloud/{oci,gcp}/costs answers; the zone's
 * declared records are always shown, and /cloud-data/cloudflare-dns adds what the API sees. The parsers for those
 * answers live here (text in, models out) so the JVM suite exercises the exact code the phone runs.
 */
object FleetModel {

    data class Link(val label: String, val url: String)

    /** [live] is a c3-infra-api path whose answer carries the total; [amount]/[currency] are the declared fallback. */
    data class Cost(val live: String?, val amount: Double?, val currency: String?, val note: String?)

    data class Provider(
        val id: String, val label: String, val account: String?, val plan: String?, val use: String?,
        val cost: Cost?, val links: List<Link>, val vms: List<String>, val selfHosted: Boolean = false,
    )

    data class Vm(
        val id: String, val alias: String, val provider: String, val region: String?, val size: String?, val gpu: String?,
        val cpu: Int?, val ramGb: Int?, val diskGb: Int?, val arch: String?, val publicIp: String?, val wgIp: String?,
        val wgPublicIp: String?, val wgRole: String?, val desc: String?,
    )

    data class Bucket(val provider: String, val name: String, val tier: String?, val region: String?, val dns: String?)

    data class Backend(val id: String, val label: String, val provider: String?, val url: String?, val role: String?)

    data class Device(
        val id: String, val model: String, val kind: String, val os: String?, val role: String?,
        val wgIp: String?, val wgPublicIp: String?,
    )

    data class DnsRecord(
        val group: String, val name: String, val type: String, val content: String, val ttl: Int?,
        val proxied: Boolean?, val priority: Int?, val comment: String?,
    )

    data class Domain(
        val name: String, val registrar: String, val admin: String, val renewalPaid: String, val expires: String,
        val nextRenewal: String, val autoRenew: Boolean, val nameservers: List<String>, val verified: String?,
        val records: List<DnsRecord>, val mtaStsMode: String?, val mtaStsMaxAge: Int?,
    )

    /** One serverless container (GCP Cloud Run) of the registry cloudy-t-infra c_vps/vps_gcloud/src/terraform.json. */
    data class CloudRun(
        val name: String, val label: String, val region: String?, val state: String?, val managed: Boolean,
        val image: String?, val port: Int?, val cpu: String?, val memory: String?, val minInstances: Int?, val maxInstances: Int?,
        val fromHost: String?, val source: String?, val persistence: String?, val volumes: List<String>, val route: String?,
        val blockers: List<String>,
    )

    /** A hosting provider of Containers-Node-Python (fleet-assets.json hosting[]): Firebase Hosting, GitHub Pages. */
    data class Hosting(val id: String, val label: String, val plan: String?, val role: String?, val state: String?, val sites: List<String>, val links: List<Link>)

    /** A public mailbox domain the owner uses (fleet-assets.json public_domains[]). */
    data class PublicDomain(val name: String, val provider: String?, val role: String?)

    data class Slice(
        val providers: List<Provider>, val vms: List<Vm>, val buckets: List<Bucket>, val backends: List<Backend>,
        val devices: List<Device>, val domains: List<Domain>, val serverless: List<CloudRun> = emptyList(),
        val hosting: List<Hosting> = emptyList(), val publicDomains: List<PublicDomain> = emptyList(),
    ) {
        fun provider(id: String): Provider? = providers.firstOrNull { it.id == id }
        fun vmsOf(providerId: String): List<Vm> = vms.filter { it.provider == providerId }
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(text: String): Slice {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: return Slice(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        val storage = root.obj("storage")
        return Slice(
            providers = root.objs("providers").map { o ->
                Provider(
                    id = o.str("id").orEmpty(), label = o.str("label").orEmpty(), account = o.str("account"),
                    plan = o.str("plan"), use = o.str("use"),
                    cost = o.obj("cost")?.let { c -> Cost(c.str("live"), c.dbl("amount"), c.str("currency"), c.str("note")) },
                    links = o.objs("links").map { Link(it.str("label").orEmpty(), it.str("url").orEmpty()) },
                    vms = o.strs("vms"), selfHosted = o.bool("self_hosted") ?: false,
                )
            },
            vms = root.objs("vms").map { o ->
                Vm(
                    id = o.str("id").orEmpty(), alias = o.str("alias").orEmpty(), provider = o.str("provider").orEmpty(),
                    region = o.str("region"), size = o.str("size"), gpu = o.str("gpu"), cpu = o.int("cpu"),
                    ramGb = o.int("ram_gb"), diskGb = o.int("disk_gb"), arch = o.str("arch"), publicIp = o.str("public_ip"),
                    wgIp = o.str("wg_ip"), wgPublicIp = o.str("wg_public_ip"), wgRole = o.str("wg_role"),
                    desc = o.str("desc"),
                )
            },
            buckets = storage?.objs("buckets").orEmpty().map { o ->
                Bucket(o.str("provider").orEmpty(), o.str("name").orEmpty(), o.str("tier"), o.str("region"), o.str("dns"))
            },
            backends = storage?.objs("backends").orEmpty().map { o ->
                Backend(o.str("id").orEmpty(), o.str("label").orEmpty(), o.str("provider"), o.str("url"), o.str("role"))
            },
            devices = root.objs("electronics").map { o ->
                Device(
                    id = o.str("id").orEmpty(), model = o.str("model").orEmpty(), kind = o.str("kind").orEmpty(),
                    os = o.str("os"), role = o.str("role"), wgIp = o.str("wg_ip"), wgPublicIp = o.str("wg_public_ip"),
                )
            },
            serverless = root.objs("serverless").map { o ->
                CloudRun(
                    name = o.str("name").orEmpty(), label = o.str("label").orEmpty(), region = o.str("region"),
                    state = o.str("state"), managed = o.bool("managed") ?: false, image = o.str("image"), port = o.int("port"),
                    cpu = o.str("cpu"), memory = o.str("memory"), minInstances = o.int("min_instances"),
                    maxInstances = o.int("max_instances"), fromHost = o.str("from_host"), source = o.str("source"),
                    persistence = o.str("persistence"), volumes = o.strs("volumes"), route = o.str("route"),
                    blockers = o.strs("blockers"),
                )
            },
            hosting = root.objs("hosting").map { o ->
                Hosting(o.str("id").orEmpty(), o.str("label").orEmpty(), o.str("plan"), o.str("role"), o.str("state"), o.strs("sites"),
                    o.objs("links").map { Link(it.str("label").orEmpty(), it.str("url").orEmpty()) })
            },
            publicDomains = root.objs("public_domains").map { o -> PublicDomain(o.str("name").orEmpty(), o.str("provider"), o.str("role")) },
            domains = root.objs("domains").map { o ->
                Domain(
                    name = o.str("name").orEmpty(), registrar = o.str("registrar").orEmpty(), admin = o.str("admin").orEmpty(),
                    renewalPaid = o.str("renewal_paid").orEmpty(), expires = o.str("expires").orEmpty(),
                    nextRenewal = o.str("next_renewal").orEmpty(), autoRenew = o.bool("auto_renew") ?: false,
                    nameservers = o.strs("nameservers"), verified = o.str("verified"),
                    records = o.objs("records").map { r -> record(r) },
                    mtaStsMode = o.obj("mta_sts")?.str("mode"), mtaStsMaxAge = o.obj("mta_sts")?.int("max_age"),
                )
            },
        )
    }

    private fun record(o: JsonObject) = DnsRecord(
        group = o.str("group").orEmpty(), name = o.str("name").orEmpty(), type = o.str("type").orEmpty(),
        content = o.str("content").orEmpty(), ttl = o.int("ttl"), proxied = o.bool("proxied"),
        priority = o.int("priority"), comment = o.str("comment"),
    )

    // ── Storage: hardware and backends only ─────────────────────────────────

    /** One storage row on the Storage page. [usage] is filled from the live answer when there is one. */
    data class StorageRow(val kind: String, val name: String, val detail: String, val usage: String?)

    /** The section a Storage row belongs to, in display order. */
    val STORAGE_KINDS: List<String> = listOf("S3 bucket", "Cloud drive", "VM disk", "Device storage")

    /**
     * The Storage page's rows: S3 buckets, cloud-drive backends, each VM's disk (declared size; [live] adds the usage)
     * and the devices' own storage (plus this phone's live volume when [thisPhone] is given). DB containers are NOT storage hardware and are never rows here: they live in
     * Containers (Infra and User Dbs), and test-c3-infra.sh T2 and InfraScreensComposeTest fail the build if one appears.
     */
    fun storageRows(s: Slice, live: Map<String, DiskUsage> = emptyMap(), thisPhone: String? = null): List<StorageRow> {
        val rows = mutableListOf<StorageRow>()
        s.buckets.forEach { b ->
            rows += StorageRow("S3 bucket", b.name, listOfNotNull(s.provider(b.provider)?.label ?: b.provider, b.tier, b.region, b.dns).joinToString(" - "), null)
        }
        s.backends.forEach { b ->
            rows += StorageRow("Cloud drive", b.label, listOfNotNull(b.provider, b.role).joinToString(" - "), null)
        }
        s.vms.forEach { v ->
            val u = live[v.id] ?: live[v.alias]
            val declared = v.diskGb?.takeIf { it > 0 }?.let { "$it GB declared" } ?: "size not declared"
            rows += StorageRow("VM disk", v.alias.ifBlank { v.id }, "${v.id} - $declared", u?.line())
        }
        s.devices.forEach { d ->
            rows += StorageRow("Device storage", d.model, "${d.kind} - ${d.os ?: "OS not declared"} - capacity not declared", null)
        }
        if (thisPhone != null) rows += StorageRow("Device storage", "This phone (live)", "app data volume, read from the OS", thisPhone)
        return rows
    }

    // ── one-line renderings (pure, so the JVM suite checks what the screens print) ──

    /** "region | size | N vCPU | N GB RAM | arch" (a missing part is skipped). A disk of 0 is "not declared", not a 0 GB disk. */
    fun vmSpec(v: Vm): String = listOfNotNull(
        v.region, v.size ?: v.gpu, v.cpu?.let { "$it vCPU" }, v.ramGb?.let { "$it GB RAM" },
        v.diskGb?.takeIf { it > 0 }?.let { "$it GB disk" }, v.arch,
    ).joinToString(" | ")

    /** "region | N vCPU | memory | min-max instances | port N" for a Cloud Run row (a missing part is skipped). */
    fun runSpec(s: CloudRun): String = listOfNotNull(
        s.region, s.cpu?.let { "$it vCPU" }, s.memory, s.maxInstances?.let { "${s.minInstances ?: 0}-$it instances" },
        s.port?.let { "port $it" },
    ).joinToString(" | ")

    /** "live (not yet Terraform-managed)" / "declared, moving from <vm>" / "live (Terraform)": where the service is in its lifecycle. */
    fun runState(s: CloudRun): String = when (s.state) {
        "live-unmanaged" -> "live (not yet Terraform-managed)"
        "live-managed" -> "live (Terraform)"
        "declared" -> "declared" + (s.fromHost?.let { ", moving from $it" }.orEmpty())
        else -> s.state ?: "state not declared"
    }

    fun vmNet(v: Vm): String =
        "public ${v.publicIp ?: "-"} | wg0 ${v.wgIp ?: "-"}${v.wgRole?.let { " ($it)" }.orEmpty()} | wg-public ${v.wgPublicIp ?: "-"}"

    /** The device kinds the Electronics page always lists, in order; a kind with no declared device says so. */
    val DEVICE_KINDS: List<Pair<String, String>> = listOf(
        "notebook" to "PC", "phone" to "Phone", "tablet" to "Tablet", "smartwatch" to "Smartwatch",
    )

    /** The device kinds of each Personal page; Gadgets also takes every kind no other page names. */
    val PERSONAL_KINDS: Map<String, List<String>> = mapOf(
        "desktop" to listOf("notebook", "desktop"), "mobile" to listOf("phone", "tablet"), "gadgets" to listOf("smartwatch"),
    )

    fun personalKinds(page: String, s: Slice): List<String> {
        val own = PERSONAL_KINDS[page].orEmpty()
        if (page != "gadgets") return own
        val named = PERSONAL_KINDS.values.flatten().toSet()
        return own + s.devices.map { it.kind }.distinct().filter { it !in named }
    }

    fun kindLabel(kind: String): String =
        DEVICE_KINDS.firstOrNull { it.first == kind }?.second ?: kind.replaceFirstChar { it.uppercaseChar() }

    /** Whole days from [today] to an ISO date (negative once past); null if [date] is not one. */
    fun daysUntil(date: String, today: java.time.LocalDate): Long? =
        runCatching { java.time.temporal.ChronoUnit.DAYS.between(today, java.time.LocalDate.parse(date)) }.getOrNull()

    /** A provider's cost line: the live total if the API gave one, else the declared fallback, else "not declared". */
    fun costLine(p: Provider, live: String?, why: String?): String = when {
        live != null -> "Monthly cost: $live (live)"
        declaredCost(p.cost) != null -> "Monthly cost: ${declaredCost(p.cost)} (declared)" + (why?.let { " - live unavailable: $it" } ?: "")
        p.cost?.live != null -> "Monthly cost: not read yet" + (why?.let { " - live unavailable: $it" } ?: "")
        else -> "Monthly cost: not declared"
    }

    // ── live answers ────────────────────────────────────────────────────────

    data class DiskUsage(val total: String, val used: String, val percent: String) {
        fun line() = "$used / $total ($percent)"
    }

    /** GET /health/tier3[/{vm}]: `[{vm, alias, resources:{diskTotal, diskUsed, diskPercent}}]` -> usage by vm id AND alias. */
    fun parseDisks(body: String): Map<String, DiskUsage> {
        val arr = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonArray ?: return emptyMap()
        val out = mutableMapOf<String, DiskUsage>()
        arr.mapNotNull { it as? JsonObject }.forEach { o ->
            val r = o.obj("resources") ?: return@forEach
            val total = r.str("diskTotal") ?: return@forEach
            val u = DiskUsage(total, r.str("diskUsed") ?: "?", r.str("diskPercent") ?: "?")
            o.str("vm")?.let { out[it] = u }
            o.str("alias")?.let { out[it] = u }
        }
        return out
    }

    /** GET /cloud/{oci,gcp}/costs: `{ok, costs:[{service, amount, currency, period}]}` -> the TOTAL row, as "9.74 EUR (October 2026)". */
    fun parseCostTotal(body: String): String? {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
        val total = root.objs("costs").firstOrNull { it.str("service").orEmpty().contains("TOTAL", ignoreCase = true) } ?: return null
        val amount = total.dbl("amount") ?: return null
        val period = total.str("period")?.substringBefore(" - ")?.substringBefore(" — ")
        return formatMoney(amount, total.str("currency") ?: "") + (if (period.isNullOrBlank()) "" else " ($period)")
    }

    fun formatMoney(amount: Double, currency: String): String =
        String.format(java.util.Locale.US, "%.2f", amount) + (if (currency.isBlank()) "" else " $currency")

    /** The declared fallback shown until (or instead of) the live total. */
    fun declaredCost(c: Cost?): String? {
        if (c == null) return null
        val amount = c.amount ?: return null
        return formatMoney(amount, c.currency.orEmpty()) + (c.note?.let { n -> " - $n" } ?: "")
    }

    /** GET /cloud-data/cloudflare-dns: `{zone, records:[{name,type,content,proxied?,origin,service?}]}`. */
    fun parseLiveDns(body: String): List<DnsRecord> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return emptyList()
        return root.objs("records").map { o ->
            DnsRecord("Live", o.str("name").orEmpty(), o.str("type").orEmpty(), o.str("content").orEmpty(), o.int("ttl"),
                o.bool("proxied"), o.int("priority"), o.str("service") ?: o.str("origin"))
        }
    }

    /** The mail-signing records of a domain, by group, in the order the page lists them. */
    val MAIL_GROUPS: List<String> = listOf("MX", "SPF", "DKIM", "DMARC", "MTA-STS", "TLS-RPT")

    fun recordsOf(d: Domain, group: String): List<DnsRecord> = d.records.filter { it.group == group }

    /** The non-mail groups, in order of first appearance. */
    fun otherGroups(d: Domain): List<String> = d.records.map { it.group }.distinct().filter { it !in MAIL_GROUPS }

    // ── json helpers ────────────────────────────────────────────────────────

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.dbl(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.objs(key: String): List<JsonObject> = (this[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    private fun JsonObject.strs(key: String): List<String> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
}
