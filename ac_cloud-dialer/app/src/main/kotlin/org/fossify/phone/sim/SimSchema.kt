package org.fossify.phone.sim

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SimGroup(val id: String, val label: String)

/** One typed field. kind: text | multiline | choice (options) | number | date. [secret] fields go only to the Cloud Account vault. */
@Serializable
data class SimField(
    val id: String,
    val group: String,
    val label: String,
    val kind: String = "text",
    val options: List<String> = emptyList(),
    val secret: Boolean = false,
)

@Serializable
data class SimVaultDecl(val get: String, val put: String, val grant: String, val unavailable: String)

/** #1528 the declared schema of the per-SIM fields (assets/sim-fields.json). */
@Serializable
data class SimSchema(val groups: List<SimGroup>, val fields: List<SimField>, val vault: SimVaultDecl) {
    val secretIds: Set<String> get() = fields.filter { it.secret }.map { it.id }.toSet()
    fun inGroup(group: String): List<SimField> = fields.filter { it.group == group }

    companion object {
        const val ASSET = "sim-fields.json"
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(raw: String): SimSchema = json.decodeFromString(serializer(), raw)
        fun load(ctx: Context): SimSchema = parse(ctx.assets.open(ASSET).bufferedReader().use { it.readText() })
    }
}
