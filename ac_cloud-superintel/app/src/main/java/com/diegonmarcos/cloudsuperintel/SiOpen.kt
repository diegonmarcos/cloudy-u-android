package com.diegonmarcos.cloudsuperintel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.diegonmarcos.superapp.notificationcenter.AiUsageModel

/**
 * #1543 The place another app asked SuperIntel to open: `com.diegonmarcos.cloudsuperintel.OPEN` with the extras
 * `section` and `page` (the Cloud SuperApp Notify AI badge sends section = page = "cost"). The contract's words are
 * libs:ai-usage's [AiUsageModel.SuperIntelLink], so sender and receiver cannot drift. MainActivity sets [pending]; the
 * shell opens it once and clears it. An unknown section is ignored (the shell checks it against the nav declaration).
 */
object SiOpen {
    data class Target(val section: String, val page: String?)

    var pending by mutableStateOf<Target?>(null)

    /** Pure: the target an intent's action and extras name, or null. */
    fun target(action: String?, section: String?, page: String?): Target? {
        if (action != AiUsageModel.SuperIntelLink.ACTION) return null
        val s = section?.trim()?.takeIf { it.isNotEmpty() && it.length <= 40 } ?: return null
        return Target(s, page?.trim()?.takeIf { it.isNotEmpty() && it.length <= 40 })
    }
}
