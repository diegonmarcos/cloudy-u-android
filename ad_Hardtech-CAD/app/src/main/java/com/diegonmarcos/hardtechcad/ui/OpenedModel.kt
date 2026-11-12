package com.diegonmarcos.hardtechcad.ui

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.hardtechcad.OpenedFile
import com.diegonmarcos.hardtechcad.R
import com.diegonmarcos.superapp.cad.Model
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** #1695 a file opened from another app: imported off the main thread, then THE model page (same viewer, controls and data card); a failure says why. */
@Composable
internal fun OpenedModel(uri: Uri, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val p = LocalKitPalette.current
    val dir = remember { File(ctx.cacheDir, "cad-models") }
    var model by remember(uri) { mutableStateOf<Model?>(null) }
    var error by remember(uri) { mutableStateOf<String?>(null) }
    LaunchedEffect(uri) {
        runCatching { withContext(Dispatchers.IO) { OpenedFile.import(ctx, uri, dir) } }
            .onSuccess { model = it }.onFailure { error = it.message ?: it.javaClass.simpleName }
    }
    val m = model
    if (m != null) { ModelPage(m, emptyList(), onClose); return }
    Column(Modifier.fillMaxSize().padding(CadType.gutter).testTag("opened_model"), verticalArrangement = Arrangement.spacedBy(CadType.gap)) {
        Text(stringResource(R.string.model_back), color = p.accent, fontSize = CadType.label, modifier = Modifier.clickable(onClick = onClose).padding(vertical = CadType.tight))
        Text(error?.let { stringResource(R.string.opened_failed, it) } ?: stringResource(R.string.opened_loading), color = p.textPrimary, fontSize = CadType.body)
    }
}
