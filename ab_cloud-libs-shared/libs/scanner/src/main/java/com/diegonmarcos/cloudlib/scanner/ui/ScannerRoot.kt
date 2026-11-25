package com.diegonmarcos.cloudlib.scanner.ui

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.diegonmarcos.cloudlib.scanner.ExportFile
import com.diegonmarcos.cloudlib.scanner.R
import com.diegonmarcos.cloudlib.scanner.ScannerExport
import com.diegonmarcos.superapp.uikit.KitDensity
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import java.io.File

/** #1908 how a host (Cloud Camera's ScannerActivity) puts the Scanner on screen: no Compose compiler needed on its side. */
object ScannerUi {
    fun show(activity: ComponentActivity, host: ScannerHost): ScannerController {
        val controller = ScannerController(activity, host)
        activity.setContent { ScannerRoot(controller) }
        return controller
    }
}

/** The Scanner: the route's screen, the export sheet over it, the busy line and the activity-result plumbing (pickers, save, share). */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ScannerRoot(c: ScannerController) {
    val ctx = LocalContext.current

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> -> c.importUris(uris) }
    val toolImages = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> -> c.loadToolImages(uris) }
    val pdfs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> -> c.loadPdfs(uris) }
    val saveOne = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uri = r.data?.data
        if (uri != null) c.work("save", { ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(c.pendingSave[0].bytes) } }) { c.say(ctx.getString(R.string.scanner_saved)) }
    }
    val saveMany = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) c.work("save", {
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            for (f in c.pendingSave) {
                val doc = DocumentsContract.createDocument(ctx.contentResolver, parent, f.mime, f.name) ?: error("create ${f.name}")
                ctx.contentResolver.openOutputStream(doc, "wt")?.use { it.write(f.bytes) }
            }
        }) { c.say(ctx.getString(R.string.scanner_saved)) }
    }

    SideEffect {
        c.pickImages = { importLauncher.launch("image/*") }
        c.pickImagesForTool = { toolImages.launch("image/*") }
        c.pickPdfs = { pdfs.launch(arrayOf("application/pdf")) }
        c.onSave = { files ->
            c.pendingSave = files
            if (files.size == 1) saveOne.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(files[0].mime).putExtra(Intent.EXTRA_TITLE, files[0].name))
            else saveMany.launch(null)
        }
        c.onShare = share@{ files ->
            val uris = ArrayList(ScannerExport.stage(ctx, files))
            if (uris.isEmpty()) return@share
            val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
            else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            i.setType(if (files.map { it.mime }.distinct().size == 1) files[0].mime else "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            i.clipData = ClipData.newRawUri("scan", uris[0]).also { cd -> uris.drop(1).forEach { cd.addItem(ClipData.Item(it)) } }
            ctx.startActivity(Intent.createChooser(i, ctx.getString(R.string.scanner_share)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    DisposableEffect(Unit) { onDispose { c.destroy() } }
    // a notice ('Saved', a failure) fades after a few seconds
    androidx.compose.runtime.LaunchedEffect(c.notice) { if (c.notice.isNotBlank()) { kotlinx.coroutines.delay(5000); c.notice = "" } }

    BackHandler {
        when {
            c.showExport -> c.showExport = false
            c.route == Route.HOME -> c.host.close()
            c.route == Route.TOOL -> c.route = Route.TOOLS
            else -> c.route = Route.HOME
        }
    }

    ScannerTheme {
        // testTagsAsResourceId: every ScannerTags tag is the node's resource-id, so the emulator walk (uiautomator) finds controls by tag.
        Box(Modifier.fillMaxSize().background(scannerBackground()).systemBarsPadding().imePadding().semantics { testTagsAsResourceId = true }) {
            when (c.route) {
                Route.HOME -> HomeScreen(c)
                Route.CAPTURE -> CaptureScreen(c)
                Route.EDITOR -> EditorScreen(c)
                Route.TOOLS -> ToolsScreen(c)
                Route.TOOL -> ToolScreen(c)
            }
            if (c.showExport) ExportSheet(c)
            if (c.busy.isNotEmpty()) BusyLine(Modifier.align(Alignment.TopCenter))
            if (c.notice.isNotBlank()) NoticeBar(c, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** The "Working" pill over every screen while a job runs. */
@Composable
private fun BusyLine(modifier: Modifier) {
    val k = LocalKitPalette.current
    Text(
        stringResource(R.string.scanner_working), color = k.tileInk, fontSize = KitDensity.body,
        modifier = modifier.padding(top = 56.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(KitDensity.corner)).background(k.accent)
            .padding(horizontal = KitDensity.medium, vertical = KitDensity.small / 2),
    )
}

/** One line of news (a failure, "Saved", "Text copied") at the bottom of every screen; a tap clears it. */
@Composable
private fun NoticeBar(c: ScannerController, modifier: Modifier) {
    com.diegonmarcos.superapp.uikit.KitStatusBanner(
        c.notice, com.diegonmarcos.superapp.uikit.KitState.IDLE, modifier.padding(KitDensity.small), tag = "scanner:notice",
        action = com.diegonmarcos.superapp.uikit.KitAction("OK", "scanner:notice_ok") { c.notice = "" },
    )
}
