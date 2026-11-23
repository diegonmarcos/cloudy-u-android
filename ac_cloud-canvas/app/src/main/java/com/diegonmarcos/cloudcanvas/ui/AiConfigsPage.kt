package com.diegonmarcos.cloudcanvas.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.cloudlib.canvas.core.R
import com.diegonmarcos.cloudcanvas.enhance.AiSettings
import com.diegonmarcos.cloudcanvas.enhance.PresetLibrary
import com.diegonmarcos.cloudcanvas.enhance.Task
import com.diegonmarcos.superapp.fleetdata.FleetData
import com.diegonmarcos.superapp.modelcatalogue.ModelCatalogue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The image models of the fleet catalogue (libs:model-catalogue, the Visual Media section): what each task may use. */
private fun imageModels(ctx: android.content.Context): List<ModelCatalogue.Row> = runCatching {
    val text = ctx.assets.open("models/catalogue.json").bufferedReader().use { it.readText() }
    ModelCatalogue.parse(text).sections.filter { it.kind == ModelCatalogue.Kind.IMAGE }.flatMap { it.rows }
}.getOrDefault(emptyList())

/**
 * Configs > AI: the OpenRouter key (own override in encrypted storage, else the fleet Account's), a model per task from the catalogue's image models,
 * and the output quality, size and cost cap. Registered as an own tab of the fleet Configs strip (FleetConfigsExtensions).
 */
@Composable
fun AiConfigsPage() {
    val ctx = LocalContext.current
    val settings = remember { AiSettings(ctx.applicationContext) }
    val lib by remember { FleetData.observe(ctx.applicationContext, "enhance-presets", own = true) { PresetLibrary.parse(it) } }.collectAsState(initial = null)
    val models = remember { imageModels(ctx) }
    var tick by remember { mutableIntStateOf(0) }
    var source by remember { mutableStateOf("checking") }
    var entry by remember { mutableStateOf("") }
    val ink = colorResource(R.color.app_text)
    val dim = colorResource(R.color.app_text_secondary)

    LaunchedEffect(tick) {
        source = withContext(Dispatchers.IO) {
            val r = settings.resolve()
            when (r.source) {
                AiSettings.Source.OWN -> "Using this app's own key " + r.key
                AiSettings.Source.FLEET -> "Using the fleet Account's OpenRouter key (held by the fleet's AI engine; this app never reads it)"
                AiSettings.Source.NONE -> "No key yet (" + r.why + ")"
            }
        }
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("OpenRouter key", color = ink, fontSize = 15.sp)
        Text(source, color = dim, fontSize = 12.sp)
        OutlinedTextField(
            value = entry, onValueChange = { entry = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text("Own key (overrides the fleet key)") }, visualTransformation = PasswordVisualTransformation(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { settings.saveOwnKey(entry); entry = ""; tick++ }, enabled = entry.isNotBlank()) { Text("Save key") }
            TextButton(onClick = { settings.clearOwnKey(); tick++ }, enabled = settings.hasOwnKey()) { Text("Use the fleet key") }
        }
        Text("Stored encrypted on this phone, masked, never logged and never exported.", color = dim, fontSize = 11.sp)

        Text("Model per task", color = ink, fontSize = 15.sp)
        if (models.isEmpty()) Text("The fleet model catalogue lists no image models.", color = dim, fontSize = 12.sp)
        Task.entries.forEach { task ->
            Text(task.label, color = if (task.available) ink else dim, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                val current = settings.choices.model(task) ?: models.firstOrNull()?.id
                models.forEach { m ->
                    FilterChip(
                        selected = current == m.id, enabled = task.available,
                        onClick = { settings.choices.setModel(task, m.id); tick++ }, label = { Text(m.name) },
                    )
                }
            }
        }

        val l = lib
        Text("Output quality", color = ink, fontSize = 15.sp)
        if (l == null) Text("The presets are served by Cloud Store; open Cloud Store once.", color = dim, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            l?.qualities?.forEach { q ->
                FilterChip(selected = l.quality(settings.choices.quality)?.id == q.id, onClick = { settings.choices.quality = q.id; tick++ }, label = { Text(q.label) })
            }
        }
        Text("Output size", color = ink, fontSize = 15.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            l?.sizes?.forEach { s ->
                FilterChip(selected = l.size(settings.choices.size)?.id == s.id, onClick = { settings.choices.size = s.id; tick++ }, label = { Text(s.label) })
            }
        }
        Text("Cost cap per run", color = ink, fontSize = 15.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            val cur = settings.choices.costCap(l?.defaultCostCapUsd ?: 0.25)
            l?.costCapsUsd?.forEach { c ->
                FilterChip(selected = cur == c, onClick = { settings.choices.setCostCap(c); tick++ }, label = { Text("$" + "%.2f".format(c)) })
            }
        }
    }
}
