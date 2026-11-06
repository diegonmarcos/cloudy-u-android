package com.diegonmarcos.superapp.ai

import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What an app wires into the (+) menu's Attach items: the app owns the permission contract (libs:perms), this lib draws no request.
 * [missing] returns the Perms row ids of [ids] that are not granted (a composable read, refreshed on resume); [fix] draws the compact
 * "Fix in Perms" chip for them. Which rows an item needs is the design's (ai-design.json composer.plus.attach[].perms).
 */
class AiAttachHost(
    val missing: @Composable (ids: List<String>) -> List<String> = { emptyList() },
    val fix: @Composable (ids: List<String>) -> Unit = {},
    /** The largest file one chip may hold. */
    val maxBytes: Long = 12_000_000L,
)

/** The three pickers behind the Attach items, created where they are always composed (the menu's own content leaves with the menu). */
internal class AiAttachActions(val files: () -> Unit, val images: () -> Unit, val camera: () -> Unit) {
    fun run(kind: String) = when (kind) { "files" -> files(); "images" -> images(); "camera" -> camera(); else -> Unit }
}

@Composable
internal fun rememberAttachActions(chat: AiChatState, theme: AiTheme, attach: AiAttachHost): AiAttachActions {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = theme.strings
    fun take(uri: Uri?) {
        if (uri == null) return
        scope.launch {
            val r = withContext(Dispatchers.IO) { AiAttachments.import(ctx, uri, attach.maxBytes) }
            r.onSuccess { chat.attach(it) }.onFailure { chat.error = s.attachFailed.format(it.message.orEmpty()) }
        }
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { take(it) }
    val images = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { take(it) }
    var shot by remember { mutableStateOf<AiAttachments.Capture?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val c = shot
        shot = null
        if (c != null) {
            val a = if (ok) AiAttachments.fromCapture(c) else null
            if (a != null) chat.attach(a) else c.file.delete()
        }
    }
    return remember(attach) {
        AiAttachActions(
            files = { files.launch(arrayOf("*/*")) },
            images = { images.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            camera = { val c = AiAttachments.capture(ctx); shot = c; camera.launch(c.uri) },
        )
    }
}

/** The composer's attachment chips: one per attached file with its remove button; they leave with the message. */
@Composable
internal fun AttachChips(chat: AiChatState, theme: AiTheme, design: AiDesign) {
    if (chat.attachments.isEmpty()) return
    val c = theme.colors
    val m = theme.metrics
    val t = theme.type
    val shape = RoundedCornerShape(design.metric("ctl_radius").dp)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(m.small), verticalAlignment = Alignment.CenterVertically) {
        chat.attachments.forEach { a ->
            Row(
                Modifier.clip(shape).background(c.field).border(m.hairline, c.tileBorder, shape).padding(start = m.gap).testTag(AiTags.chip(a.id)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(a.name, color = c.text, style = t.style(t.small), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = m.modelMaxWidth))
                Text(
                    "×", color = c.text2, style = t.style(t.chatInput, FontWeight.Bold),
                    modifier = Modifier.clickable { chat.detach(a.id) }.padding(horizontal = m.gap, vertical = m.small)
                        .semantics { contentDescription = theme.strings.removeAttachment }.testTag(AiTags.chipRemove(a.id)),
                )
            }
        }
    }
}

/**
 * The attachments of one message: an image inline (an answer's with Save, Share and Use as input under it), any other file as its name.
 * [actions] false = the user's own message (the picture he sent), no buttons.
 */
@Composable
internal fun AttachmentList(chat: AiChatState, theme: AiTheme, atts: List<AiAttachment>, actions: Boolean) {
    if (atts.isEmpty()) return
    val m = theme.metrics
    Column(verticalArrangement = Arrangement.spacedBy(m.small)) {
        atts.forEach { a ->
            if (a.image) InlineImage(chat, theme, a, actions)
            else Text(a.name, color = theme.colors.text2, style = theme.type.style(theme.type.small), modifier = Modifier.testTag(AiTags.chip(a.id)))
        }
    }
}

@Composable
private fun InlineImage(chat: AiChatState, theme: AiTheme, a: AiAttachment, actions: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val m = theme.metrics
    val c = theme.colors
    val t = theme.type
    val s = theme.strings
    val target = ctx.resources.displayMetrics.widthPixels
    val bmp by produceState<ImageBitmap?>(null, a.path) { value = withContext(Dispatchers.IO) { decode(a.path, target) } }
    Column(verticalArrangement = Arrangement.spacedBy(m.small)) {
        bmp?.let {
            Image(
                it, contentDescription = a.name, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(max = m.robot * IMAGE_ROBOTS).clip(RoundedCornerShape(m.cardRadius)).testTag(AiTags.image(a.id)),
            )
        }
        if (actions) Row(horizontalArrangement = Arrangement.spacedBy(m.engineGap)) {
            @Composable fun action(label: String, tag: String, run: () -> Unit) =
                Text(label, color = c.accent, style = t.style(t.small, FontWeight.SemiBold), modifier = Modifier.clickable(onClick = run).padding(vertical = m.small).testTag(tag))
            action(s.saveImage, AiTags.imageAction(a.id, "save")) {
                scope.launch {
                    val where = withContext(Dispatchers.IO) { AiAttachments.saveToGallery(ctx, a) }
                    Toast.makeText(ctx, if (where != null) s.savedTo.format(where) else s.saveFailed, Toast.LENGTH_SHORT).show()
                }
            }
            action(s.shareImage, AiTags.imageAction(a.id, "share")) { AiAttachments.share(ctx, a) }
            action(s.reuseImage, AiTags.imageAction(a.id, "reuse")) { chat.attach(a) }
        }
    }
}

/** An image of the answer is shown at most this many robot-heights tall (the theme's own unit). */
private const val IMAGE_ROBOTS = 5

/** The picture at roughly the screen's width: a power-of-two subsample, never the full bitmap of a large answer. */
private fun decode(path: String, target: Int): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) sample *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}.getOrNull()
