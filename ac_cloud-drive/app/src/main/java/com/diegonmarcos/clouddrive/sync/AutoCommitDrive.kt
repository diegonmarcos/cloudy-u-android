package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.cloudlib.gitrepos.*

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.diegonmarcos.clouddrive.BuildConfig
import com.diegonmarcos.cloudlib.gitsync.GitEngine
import com.diegonmarcos.cloudlib.gitsync.GitOpResult
import com.diegonmarcos.superapp.ai.AiConfig
import com.diegonmarcos.superapp.ai.AiGatewayBackend
import com.diegonmarcos.superapp.ai.AiMsg
import com.diegonmarcos.superapp.ai.AiOpenRouterBackend
import com.diegonmarcos.superapp.ai.AiRequest
import com.diegonmarcos.superapp.ai.AiToken
import java.io.File

/**
 * #1419 Auto Commit on the phone: the writer choice (Configs ▸ Git ▸ Auto Commit), the model ask and the commit itself.
 * The model writer goes through the fleet's AI client (libs:ai) to the fleet agent gateway, which holds the fleet's
 * OpenRouter key: this app reads no key (decisions-use guard D5). The picker lists OpenRouter's public model list
 * (no key needed to read it); any error or no network falls back to the rule ([AutoCommit.write]).
 */
object AutoCommitDrive {
    private const val PREFS = "drive_auto_commit"
    private const val K_WRITER = "writer"
    private const val K_MODEL = "model"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun writer(ctx: Context): AutoCommit.Writer = AutoCommit.Writer.of(prefs(ctx).getString(K_WRITER, null))
    fun setWriter(ctx: Context, w: AutoCommit.Writer) = prefs(ctx).edit().putString(K_WRITER, w.id).apply()
    fun model(ctx: Context): String = prefs(ctx).getString(K_MODEL, null) ?: runCatching { AiConfig.load(ctx).defaultModel }.getOrDefault("")
    fun setModel(ctx: Context, id: String) = prefs(ctx).edit().putString(K_MODEL, id).apply()

    fun online(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(false)

    /** The model ids the picker offers, blocking: OpenRouter's public list, else the configured default alone. */
    fun models(ctx: Context): List<String> {
        val cfg = runCatching { AiConfig.load(ctx) }.getOrNull() ?: return emptyList()
        val live = runCatching { AiOpenRouterBackend(cfg, "Cloud Drive", { AiToken(null, "list only") }).models().map { it.id } }.getOrDefault(emptyList())
        return (listOf(cfg.defaultModel) + live).filter { it.isNotBlank() }.distinct()
    }

    /** One non-streamed ask through the gateway; null on any error (the caller falls back to the rule). */
    fun ask(ctx: Context): (String, String) -> String? = { model, prompt ->
        val cfg = AiConfig.load(ctx)
        val r = AiGatewayBackend(cfg, BuildConfig.AI_GATEWAY_URL).chat(AiRequest(model, listOf(AiMsg("user", prompt)), stream = false)) { }
        if (r.error != null) throw IllegalStateException(r.error) else r.text
    }

    /** Stage everything, build the message, commit. Never pushes; a clean tree commits nothing. */
    fun run(ctx: Context, dir: File, name: String, email: String): GitOpResult = GitEngine(ctx, dir).use { e ->
        e.stageAll()
        if (e.status().files.none { it.hasStaged }) return@use GitOpResult(true, "auto commit: nothing to commit")
        val diff = e.diff(staged = true)
        val files = AutoCommit.stats(diff, sizeOf = { p -> File(dir, p).let { if (it.isFile) it.length() else 0L } })
        val w = AutoCommit.write(files, diff, writer(ctx), model(ctx), online(ctx), ask(ctx))
        val c = e.commit(w.message, name, email)
        GitOpResult(true, "auto commit: ${c.shortSha} " + w.message.lineSequence().first(), "written by ${w.by}\n\n${w.message}")
    }
}
