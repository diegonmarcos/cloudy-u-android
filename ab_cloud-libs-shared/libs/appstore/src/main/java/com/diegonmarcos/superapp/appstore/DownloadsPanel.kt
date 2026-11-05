package com.diegonmarcos.superapp.appstore

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.appstore.dl.DlJob
import com.diegonmarcos.superapp.appstore.dl.DlState
import com.diegonmarcos.superapp.appstore.dl.DlText
import com.diegonmarcos.superapp.appstore.dl.DownloadEngine
import com.diegonmarcos.superapp.appstore.dl.UserAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * #1517 THE DOWNLOADS VIEW: the engine's queue, drawn. It only OBSERVES [DownloadEngine.state]; leaving the page, switching tab or
 * recomposing changes nothing about a transfer. The buttons are the only place in the UI that stops engine work, and each builds a
 * [UserAction] from its own tap (EngineGuardTest holds that nothing else in the UI may).
 */
@Composable
fun StoreDownloadsPanel(modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext
    var engine by remember { mutableStateOf(StoreEngine.engineOrNull()) }
    // Building the engine reads the persisted queue (disk): off the main thread. It also resumes what a restart interrupted.
    LaunchedEffect(Unit) { if (engine == null) engine = withContext(Dispatchers.IO) { StoreEngine.get(app) } }
    val e = engine ?: return
    val jobs by e.state.collectAsState()
    if (jobs.isEmpty()) return
    Column(modifier.fillMaxWidth().testTag("store-downloads").padding(top = sdp(StoreDensity.S6), bottom = sdp(StoreDensity.S4))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Downloads · ${jobs.count { it.state.active }} active", color = Ink.WHITE.ink(), fontSize = StoreDensity.T_META.sp,
                modifier = Modifier.weight(1f).testTag("store-downloads:title"))
            if (jobs.any { !it.state.active }) StoreBar.Button("Clear", Ink.GREY_FILL, { e.clearFinished() }, tag = "store-downloads:clear")
        }
        for (j in jobs.sortedWith(compareByDescending<DlJob> { it.state.active }.thenByDescending { it.priority }.thenBy { it.addedAt })) Item(e, j)
    }
}

@Composable
private fun Item(e: DownloadEngine, j: DlJob) {
    Column(Modifier.fillMaxWidth().padding(top = sdp(StoreDensity.S4)).testTag("store-downloads:${j.id}")) {
        Text(j.label + if (j.mirrorName.isNotEmpty() && j.state == DlState.RUNNING) "  ·  ${j.mirrorName}" else "",
            color = Ink.WHITE.ink(), fontSize = StoreDensity.T_META.sp)
        Text(DlText.line(j), color = (if (j.state == DlState.FAILED) 0xFFF56565.toInt() else Ink.DIM).ink(), fontSize = StoreDensity.T_META.sp,
            modifier = Modifier.testTag("store-downloads:${j.id}:text"))
        if (j.state == DlState.RUNNING || j.state == DlState.PAUSED || j.state == DlState.VERIFYING || j.state == DlState.QUEUED) {
            Spacer(Modifier.size(sdp(StoreDensity.S4)))
            if (j.percent >= 0) LinearProgressIndicator(progress = { j.percent / 100f }, modifier = Modifier.fillMaxWidth().testTag("store-downloads:${j.id}:bar"))
            else LinearProgressIndicator(Modifier.fillMaxWidth().testTag("store-downloads:${j.id}:bar"))
        }
        Row {
            when (j.state) {
                DlState.QUEUED, DlState.RUNNING, DlState.VERIFYING ->
                    StoreBar.Button("Pause", Ink.AMBER_FILL, { e.pause(j.id, UserAction.tap("downloads:pause")) }, tag = "store-downloads:${j.id}:pause")
                DlState.PAUSED, DlState.FAILED, DlState.CANCELLED ->
                    StoreBar.Button(if (j.state == DlState.FAILED) "Retry" else "Resume", Ink.GREEN_FILL, { e.resume(j.id) }, tag = "store-downloads:${j.id}:resume")
                DlState.DONE -> {}
            }
            if (j.state.active || j.state == DlState.FAILED)
                StoreBar.Button("Cancel", Ink.GREY_FILL, { e.cancel(j.id, UserAction.tap("downloads:cancel")) }, tag = "store-downloads:${j.id}:cancel")
        }
    }
}
