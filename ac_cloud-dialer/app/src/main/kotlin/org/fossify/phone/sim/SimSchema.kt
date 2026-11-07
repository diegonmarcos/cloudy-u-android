package org.fossify.phone.sim

import android.content.Context
import org.fossify.phone.helpers.FleetDataset
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

/** #1528 the declared schema of the per-SIM fields (runtime dataset sim-fields). */
@Serializable
data class SimSchema(val groups: List<SimGroup>, val fields: List<SimField>, val vault: SimVaultDecl) {
    val secretIds: Set<String> get() = fields.filter { it.secret }.map { it.id }.toSet()
    fun inGroup(group: String): List<SimField> = fields.filter { it.group == group }

    companion object {
        const val DATASET = "sim-fields"
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(raw: String): SimSchema = json.decodeFromString(serializer(), raw)
        /** Null until Cloud Store has served `sim-fields` (#1547); never throws. */
        fun load(ctx: Context): SimSchema? = FleetDataset.get(ctx, DATASET) { parse(it) }
    }
}
