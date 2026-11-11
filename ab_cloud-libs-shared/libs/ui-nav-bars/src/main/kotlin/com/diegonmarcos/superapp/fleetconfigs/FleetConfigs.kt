package com.diegonmarcos.superapp.fleetconfigs

import com.diegonmarcos.superapp.uinavbars.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * THE Configs > General and Configs > About declaration of the app this library is compiled into. Every app (Cloud SuperApp excepted,
 * it keeps its own Configs) declares `build.json::configs_pages`; fleet-configs.gradle resolves it (backend mesh URLs, the Store and
 * SuperApp packages, version, build sha) and bakes it as [BuildConfig.FLEET_CONFIGS_B64]. The rule it is held to is
 * 1_cicd/src/data/configs-pages.json, checked by cloud-android-configs-pages-guard.py. No app writes these pages in Kotlin.
 */
public data class GeneralSection(val id: String, val label: String, val note: String)

public data class Backend(val id: String, val why: String, val kind: String, val mesh: String)

/** One screen or section of the app, as About's "Screens" lists it. */
public data class AppScreen(val name: String, val what: String)

/** A grant the app uses (build.json::perms, labelled from the libs:perms catalogue) and why; [helper] = the fleet app that holds it. */
public data class PermUse(val id: String, val label: String, val kind: String, val why: String, val helper: String)

/** A store the app keeps state in (libs:fleetconfig-model fleet-config.json, the new-phone migration manifest). */
public data class DataStore(val id: String, val path: String, val cls: String, val doc: String)

/** A fleet app this one talks to or hands work to. */
public data class RelatedApp(val app: String, val name: String, val pkg: String, val why: String)

/** Where Cloud Store publishes this app: the rolling release [tag] and the asset per ABI (first ABI of each build variant). */
public data class UpdateDecl(val repo: String, val tag: String, val assets: Map<String, String>, val appDir: String) {
    /** The asset built for this device's ABI (the first variant when none matches). */
    public fun asset(abis: List<String>): String = abis.firstNotNullOfOrNull { assets[it] } ?: assets.values.firstOrNull().orEmpty()
}

public data class AboutDecl(
    val tagline: String,
    val description: String,
    val features: List<String>,
    val screens: List<AppScreen>,
    val guide: List<String>,
    val privacy: String,
    val stores: List<DataStore>,
    val diagnostics: List<String>,
    val perms: List<PermUse>,
    val backends: List<Backend>,
    val related: List<RelatedApp>,
    val repo: String,
    val license: String,
    val origin: String,
    val upstream: String,
    val credits: String,
    val crash: String,
    val debug: String,
    val support: String,
)

public data class FleetConfigsDecl(
    val app: String,
    val name: String,
    val version: String,
    val versionCode: Int,
    val sha: String,
    val buildDate: String,
    val applicationId: String,
    val storePkg: String,
    val superappPkg: String,
    val general: List<GeneralSection>,
    val about: AboutDecl,
    val update: UpdateDecl,
    /** #1630 build.json::fleet_agents of this app, drawn by the Agents tab (libs:fleetconfigs-agents). Empty when none declared. */
    val agents: List<FleetAgentRow> = emptyList(),
)

/** #1630 One row of build.json::fleet_agents as the Agents tab shows it (the shape is aa_cloud-superapp/build.json::_doc_fleet_agents). */
public data class FleetAgentRow(
    val id: String,
    val name: String,
    val kind: String,
    val trigger: String,
    val summary: String,
)

public object FleetConfigs {
    /** The sections every General page carries (configs-pages.json::general_required), in this order unless the app reorders them. */
    public val REQUIRED_SECTIONS: List<String> = listOf("perms", "battery", "storage", "notifications", "ui", "language")

    /** The one optional section: the app's own Configs pages, opened through the host's Configs. */
    public const val APP_SECTION: String = "app"

    /**
     * The section every app gets by default (configs-pages.json::general_default): installed version and build, Check for
     * updates against the app's Cloud Store release, the latest build, Update (handed to Cloud Store) and the release notes.
     * Drawn right after Perms unless the app's declaration places it.
     */
    public const val UPDATE_SECTION: String = "update"

    public fun defaultLabel(id: String): String = when (id) {
        "perms" -> "Perms"
        UPDATE_SECTION -> "Update"
        "battery" -> "Battery"
        "storage" -> "Storage"
        "notifications" -> "Notifications"
        "ui" -> "UI"
        "language" -> "Language"
        APP_SECTION -> "App"
        else -> id.replaceFirstChar { it.uppercase() }
    }

    /** The Cloud Store section of every About page: how the fleet's apps are installed and updated. */
    public const val STORE_TEXT: String =
        "Every Cloud app is installed and updated by Cloud Store. It lists the whole fleet, installs an app from the signed release " +
            "built by CI for each commit, updates installed apps (on Wi-Fi, automatically, when enabled) and shows what each phone runs. " +
            "Nothing comes from a third-party store."

    /** The Cloud SuperApp section of every About page: the launcher hub of the fleet. */
    public const val SUPERAPP_TEXT: String =
        "Cloud SuperApp is the launcher hub of the fleet: the home screen that opens every Cloud app, the shared panels (communication, " +
            "boards, cloud, phone) and the debug tools (crash reports, logs, the route catalogue of each app)."

    /** [declared] with the default sections added where the app did not place them (Update after Perms). */
    public fun withDefaults(declared: List<GeneralSection>): List<GeneralSection> {
        if (declared.any { it.id == UPDATE_SECTION }) return declared
        val at = declared.indexOfFirst { it.id == "perms" } + 1
        return declared.toMutableList().apply { add(at, GeneralSection(UPDATE_SECTION, defaultLabel(UPDATE_SECTION), "")) }
    }

    public const val RELEASES_REPO: String = "diegonmarcos/cloudy-u-android"

    public val EMPTY: FleetConfigsDecl = FleetConfigsDecl(
        app = "", name = "", version = "", versionCode = 0, sha = "", buildDate = "", applicationId = "", storePkg = "", superappPkg = "",
        general = withDefaults(REQUIRED_SECTIONS.map { GeneralSection(it, defaultLabel(it), "") }),
        about = AboutDecl("", "", emptyList(), emptyList(), emptyList(), "", emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
            "", "", "", "", "", "", "", ""),
        update = UpdateDecl(RELEASES_REPO, "latest", emptyMap(), ""),
    )

    /** This app's declaration (empty when the build found none: the pages still draw the device's own state). */
    public val current: FleetConfigsDecl by lazy { decode(BuildConfig.FLEET_CONFIGS_B64) }

    public fun decode(b64: String): FleetConfigsDecl =
        if (b64.isBlank()) EMPTY else runCatching { parse(String(java.util.Base64.getDecoder().decode(b64), Charsets.UTF_8)) }.getOrDefault(EMPTY)

    public fun parse(json: String): FleetConfigsDecl {
        val o = JSONObject(json)
        val g = o.optJSONObject("general") ?: JSONObject()
        val a = o.optJSONObject("about") ?: JSONObject()
        val declared = g.optJSONArray("sections").objects().map {
            val id = it.optString("id")
            GeneralSection(id, it.optString("label").ifBlank { defaultLabel(id) }, it.optString("note"))
        }.filter { it.id.isNotBlank() }
        val u = o.optJSONObject("update") ?: JSONObject()
        return FleetConfigsDecl(
            app = o.optString("app"),
            name = o.optString("name"),
            version = o.optString("version"),
            versionCode = o.optInt("version_code"),
            sha = o.optString("sha"),
            buildDate = o.optString("build_date"),
            applicationId = o.optString("application_id"),
            storePkg = o.optString("store_pkg"),
            superappPkg = o.optString("superapp_pkg"),
            general = withDefaults(declared.ifEmpty { REQUIRED_SECTIONS.map { GeneralSection(it, defaultLabel(it), "") } }),
            about = AboutDecl(
                tagline = a.optString("tagline"),
                // `what` is the v1 name of the description (an older build of the declaration still reads)
                description = a.optString("description").ifBlank { a.optString("what") },
                features = a.optJSONArray("features").strings(),
                screens = a.optJSONArray("screens").objects().map { AppScreen(it.optString("name"), it.optString("what")) }.filter { it.name.isNotBlank() },
                guide = a.optJSONArray("guide").strings(),
                privacy = a.optString("privacy"),
                stores = a.optJSONArray("stores").objects().map { DataStore(it.optString("id"), it.optString("path"), it.optString("class"), it.optString("doc")) },
                diagnostics = a.optJSONArray("diagnostics").strings(),
                perms = a.optJSONArray("perms").objects().map {
                    PermUse(it.optString("id"), it.optString("label"), it.optString("kind"), it.optString("why"), it.optString("helper"))
                },
                backends = a.optJSONArray("backends").objects().map {
                    Backend(it.optString("id"), it.optString("why"), it.optString("kind"), it.optString("mesh"))
                },
                related = a.optJSONArray("related").objects().map {
                    RelatedApp(it.optString("app"), it.optString("name"), it.optString("pkg"), it.optString("why"))
                },
                repo = a.optString("repo"),
                license = a.optString("license"),
                origin = a.optString("origin"),
                upstream = a.optString("upstream"),
                credits = a.optString("credits"),
                crash = a.optString("crash"),
                debug = a.optString("debug"),
                support = a.optString("support"),
            ),
            update = UpdateDecl(
                repo = u.optString("repo").ifBlank { RELEASES_REPO },
                tag = u.optString("tag").ifBlank { "latest" },
                assets = u.optJSONObject("assets")?.let { j -> j.keys().asSequence().associateWith { j.optString(it) } }.orEmpty(),
                appDir = o.optString("app"),
            ),
            agents = o.optJSONArray("agents").objects().map {
                FleetAgentRow(it.optString("id"), it.optString("name").ifBlank { it.optString("id") }, it.optString("kind"), it.optString("trigger"), it.optString("summary"))
            },
        )
    }

    /** SPDX ids as a reader says them. */
    public fun licenceLabel(spdx: String): String = when (spdx) {
        "LicenseRef-NoLicenseGranted" -> "All rights reserved (no licence granted yet)"
        "" -> "Not declared"
        else -> spdx
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { optString(it) }.filter { it.isNotBlank() }
}
