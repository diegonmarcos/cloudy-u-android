package com.diegonmarcos.cloudwiki.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.diegonmarcos.cloudwiki.ChronosPage
import com.diegonmarcos.cloudwiki.ChronosRepo
import com.diegonmarcos.cloudwiki.ChronosTree
import com.diegonmarcos.cloudwiki.R
import com.diegonmarcos.cloudwiki.WikiData
import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.superapp.navbartop2.NavBarTopSecondRow
import com.diegonmarcos.superapp.uikit.KitStoreRow
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/** The two tabs of a project page. */
object ProjectTabs {
    const val TOPICS = "project_topics"
    const val EXPLORER = "project_explorer"
}

/**
 * #1857 a project of the lib-chronos repo (Projects > Sacred Geometry): its Markdown page (Sacred-geo/topics.md, the runtime clone's copy
 * when the app has cloned, else the APK's, rendered by [Markdown] like every wiki page) and its web page (Sacred-geo/index.html, the 3D
 * explorer) in the same embedded web view Chronos uses ([ChronosSimPage]: the APK/clone only, every off-origin request refused). The
 * Markdown page links to the explorer; both are tabs of the page.
 */
@Composable
fun ProjectPage(project: ChronosRepo.Project, label: String) {
    val ctx = LocalContext.current.applicationContext
    val repo by ChronosRepo.state.collectAsState()
    var tab by rememberSaveable(project.id) { mutableStateOf(ProjectTabs.TOPICS) }
    val explorer = remember(project) {
        ChronosPage(project.id + ".explorer", label, "token", project.sourceDir + "/" + project.explorer, null, emptyList())
    }
    Column(Modifier.fillMaxSize().testTag(Tags.PROJECT)) {
        NavBarTopSecondRow(
            pages = listOf(
                NavPage(ProjectTabs.TOPICS, stringResource(R.string.project_topics), "menu_book"),
                NavPage(ProjectTabs.EXPLORER, stringResource(R.string.project_explorer), "token"),
            ),
            selectedId = tab,
            onSelect = { tab = it.id },
            modifier = Modifier.testTag(Tags.TABS),
            underTopChrome = false,
        )
        if (tab == ProjectTabs.EXPLORER) {
            ChronosSimPage(ChronosTree(listOf(explorer)), explorer, onOpen = {})
        } else {
            val p = LocalKitPalette.current
            val md = remember(repo.sha) { ChronosRepo.readText(ctx, project.sourceDir + "/" + project.markdown).orEmpty() }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(KitStoreRow.padH).testTag(Tags.ARTICLE)) {
                Text(
                    stringResource(R.string.project_open_explorer), color = p.accent, fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline,
                    modifier = Modifier.fillMaxWidth().clickable { tab = ProjectTabs.EXPLORER }.padding(vertical = 8.dp).testTag(Tags.PROJECT_OPEN),
                )
                Markdown(md, WikiData(emptyList()), {})
            }
        }
    }
}
