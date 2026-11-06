package app.sterna.core.data.mail

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Which part of a folder a list shows: everything, or one side of the Alerts split. */
enum class AlertScope { ALL, ALERTS, NO_ALERTS }

/** The alert rules, as declared in build.json::mail_inbox_split. Every value is `[a-z0-9.-]`. */
data class InboxSplitRules(
    val localParts: List<String>,
    val senderDomains: List<String>,
    val labelAlerts: String = "",
    val labelNoAlerts: String = "",
)

/**
 * The Inbox's two breakdown views, "Alerts" and "no Alerts": ONE predicate, so they partition the
 * folder. [isAlert] (Kotlin) and [alertSql] (SQL, what the list and its counts run) are the same rule
 * and are tested against each other. A message with no sender address is a no-Alert, never neither.
 */
object InboxSplit {
    private val SAFE = Regex("[a-z0-9.-]+")

    fun parse(json: String): InboxSplitRules {
        val root: JsonObject = Json.parseToJsonElement(json).jsonObject
        fun list(key: String): List<String> =
            (root[key] as? JsonArray ?: JsonArray(emptyList())).map { it.jsonPrimitive.content.trim().lowercase() }
                .onEach { require(SAFE.matches(it)) { "mail_inbox_split.$key: '$it' is not [a-z0-9.-]" } }
        fun text(key: String) = (root[key] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        return InboxSplitRules(list("alert_local_parts"), list("alert_sender_domains"), text("label_alerts"), text("label_no_alerts"))
    }

    private fun normal(address: String?): String = address?.trim()?.lowercase().orEmpty()

    /** Whether a sender address is an alert under [rules]. Mirrors [alertSql] term for term. */
    fun isAlert(rules: InboxSplitRules, fromEmail: String?): Boolean {
        val a = normal(fromEmail)
        val at = a.indexOf('@')
        return rules.localParts.any { a.startsWith("$it@") } ||
            rules.senderDomains.any { d ->
                a.endsWith("@$d") || (at >= 0 && a.endsWith(".$d") && at + 1 <= a.length - d.length - 1)
            }
    }

    /** The predicate over rows of [table]: never NULL, true exactly when [isAlert]. */
    fun alertSql(rules: InboxSplitRules, table: String): String {
        val col = "LOWER(TRIM(COALESCE($table.fromEmail, '')))"
        val terms = rules.localParts.map { "$col LIKE '$it@%'" } +
            rules.senderDomains.flatMap { d -> listOf("$col LIKE '%@$d'", "$col LIKE '%@%.$d'") }
        return if (terms.isEmpty()) "0" else "(" + terms.joinToString(" OR ") + ")"
    }

    /** The extra WHERE term for [scope] (begins with " AND "), empty for ALL. */
    fun whereSql(scope: AlertScope, rules: InboxSplitRules?, table: String): String = when {
        scope == AlertScope.ALL || rules == null -> ""
        scope == AlertScope.ALERTS -> " AND ${alertSql(rules, table)}"
        else -> " AND NOT ${alertSql(rules, table)}"
    }

    /**
     * Splits [items] into (alerts, no-alerts), each message once: [keyOf] is its Message-ID, or its
     * folder UID where the header is missing. A repeat of a key is dropped, the first kept.
     */
    fun <T> partition(rules: InboxSplitRules, items: List<T>, keyOf: (T) -> String, fromOf: (T) -> String?): Pair<List<T>, List<T>> {
        val seen = HashSet<String>()
        val unique = items.filter { seen.add(keyOf(it)) }
        return unique.partition { isAlert(rules, fromOf(it)) }
    }
}

/** The three counts the filter menu shows for one folder: [alerts] + [noAlerts] == [all] when the split holds. */
data class AlertCounts(val all: Int, val alerts: Int, val noAlerts: Int) {
    val partitions: Boolean get() = alerts + noAlerts == all
}
