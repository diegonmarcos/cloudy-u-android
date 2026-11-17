package com.diegonmarcos.cloudcanvas.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.cloudcanvas.CanvasStart
import com.diegonmarcos.cloudcanvas.R
import com.diegonmarcos.cloudcanvas.enhance.PresetLibrary
import com.diegonmarcos.superapp.fleetdata.FleetData
import com.diegonmarcos.superapp.fleetconfigs.FleetConfigsActivity
import android.content.Intent

/** The Draw Enhancer: pick or take a picture, pick a preset (or write a prompt), see and edit the expanded prompt, run, compare, save or send back to Draw. */
@Composable
fun EnhanceScreen(ctl: EnhanceController, start: CanvasStart, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val lib by remember { FleetData.observe(ctx.applicationContext, "enhance-presets", own = true) { PresetLibrary.parse(it) } }.collectAsState(initial = null)
    val ink = colorResource(R.color.app_text)
    val dim = colorResource(R.color.app_text_secondary)
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) ctl.load(uri) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { Folders.remember(ctx, "enhance", uri); ctl.saveTo(uri) }
    }
    LaunchedEffect(lib) {
        val l = lib
        if (l != null && ctl.presetId == null) ctl.pick(l, l.presets.firstOrNull()?.id) else ctl.refreshPrompt(l)
    }
    LaunchedEffect(start) { if (start.panel == "prompt") ctl.promptOpen = true }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Input", color = ink, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.horizontalScroll(rememberScrollState())) {
            AssistChip(onClick = { pickImage.launch("image/*") }, label = { Text("Upload image") })
            AssistChip(onClick = { ctl.useDrawing() }, label = { Text("Use drawing from Draw") })
            ctl.input?.let { Text("Using " + ctl.inputName, color = dim, fontSize = 12.sp) }
        }
        ctl.input?.let { Image(it.asImageBitmap(), contentDescription = "Input picture", modifier = Modifier.height(110.dp), contentScale = ContentScale.Fit) }

        Text("Preset", color = ink, fontSize = 13.sp)
        val l = lib
        if (l == null) Text("The preset library is served by Cloud Store; open Cloud Store once if nothing appears. A custom prompt works meanwhile.", color = dim, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            l?.presets?.forEach { p -> FilterChip(selected = ctl.presetId == p.id, onClick = { ctl.pick(l, p.id) }, label = { Text(p.label) }) }
            FilterChip(selected = ctl.presetId == null, onClick = { ctl.pick(l, null) }, label = { Text("Custom") })
        }
        l?.preset(ctl.presetId)?.let { Text(it.summary, color = dim, fontSize = 12.sp) }
        OutlinedTextField(
            value = ctl.custom, onValueChange = { ctl.custom = it; ctl.promptEdited = false; ctl.refreshPrompt(l) },
            label = { Text(if (ctl.presetId == null) "Custom prompt" else "Extra instructions (optional)") },
            modifier = Modifier.fillMaxWidth(), minLines = 1, maxLines = 3,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Expanded prompt", color = ink, fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { ctl.promptOpen = !ctl.promptOpen }) { Text(if (ctl.promptOpen) "Hide" else "Show and edit") }
        }
        if (ctl.promptOpen) {
            OutlinedTextField(
                value = ctl.promptText, onValueChange = { ctl.promptText = it; ctl.promptEdited = true },
                modifier = Modifier.fillMaxWidth(), minLines = 6, maxLines = 14,
                supportingText = { Text(if (ctl.promptEdited) "Edited for this run." else "From the preset, quality and size in Configs > AI.") },
            )
            if (ctl.promptEdited) TextButton(onClick = { ctl.promptEdited = false; ctl.refreshPrompt(l) }) { Text("Reset to the preset") }
        }

        val row = ctl.modelFor(ctl.task(l))
        val est = ctl.estimate(l)
        Text(
            "Model: " + (row?.name ?: "none in the catalogue") + "   Quality: " + (l?.quality(ctl.settings.choices.quality)?.label ?: "-") +
                "   Size: " + (l?.size(ctl.settings.choices.size)?.label ?: "-") + (est?.let { "   Est. cost: $" + "%.3f".format(it) } ?: ""),
            color = dim, fontSize = 12.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { ctl.run(l) }, enabled = ctl.input != null && ctl.promptText.isNotBlank() && !ctl.running) { Text("Enhance") }
            TextButton(onClick = { ctx.startActivity(Intent(ctx, FleetConfigsActivity::class.java).putExtra(FleetConfigsActivity.EXTRA_PAGE, "ai")) }) { Text("Configs > AI") }
            if (ctl.running) { CircularProgressIndicator(Modifier.size(24.dp)); Text("Enhancing, this can take a minute.", color = dim, fontSize = 12.sp) }
        }
        ctl.error?.let { Text(it, color = colorResource(R.color.app_error), fontSize = 13.sp) }
        ctl.note?.let { Text(it, color = colorResource(R.color.app_accent), fontSize = 13.sp) }

        ctl.result?.let { r ->
            Text("Original and result", color = ink, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                ctl.input?.let { Image(it.asImageBitmap(), "Original", Modifier.weight(1f).height(200.dp), contentScale = ContentScale.Fit) }
                Image(r.asImageBitmap(), "Enhanced result", Modifier.weight(1f).height(200.dp), contentScale = ContentScale.Fit)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { val t = Folders.saved(ctx, "enhance"); if (t == null) pickFolder.launch(null) else ctl.saveTo(t) }, label = { Text("Save PNG") })
                AssistChip(onClick = { pickFolder.launch(null) }, label = { Text("Folder") })
                AssistChip(onClick = { ctl.sendToDraw() }, label = { Text("Send back to Draw") })
            }
        }
    }
}

/** The Music Enhancer is a placeholder for now: a short description, no actions. */
@Composable
fun MusicScreen(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Music Enhancer", color = colorResource(R.color.app_text), fontSize = 22.sp)
        Text("Coming soon", color = colorResource(R.color.app_accent), fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
        Text(
            "A way to clean up and improve the loops you record in Play, using the same AI models you choose in Configs > AI. Nothing here works yet.",
            color = colorResource(R.color.app_text_secondary), fontSize = 14.sp, modifier = Modifier.padding(top = 16.dp),
        )
    }
}
