package com.diegonmarcos.cloudc3.pages

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * #1471 Infra > APIs: the third-party API providers, read from data/api_providers.json (text in, models out). The groups,
 * the providers and the page each group answers are all DATA; nothing here names a provider or a group. A secret is carried
 * as a NAME and a kind only - there is no field that could hold a value, and the parser ignores every other key.
 */
object ApiProviders {

    data class Group(val id: String, val label: String, val page: String)
    data class Secret(val name: String, val kind: String)
    data class Provider(
        val id: String, val label: String, val group: String, val category: String, val links: List<FleetModel.Link>,
        val apps: List<String>, val containers: List<String>, val auth: String, val secrets: List<Secret>,
    )
    data class Decl(val groups: List<Group> = emptyList(), val providers: List<Provider> = emptyList()) {
        /** The group an Infra page id shows, or null when the page is not an API page. */
        fun groupOfPage(page: String): Group? = groups.firstOrNull { it.page == page }
        fun of(group: Group): List<Provider> = providers.filter { it.group == group.id }
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(text: String): Decl {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return Decl()
        fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
        fun JsonObject.objs(k: String) = (this[k] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        fun JsonObject.strs(k: String) = (this[k] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val groups = root.objs("groups").mapNotNull { g ->
            val id = g.str("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val page = g.str("page")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Group(id, g.str("label") ?: id, page)
        }
        val providers = root.objs("providers").mapNotNull { o ->
            val id = o.str("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val used = o["used_by"] as? JsonObject
            Provider(
                id = id, label = o.str("label") ?: id, group = o.str("group").orEmpty(), category = o.str("category").orEmpty(),
                links = o.objs("links").map { FleetModel.Link(it.str("label").orEmpty(), it.str("url").orEmpty()) },
                apps = used?.strs("apps").orEmpty(), containers = used?.strs("containers").orEmpty(), auth = o.str("auth").orEmpty(),
                secrets = o.objs("secrets").mapNotNull { s -> s.str("name")?.let { Secret(it, s.str("kind").orEmpty()) } },
            )
        }
        return Decl(groups, providers)
    }
}
