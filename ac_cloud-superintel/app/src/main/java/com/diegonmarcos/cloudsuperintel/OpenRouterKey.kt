package com.diegonmarcos.cloudsuperintel

import android.content.Context
import com.diegonmarcos.superapp.ai.AiToken
import org.json.JSONArray
import org.json.JSONObject

/**
 * The key of the image flow (an image in or out goes straight to OpenRouter's chat completions): read at the moment of each send from the
 * private store, Cloud Account's secret provider (the same door and the same declaration as the APIs page, assets/ai/apis.json), and never
 * kept, logged or baked. The first REAL row of the declared key provider holds the key; the bundled sample rows never do.
 */
object OpenRouterKey {
    /** The provider whose key it is, from the same declaration the APIs page reads. */
    fun provider(apisJson: String): String = JSONObject(apisJson).getJSONObject("private").getString("key_provider")

    fun token(cfg: ApiConfig, provider: String, src: PrivateSource): AiToken {
        val rows = src.get(cfg.rowsKey)?.let { runCatching { Apis.parseRows(JSONArray(it), false) }.getOrNull() }.orEmpty()
        val row = rows.firstOrNull { it.provider.equals(provider, ignoreCase = true) } ?: return AiToken(null, "no $provider key in the private store")
        val v = src.get(cfg.tokenKey.replace("{id}", row.id)) ?: return AiToken(null, "the $provider key is not readable from the private store")
        return AiToken(v)
    }

    /** The token source of one app: asset read once, the private store asked on every call. */
    fun source(ctx: Context): () -> AiToken {
        val text = AiData.asset(ctx, "ai/apis.json")
        val cfg = Apis.parse(text)
        val provider = provider(text)
        val src = PrivateSource.account(ctx, cfg.authority, cfg.method)
        return { token(cfg, provider, src) }
    }
}
