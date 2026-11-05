package com.diegonmarcos.cloudlib.mediaviewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import com.diegonmarcos.superapp.uikit.KitDensity
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

object MediaViewerTags {
    const val IMAGE = "media_image"
    const val AUDIO = "media_audio"
    const val VIDEO = "media_video"
    const val PLAY = "media_play"
    const val ERROR = "media_error"
}

/** The words of the viewer; the host passes its translation. */
data class MediaLabels(val play: String = "Play", val pause: String = "Pause", val cannotOpen: String = "Cannot open this file")

/** Shows [path] as [kind]. View only: there is no edit surface here. */
@Composable
fun MediaViewer(path: String, kind: MediaKind, modifier: Modifier = Modifier, labels: MediaLabels = MediaLabels()) {
    when (kind) {
        MediaKind.Image -> ImageViewer(path, modifier, labels)
        MediaKind.Audio -> AudioViewer(path, modifier, labels)
        MediaKind.Video -> VideoViewer(path, modifier)
    }
}

@Composable
private fun ImageViewer(path: String, modifier: Modifier, labels: MediaLabels) {
    var bitmap by remember(path) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(path) { mutableStateOf(false) }
    LaunchedEffect(path) {
        val decoded = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                val opts = BitmapFactory.Options().apply { inSampleSize = MediaMath.sampleSize(bounds.outWidth, bounds.outHeight, MAX_SIDE) }
                BitmapFactory.decodeFile(path, opts)
            }.getOrNull()
        }
        bitmap = decoded
        failed = decoded == null
    }
    var zoom by remember(path) { mutableFloatStateOf(1f) }
    var panX by remember(path) { mutableFloatStateOf(0f) }
    var panY by remember(path) { mutableFloatStateOf(0f) }
    val bmp = bitmap
    if (bmp != null) {
        Image(
            bmp.asImageBitmap(), contentDescription = File(path).name, contentScale = ContentScale.Fit,
            modifier = modifier.fillMaxSize().testTag(MediaViewerTags.IMAGE)
                .pointerInput(path) {
                    detectTransformGestures { _, pan, gesture, _ ->
                        zoom = MediaMath.zoom(zoom, gesture)
                        if (zoom > 1f) { panX += pan.x; panY += pan.y } else { panX = 0f; panY = 0f }
                    }
                }
                .graphicsLayer(scaleX = zoom, scaleY = zoom, translationX = panX, translationY = panY),
        )
    } else if (failed) {
        Text(labels.cannotOpen, modifier.padding(KitDensity.large).testTag(MediaViewerTags.ERROR), fontSize = KitDensity.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AudioViewer(path: String, modifier: Modifier, labels: MediaLabels) {
    val player = remember(path) { runCatching { MediaPlayer().apply { setDataSource(path); prepare() } }.getOrNull() }
    DisposableEffect(player) { onDispose { runCatching { player?.release() } } }
    if (player == null) {
        Text(labels.cannotOpen, modifier.padding(KitDensity.large).testTag(MediaViewerTags.ERROR), fontSize = KitDensity.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    var playing by remember(player) { mutableStateOf(false) }
    var position by remember(player) { mutableIntStateOf(0) }
    val duration = remember(player) { player.duration.coerceAtLeast(1) }
    LaunchedEffect(player, playing) {
        while (playing) {
            position = runCatching { player.currentPosition }.getOrDefault(0)
            if (!runCatching { player.isPlaying }.getOrDefault(false)) { playing = false; position = 0 }
            delay(POLL_MS)
        }
    }
    Column(modifier.fillMaxWidth().padding(KitDensity.large).testTag(MediaViewerTags.AUDIO), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(File(path).name, fontSize = KitDensity.title, color = MaterialTheme.colorScheme.onSurface, maxLines = 2)
        Slider(value = position.toFloat(), onValueChange = { position = it.toInt(); runCatching { player.seekTo(position) } }, valueRange = 0f..duration.toFloat())
        Row(Modifier.fillMaxWidth()) {
            Text(MediaMath.clock(position.toLong()), fontSize = KitDensity.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(" / " + MediaMath.clock(duration.toLong()), fontSize = KitDensity.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            if (playing) labels.pause else labels.play,
            Modifier.testTag(MediaViewerTags.PLAY).padding(KitDensity.medium).clickable {
                if (playing) { runCatching { player.pause() }; playing = false } else { runCatching { player.start() }; playing = true }
            },
            fontSize = KitDensity.title, color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun VideoViewer(path: String, modifier: Modifier) {
    AndroidView(
        modifier = modifier.fillMaxSize().testTag(MediaViewerTags.VIDEO),
        factory = { ctx ->
            VideoView(ctx).apply {
                val controller = MediaController(ctx)
                controller.setAnchorView(this)
                setMediaController(controller)
                setVideoURI(Uri.fromFile(File(path)))
                setOnPreparedListener { it.isLooping = false; start() }
            }
        },
        onRelease = { it.stopPlayback() },
    )
}

private const val MAX_SIDE = 2048
private const val POLL_MS = 250L
