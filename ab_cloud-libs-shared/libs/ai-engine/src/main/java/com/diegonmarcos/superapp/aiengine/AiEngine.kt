package com.diegonmarcos.superapp.aiengine

/**
 * Where Cloud SuperIntel's AI engines live, and what a caller may assume about them (#1855 P2).
 *
 * [SERVICE_PACKAGES] is a constellation ADDRESS, like TextTools.SERVICE_PACKAGES: not read from a
 * build.json (a lib shared by reference reads the CONSUMING app's file).
 */
object AiEngine {
    val SERVICE_PACKAGES = listOf("com.diegonmarcos.cloudsuperintel")

    /** The intent action [IAiEngine] is published under. */
    const val ACTION = "com.diegonmarcos.superapp.aiengine.IAiEngine"

    /** Signature-level: the platform refuses any caller not signed with the Cloud key. */
    const val PERMISSION = "com.diegonmarcos.cloud.permission.CONSTELLATION_DATA"

    const val VERSION = 1

    const val NOT_INSTALLED = "The AI engines are held by the Cloud SuperIntel app: install Cloud SuperIntel"

    /** The provider kinds. */
    const val KIND_GATEWAY = "gateway"
    const val KIND_OPENROUTER = "openrouter"
    const val KIND_CLAUDE_CLI = "claude-cli"
    const val KIND_LOCAL = "local"
    val KINDS = listOf(KIND_GATEWAY, KIND_OPENROUTER, KIND_CLAUDE_CLI, KIND_LOCAL)

    /** A provider's health word. [HEALTH_NOKEY]: reachable in principle, but the Account holds no key for it. */
    const val HEALTH_UP = "up"
    const val HEALTH_DOWN = "down"
    const val HEALTH_UNKNOWN = "unknown"
    const val HEALTH_NOKEY = "nokey"

    /** The reason a cancelled request ends with. */
    const val CANCELLED = "cancelled"

    private fun cell(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    /** One engine provider, the wire row of [IAiEngine.providers]. */
    data class ProviderInfo(
        val id: String,
        val label: String,
        val kind: String,
        val health: String = HEALTH_UNKNOWN,
        val model: String = "",
        val detail: String = "",
    ) {
        /** True only when the provider was seen answering: [HEALTH_UP]. */
        val reachable: Boolean get() = health == HEALTH_UP

        fun encode(): String = listOf(id, label, kind, health, model, detail).joinToString("\t") { cell(it) }

        companion object {
            /** Reads a row without trusting its shape (it crossed a process); null when it has no id. */
            fun decode(row: String?): ProviderInfo? {
                val f = row?.split('\t') ?: return null
                val id = f.getOrNull(0).orEmpty()
                if (id.isEmpty()) return null
                return ProviderInfo(id, f.getOrNull(1).orEmpty().ifEmpty { id }, f.getOrNull(2).orEmpty(),
                    f.getOrNull(3).orEmpty().ifEmpty { HEALTH_UNKNOWN }, f.getOrNull(4).orEmpty(), f.getOrNull(5).orEmpty())
            }

            fun decodeAll(rows: Array<String>?): List<ProviderInfo> = rows?.mapNotNull { decode(it) }.orEmpty()
        }
    }

    /** One model of a provider, the wire row of [IAiEngine.models]. Prices are raw per-token USD strings ("" = none). */
    data class ModelInfo(
        val id: String,
        val name: String,
        val group: String = "",
        val contextLength: Long = 0,
        val pricePrompt: String = "",
        val priceCompletion: String = "",
        val note: String = "",
    ) {
        fun encode(): String = listOf(id, name, group, contextLength.toString(), pricePrompt, priceCompletion, note)
            .joinToString("\t") { cell(it) }

        companion object {
            fun decode(row: String?): ModelInfo? {
                val f = row?.split('\t') ?: return null
                val id = f.getOrNull(0).orEmpty()
                if (id.isEmpty()) return null
                return ModelInfo(id, f.getOrNull(1).orEmpty().ifEmpty { id }, f.getOrNull(2).orEmpty(),
                    f.getOrNull(3)?.toLongOrNull() ?: 0L, f.getOrNull(4).orEmpty(), f.getOrNull(5).orEmpty(), f.getOrNull(6).orEmpty())
            }

            fun decodeAll(rows: Array<String>?): List<ModelInfo> = rows?.mapNotNull { decode(it) }.orEmpty()
        }
    }

    /** The stream of one chat, delivered on a binder thread. Per request: chunks, then one onDone or onError. */
    interface Listener {
        fun onChunk(text: String)
        fun onDone(usageJson: String)
        fun onError(reason: String)
    }
}
