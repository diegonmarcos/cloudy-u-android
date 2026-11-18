package com.diegonmarcos.cloudsearch.data

import android.content.Context
import com.diegonmarcos.superapp.decisions.link.DecisionsLink
import com.diegonmarcos.superapp.decisions.link.RelayReply

/**
 * #1801 THE OPENROUTER KEY IS NOT HERE. It lives in the fleet's AI-key owner engine (Cloud-Lib-Decisions-Engine, its own
 * AI keys screen) and nowhere else: this app hands the engine the request body it used to POST itself ([relay], declared
 * use `search_ai_chat`), the engine adds the key in its own process and returns the provider's status code and answer, and
 * the chat and the agents read that answer exactly as before. This app keeps no copy and never sees one: not in prefs, not in
 * a file, never logged, never in a debug answer.
 *
 * What it still says about the key is whether one is there ([token]: the engine's `keys` status, presence and nothing
 * else) and where to set it ([openSettings]: the engine's AI keys screen, linked from Settings ▸ Token).
 */
object Account {
    /** [present] = the engine holds a key for the provider; [why] says why not. No key is in it. */
    data class Token(val present: Boolean, val why: String)

    /** Tests replace the engine read. */
    @Volatile var reader: (Context, String) -> Token = { ctx, provider -> readStatus(ctx, provider) }

    /** Tests replace the relay. */
    @Volatile var relayer: (Context, String) -> RelayReply = { ctx, body -> DecisionsLink.relay(ctx, USE, ENDPOINT, body) }

    fun token(ctx: Context, provider: String): Token = reader(ctx, provider)

    /** One chat-completions [body] through the engine, which adds the key (blocking: call off the main thread). */
    fun relay(ctx: Context, body: String): RelayReply = relayer(ctx, body)

    /** The engine's AI keys screen with [provider] preselected; false when no engine is installed. */
    fun openSettings(ctx: Context, provider: String): Boolean = DecisionsLink.openKeySettings(ctx, provider)

    /** The declared relay use (decisions.json::relay.uses) and the endpoint name it may reach. */
    const val USE = "search_ai_chat"
    const val ENDPOINT = "chat"

    private fun readStatus(ctx: Context, provider: String): Token {
        val k = DecisionsLink.keys(ctx)
        if (!k.optBoolean("ok")) return Token(false, k.optString("detail").ifBlank { k.optString("reason", "the AI keys engine did not answer") })
        val list = k.optJSONArray("providers")
        val p = (0 until (list?.length() ?: 0)).mapNotNull { list?.optJSONObject(it) }.firstOrNull { it.optString("id") == provider }
            ?: return Token(false, "the AI keys engine has no $provider provider")
        return if (p.optBoolean("key_present")) Token(true, "")
        else Token(false, "no $provider key in the AI keys engine — set one in its AI keys screen")
    }
}
