package com.diegonmarcos.cloudme.health

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudme.LocalMeTarget
import com.diegonmarcos.cloudme.Sections

/**
 * One Health page (`{"kind":"fragment","id":"healthpage","page":"<id>"}` in data/ui/health/<id>.json). The page is ONE vertical
 * scroller, which MeBody's chrome connection follows; nothing inside nests another vertical scroller (choices and tables scroll
 * sideways at most). The declared content is read from assets once, the user's data from [HealthUserStore] and every change is
 * written straight back.
 */
@Composable
fun HealthPage(pageId: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val onTarget = LocalMeTarget.current
    val content = remember { runCatching { HealthContent.load(ctx) } }
    var user by remember { mutableStateOf(HealthUserStore.load(ctx)) }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 28.dp)) {
        val c = content.getOrNull()
        if (c == null) {
            HEmpty("The Health content files are missing from this build: ${content.exceptionOrNull()?.message}")
            return@Column
        }
        val env = HealthEnv(
            c, user,
            update = { f -> user = f(user).also { HealthUserStore.save(ctx, it) } },
            go = { onTarget("page:pers/$it") },
            pageLabel = { Sections.byId("pers")?.page(it)?.label ?: it },
        )
        when (pageId) {
            "home" -> HomePage(env)
            "bio" -> BioPage(env)
            "circadian" -> CircadianPage(env)
            "biomarkers" -> BiomarkersPage(env)
            "timeline" -> TimelinePage(env)
            "objectives" -> ObjectivesPage(env)
            "matrix" -> MatrixPage(env)
            "culinary" -> CulinaryPage(env)
            "clash" -> ClashPage(env)
            "thermal" -> ThermalPage(env)
            "hemo" -> HemoPage(env)
            "shop" -> ShopPage(env)
            "dossier" -> DossierPage(env)
            "workout" -> WorkoutPage(env)
            "tracked" -> TrackedPage(env)
            "activities" -> ActivitiesPage(env)
            "bodycomp" -> BodyCompPage(env)
            "hr" -> HrPage(env)
            "sleep" -> SleepPage(env)
            else -> HEmpty("Unknown Health page: $pageId")
        }
    }
}
