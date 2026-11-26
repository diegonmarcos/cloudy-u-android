package com.diegonmarcos.cloudme

import android.webkit.WebSettings
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/**
 * #1938 embedded pages in the app's dark theme. ROOT CAUSE of the near-black Personal page: every embedded page is drawn in a WebView over the app's near-black
 * background (0xFF0B0414) and the WebView's algorithmic darkening / force-dark is OFF by default (targetSdk 33+), so a site that styles no dark scheme of its own
 * (diegonmarcos.com sets no prefers-color-scheme rules and no text colour) keeps its default black text on that dark background.
 * Fix, for EVERY embedded page (WebPage.make): the site's own dark scheme when it has one (the web theme is preferred over the user agent's darkening), otherwise algorithmic
 * darkening. [plan] is the pure decision (a JVM test), [apply] sets it.
 */
object WebDarkMode {
    enum class Plan { ALGORITHMIC_PREFER_WEB, FORCE_DARK_PREFER_WEB, NONE }

    fun plan(algorithmic: Boolean, forceDark: Boolean): Plan = when {
        algorithmic -> Plan.ALGORITHMIC_PREFER_WEB
        forceDark -> Plan.FORCE_DARK_PREFER_WEB
        else -> Plan.NONE
    }

    fun apply(settings: WebSettings): Plan {
        val p = plan(WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING), WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK))
        when (p) {
            Plan.ALGORITHMIC_PREFER_WEB -> WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true)
            Plan.FORCE_DARK_PREFER_WEB -> {
                @Suppress("DEPRECATION") WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_ON)
                if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY))
                    @Suppress("DEPRECATION") WebSettingsCompat.setForceDarkStrategy(settings, WebSettingsCompat.DARK_STRATEGY_PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING)
            }
            Plan.NONE -> Unit
        }
        return p
    }
}
