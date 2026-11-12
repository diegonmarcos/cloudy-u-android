package com.diegonmarcos.cloudwebserver.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudwebserver.Api
import org.json.JSONObject

/**
 * Configs > Server: the master switches (renderPower), what this app serves, its ports and DNS names, the private DNS, versions
 * (renderServing) and the server configuration (renderConfig). Configs > Device is the full device report (the web shell's own
 * Configs About), Engines the registry (renderEngines), Links the link-open declaration (renderLinks). General, Perms, About and the
 * fleet tail (Agents, Logcat, X-Ray App) are the shared libs:ui-nav-bars pages, drawn in place.
 */
@Composable
fun ServerConfigPage() {
    val (tick, bump) = rememberTick()
    val power = rememberObj(Api.POWER, tick)
    val serving = rememberObj(Api.SERVING, tick)
    val config = rememberObj(Api.CONFIG, tick)
    PageColumn("c3_cfg_server") {
        Acts { Act("Refresh", "c3_cfg_refresh") { bump() } }
        Loaded(power, "power") { PowerCard(it, bump) }
        Loaded(serving, "serving") { sv -> ServingCards(sv, (config as? Load.Ok)?.value ?: JSONObject()) }
        Loaded(config, "config") { ConfigCards(it) }
    }
}

@Composable
private fun ServingCards(sv: JSONObject, cfg: JSONObject) {
    val eps = sv.objs("endpoints")
    val drive = eps.firstOrNull { it.optString("role") == "drive" }
    val diag = eps.firstOrNull { it.optString("role") == "diag" }
    Card("What this app serves", "c3_cfg_serves") {
        Kv("Emulated drive", drive?.let { "${it.str("name")}  →  127.0.0.1:${it.str("port")}  ·  ${cfg.str("root", "")} and nothing else" } ?: "—")
        Kv("Fleet diagnostics", diag?.let { "${it.str("name")}  →  127.0.0.1:${it.str("port")}  ·  the SuperApp's About, Logcat and Diagnose" } ?: "—")
        Kv("Reads", "loopback only; any other peer is refused")
        Kv("Methods", cfg.optJSONArray("methods").strs().joinToString(", ") + " (+ the guarded link opener)")
    }
    Card("Ports and DNS names", "c3_cfg_ports") {
        eps.forEach { e ->
            val up = if (e.has("up") && !e.isNull("up")) e.optBoolean("up") else null
            Kv(e.str("name"), "127.0.0.1:${e.str("port")}  ·  ${e.str("role")}${when (up) { true -> "  ·  answering"; false -> "  ·  not answering"; null -> "" }}  ·  ${e.str("owner")}", up = up == true)
        }
    }
    val dns = sv.optJSONObject("dns")
    Card("Private DNS", "c3_cfg_dns") {
        Kv("Server", "udp ${dns.str("host")}:${dns.str("port")}")
        Kv("Answers", "${dns.str("answer")} (TTL ${dns.str("ttl_s")} s) for the four names above, nothing else")
        Kv("Scope", dns.str("scope"))
        Kv("Browser", sv.str("opener"))
    }
    Card("Versions", "c3_cfg_versions") {
        Kv("App", "${sv.str("app")} ${sv.str("version_name")} (code ${sv.str("version_code")})")
        Kv("Server crate", sv.str("crate_version"))
        Kv("Bridge", sv.optJSONObject("bridge").str("authority"))
        sv.objs("bundled").forEach { b -> Kv(b.str("name"), b.str("version")) }
        Kv("Platform", "${cfg.str("os", "")} / ${cfg.str("arch", "")}")
    }
}

/** renderConfig: top-level values as one Server card, each nested object as its own card. */
@Composable
private fun ConfigCards(cfg: JSONObject) {
    val keys = cfg.keys().asSequence().toList()
    Card("Server", "c3_cfg_config") { keys.filter { cfg.opt(it) !is JSONObject }.forEach { k -> Kv(k, cfg.str(k)) } }
    keys.filter { cfg.opt(it) is JSONObject }.forEach { k ->
        val o = cfg.getJSONObject(k)
        Card(k) { o.keys().asSequence().forEach { kk -> Kv(kk, o.str(kk)) } }
    }
}

/** The web shell's Configs > About: the device report (macro groups), with Copy All Infos. */
@Composable
fun DevicePage() {
    val (tick, bump) = rememberTick()
    val about = rememberArr(Api.ABOUT, tick)
    val ctx = LocalContext.current
    PageColumn("c3_cfg_device") {
        Loaded(about, "device report") { g ->
            Card("Index", "c3_about_index") {
                Note(g.objs().joinToString(" · ") { it.str("macro") })
                Acts { Act("Copy All Infos", "c3_copy_about") { copyText(ctx, "about", aboutText(g)) }; Act("Refresh") { bump() } }
            }
            AboutGroups(g)
        }
    }
}

/** renderEngines: the registry, grouped, read-only (the shared server has no per-engine switch). */
@Composable
fun EnginesPage() {
    val (tick, _) = rememberTick()
    val list = rememberArr(Api.ENGINES, tick)
    PageColumn("c3_cfg_engines") {
        Loaded(list, "engines") { arr ->
            val all = arr.objs()
            listOf("render" to "Render engines", "feature" to "Feature engines", "core" to "Core (always on)", "not-portable" to "Not served here").forEach { (g, title) ->
                val rows = all.filter { it.optString("group") == g }
                if (rows.isNotEmpty()) Card("$title (${rows.size})", "c3_engines_$g") {
                    rows.forEach { e ->
                        Text("${e.str("name")}  [${e.str("id")}]", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 12.dp))
                        Note(e.str("covers")); Note(e.str("summary"))
                        if (e.has("reason") && !e.isNull("reason")) Note("Not served: ${e.str("reason")}")
                    }
                }
            }
        }
    }
}


/** renderLinks: what a tapped link does, per declared type, with the remembered choice (Ask each time resets it). */
@Composable
fun LinksPage() {
    val (tick, _) = rememberTick()
    val links = rememberObj(Api.LINKS, tick)
    val ctx = LocalContext.current
    var redraw by remember { mutableIntStateOf(0) }
    PageColumn("c3_cfg_links") {
        Loaded(links, "links") { l ->
            Links.decl = l
            Card("What a tapped link does", "c3_links") {
                l.optJSONArray("types").objs().forEach { t ->
                    val id = t.optString("id")
                    val choices = t.optJSONArray("choices").strs()
                    redraw.let { }
                    val cur = Links.remembered(ctx, id)
                    Kv(t.str("label"), "${t.str("hosts")} · ${t.optJSONArray("schemes").strs().joinToString(" ")} · offers: ${choices.joinToString(", ")}")
                    if (choices.size > 1 && l.optBoolean("remember")) Acts {
                        Act((if (cur == null) "● " else "") + "Ask each time", "c3_links_ask_$id") { Links.remember(ctx, id, null); redraw++ }
                        choices.forEach { c -> Act((if (cur == c) "● " else "") + Links.choiceLabel(c), "c3_links_${id}_$c") { Links.remember(ctx, id, c); redraw++ } }
                    } else Acts { Badge("${choices.joinToString(", ")} only") }
                }
                Note("Declared in build.json::links. A remembered choice lives in this app only.")
            }
        }
    }
}
