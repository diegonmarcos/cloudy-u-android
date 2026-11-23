package com.diegonmarcos.cloudnav.tracker

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudnav.maps.MapsDb
import com.diegonmarcos.cloudnav.maps.takeout.ImportController
import com.diegonmarcos.cloudnav.maps.takeout.ImportState
import com.diegonmarcos.superapp.uikit.KitCard
import com.diegonmarcos.superapp.uikit.KitSectionHeader
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

object ImportTags {
    const val FILE = "import:file"; const val FOLDER = "import:folder"; const val CANCEL = "import:cancel"
    const val PROGRESS = "import:progress"; const val SUMMARY = "import:summary"; const val ERROR = "import:error"; const val HISTORY = "import:history"
}

/** One earlier import as the `imports` table keeps it. */
class ImportRecord(val name: String, val startedMs: Long, val status: String, val days: Long, val visits: Long, val activities: Long, val places: Long, val fixes: Long)

/**
 * #1853 Explored > Import: bring a Google Takeout location history (Records.json, Semantic Location History, or the on-device
 * Timeline export; a zip, a folder or one JSON file) into the one store. Progress and cancel while it runs, a summary after, and the
 * list of earlier imports. Re-importing the same export changes nothing (stable keys).
 */
@Composable
fun ImportTabView(a: DayActions,
                  /** #1863 the Export / Import page's own cards above and below the import (the import itself is unchanged). */
                  before: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {}, after: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {}) {
    val p = LocalKitPalette.current
    val ctx = LocalContext.current.applicationContext
    var st by remember { mutableStateOf(ImportController.state) }
    var history by remember { mutableStateOf(emptyList<ImportRecord>()) }
    LaunchedEffect(Unit) { while (true) { st = ImportController.state; delay(300) } }
    LaunchedEffect(st::class) {
        history = withContext(Dispatchers.IO) {
            runCatching {
                MapsDb.get(ctx).sqlExec().rows("SELECT name, started_ms, status, days, visits, activities, places, fixes FROM imports ORDER BY id DESC LIMIT 8;").map { r ->
                    ImportRecord((r[0] as? String).orEmpty(), (r[1] as? Long) ?: 0L, (r[2] as? String).orEmpty(), (r[3] as? Long) ?: 0L, (r[4] as? Long) ?: 0L, (r[5] as? Long) ?: 0L, (r[6] as? Long) ?: 0L, (r[7] as? Long) ?: 0L)
                }
            }.getOrDefault(emptyList())
        }
    }
    val running = st is ImportState.Running
    LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        before()
        item { KitSectionHeader("Import Google location history", "A Takeout export: Records.json, Semantic Location History, or the Timeline export. A zip, a folder or one JSON file.") }
        item {
            KitCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = a.onImportFile, enabled = !running, modifier = Modifier.testTag(ImportTags.FILE)) { Text("Pick zip or JSON file") }
                        OutlinedButton(onClick = a.onImportFolder, enabled = !running, modifier = Modifier.testTag(ImportTags.FOLDER)) { Text("Pick folder") }
                    }
                    Text("Read in a stream, never loaded whole. Imported rows are kept next to what this phone recorded; where both cover the same time the recorded data is shown. Importing the same export again changes nothing.",
                        color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            when (val s = st) {
                is ImportState.Running -> KitCard {
                    Column(Modifier.testTag(ImportTags.PROGRESS), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val pr = s.progress
                        Text(if (s.phase == "rebuilding") "Rebuilding days, trips, places, cities and world" else "Importing ${s.source}", color = p.textPrimary, style = MaterialTheme.typography.titleSmall)
                        Box(Modifier.fillMaxWidth().height(6.dp).background(p.surfaceSelected)) { Box(Modifier.fillMaxWidth(if (s.phase == "rebuilding") 1f else pr?.fraction ?: 0f).height(6.dp).background(p.accent)) }
                        if (pr != null) {
                            Text("${(pr.fraction * 100).toInt()} %  ·  ${pr.filesDone} files  ·  ${pr.file.substringAfterLast('/')}", color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
                            Text("${pr.fixes} positions  ·  ${pr.visits} visits  ·  ${pr.activities} activities  ·  ${pr.signals} signals", color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedButton(onClick = a.onImportCancel, modifier = Modifier.testTag(ImportTags.CANCEL)) { Text("Cancel") }
                    }
                }
                is ImportState.Done -> KitCard {
                    val m = s.summary
                    Column(Modifier.testTag(ImportTags.SUMMARY), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (m.cancelled) "Import cancelled (what was read is kept; import again to finish)" else if (m.found) "Import done" else "No location history found in this selection",
                            color = p.textPrimary, style = MaterialTheme.typography.titleSmall)
                        if (m.found) {
                            Text("Days: ${m.days}", color = p.textPrimary); Text("Visits: ${m.visits}", color = p.textPrimary); Text("Activities: ${m.activities}", color = p.textPrimary)
                            Text("Places: ${m.places}", color = p.textPrimary); Text("Positions: ${m.fixes}", color = p.textSecondary); Text("Other signals: ${m.signals}", color = p.textSecondary)
                            Text("Segments shown: ${s.segments}", color = p.textSecondary)
                        }
                        Text("Files read: ${m.files}  ·  records skipped: ${m.skipped}  ·  files with errors: ${m.failedFiles.size}", color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
                        for (f in m.failedFiles.take(3)) Text(f, color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                }
                is ImportState.Failed -> KitCard { Text("Import failed: ${s.message}", Modifier.testTag(ImportTags.ERROR), color = p.textPrimary) }
                else -> {}
            }
        }
        if (history.isNotEmpty()) {
            item { KitSectionHeader("Earlier imports", "") }
            items(history.size) { i ->
                val h = history[i]
                KitCard {
                    Column(Modifier.testTag(ImportTags.HISTORY)) {
                        Text(h.name, color = p.textPrimary, style = MaterialTheme.typography.titleSmall)
                        Text("${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(h.startedMs))}  ·  ${h.status}", color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
                        Text("${h.days} days  ·  ${h.visits} visits  ·  ${h.activities} activities  ·  ${h.places} places  ·  ${h.fixes} positions", color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        after()
    }
}
