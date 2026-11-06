package com.diegonmarcos.cloudtoolssmallllms

import android.util.Base64
import com.diegonmarcos.cloudtoolssmallllms.core.MlCatalogue
import com.diegonmarcos.cloudtoolssmallllms.core.ToolsConfig
import com.diegonmarcos.superapp.bottomnav.NavDecl
import org.json.JSONObject

/**
 * The two declarations app/build.gradle bakes from build.json, decoded once: build.json::tools
 * (tasks, prompts, workers, router - validated by :tools-core's parser, so a bad edit fails the
 * first launch's parse and the JVM suite, never a screen) and the nav (libs:bottomnav NavDecl).
 */
object Decl {
    val tools: ToolsConfig by lazy {
        ToolsConfig.parse(String(Base64.decode(BuildConfig.TOOLS_CONFIG_B64, Base64.DEFAULT), Charsets.UTF_8))
    }

    val nav: NavDecl by lazy {
        NavDecl.fromBuildConfig(BuildConfig.UI_SECTIONS_B64, BuildConfig.UI_BOTTOM_NAV, BuildConfig.UI_DEFAULT_SECTION)
    }

    /** build.json::ui.ml_pages (MLs page id -> Cloud Store group id), baked; the groups and libs come from the Store's fleet file in the assets (MlCatalogue). */
    val mlPages: Map<String, String> by lazy {
        MlCatalogue.pages(JSONObject(String(Base64.decode(BuildConfig.UI_ML_PAGES_B64, Base64.DEFAULT), Charsets.UTF_8)))
    }

    /** The section and page ids Kotlin dispatches on; test-tools-app.sh diffs them against build.json::ui in both directions. */
    const val SECTION_APPS = "apps"
    const val SECTION_MODELS = "models"
    const val SECTION_AI = "ai"
    const val SECTION_STORE = "store"
    const val SECTION_MLS = "mls"
    const val SECTION_SENSORS = "sensors"
    const val SECTION_SETTINGS = "settings"
    const val PAGE_APPS_GRID = "apps_grid"
    const val PAGE_APPS_LIST = "apps_list"
    const val PAGE_SMALL = "small"
    const val PAGE_MEDIUM = "medium"
    const val PAGE_RETRIEVAL = "retrieval"
    const val PAGE_INSTALLED = "installed"
    const val PAGE_LOADED = "loaded"
    const val PAGE_ALL = "all"
    const val PAGE_ML_LITE = "ml_lite"
    const val PAGE_ML_TINY = "ml_tiny"
    const val PAGE_SENSORS_INBUILT = "sensors_inbuilt"
    const val PAGE_SENSORS_CONNECTED = "sensors_connected"
}
