package com.diegonmarcos.cloudwiki

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.diegonmarcos.cloudwiki.ui.AppTheme
import com.diegonmarcos.cloudwiki.ui.WikiShell
import com.diegonmarcos.superapp.bottomnav.FleetChrome

/**
 * Where the wiki opens, from the launch intent's string extras (the fleet's deep-link shape, as `extapp:cloud-wiki#page:...`): `page` a side-menu page id
 * (a topic or `graph`), `tab` a page id of that topic's subpage tabs, `tags` a comma list of tag chips to filter by. All optional.
 */
data class WikiStart(val page: String? = null, val tab: String? = null, val tags: List<String> = emptyList()) {
    companion object {
        const val EXTRA_PAGE = "page"
        const val EXTRA_TAB = "tab"
        const val EXTRA_TAGS = "tags"

        fun from(i: Intent?): WikiStart = WikiStart(
            i?.getStringExtra(EXTRA_PAGE)?.takeIf { it.isNotBlank() },
            i?.getStringExtra(EXTRA_TAB)?.takeIf { it.isNotBlank() },
            i?.getStringExtra(EXTRA_TAGS).orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() },
        )
    }
}

/** The one Activity: edge-to-edge through the fleet's FleetChrome; the bars clear their own insets. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FleetChrome.apply(this)
        val start = WikiStart.from(intent)
        setContent { AppTheme { WikiShell(start) } }
    }
}
