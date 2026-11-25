/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.diegonmarcos.mediacenter.shell

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.diegonmarcos.mediacenter.R
import com.diegonmarcos.mediacenter.core.LocalEventHandler
import com.diegonmarcos.mediacenter.core.Settings
import com.diegonmarcos.mediacenter.core.navigate
import com.diegonmarcos.mediacenter.feature_node.domain.model.Album
import com.diegonmarcos.mediacenter.feature_node.domain.model.AlbumState
import com.diegonmarcos.mediacenter.feature_node.domain.model.Media
import com.diegonmarcos.mediacenter.feature_node.domain.model.MediaMetadataState
import com.diegonmarcos.mediacenter.feature_node.domain.model.MediaState
import com.diegonmarcos.mediacenter.feature_node.domain.model.SourceSelection
import com.diegonmarcos.mediacenter.feature_node.domain.model.StorageSource
import com.diegonmarcos.mediacenter.feature_node.domain.util.getUri
import com.diegonmarcos.mediacenter.feature_node.domain.util.isFavorite
import com.diegonmarcos.mediacenter.feature_node.presentation.storycards.StoryCardsViewModel
import com.diegonmarcos.mediacenter.feature_node.presentation.storycards.components.StoryCardsRow
import com.diegonmarcos.mediacenter.feature_node.presentation.util.Screen

private const val RECENT_COUNT = 24
private const val ALBUM_COUNT = 12

/**
 * Home: the library at a glance. Recent photos, the story-card highlights, the albums, the real
 * state of the cloud backup (the app's sync table) and of the on-device indexes. One scroller
 * (the list); the rows inside it scroll sideways and are bounded.
 */
@Composable
fun HomeScreen(
    mediaState: State<MediaState<Media.UriMedia>>,
    albumsState: State<AlbumState>,
    @Suppress("UNUSED_PARAMETER") metadataState: State<MediaMetadataState>,
) {
    val eventHandler = LocalEventHandler.current
    val home = hiltViewModel<HomeViewModel>()
    val objects = hiltViewModel<ObjectsViewModel>()
    val storyCardsViewModel = hiltViewModel<StoryCardsViewModel>()
    val sync by home.sync.collectAsStateWithLifecycle()
    val index by objects.overview.collectAsStateWithLifecycle()
    val storyCards by storyCardsViewModel.allCards.collectAsStateWithLifecycle()

    val media = mediaState.value.media
    val favourites = remember(media) { media.count { it.isFavorite } }
    val recent = remember(media) { media.take(RECENT_COUNT) }
    val albums = remember(albumsState.value.albums) { albumsState.value.albums.take(ALBUM_COUNT) }

    var sourceText by Settings.Misc.rememberSourceSelectionText()
    val sources = remember(sourceText) { SourceSelection.decode(sourceText) }
    val sourceSelector: @Composable () -> Unit = {
        SourceSelector(sources) { sourceText = SourceSelection.encode(SourceSelection.toggle(sources, it)) }
    }

    if (media.isEmpty() && !mediaState.value.isLoading) {
        Column {
            sourceSelector()
            ShellEmptyState(
                icon = Icons.Outlined.PhotoLibrary,
                title = stringResource(R.string.shell_home_empty_title),
                body = stringResource(R.string.shell_home_empty_body),
            )
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { sourceSelector() }
        item {
            Text(
                stringResource(R.string.shell_home_summary, media.size, favourites),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        if (recent.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.shell_home_recent)) }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(recent, key = { it.id }) { item ->
                        Thumb(
                            model = item.getUri(),
                            modifier = Modifier.size(96.dp).clickable {
                                eventHandler.navigate(Screen.MediaViewScreen.idAndAlbum(item.id, -1L))
                            },
                        )
                    }
                }
            }
        }
        if (storyCards?.isNotEmpty() == true) {
            item { SectionTitle(stringResource(R.string.shell_home_highlights)) }
            item {
                StoryCardsRow(
                    cards = storyCards.orEmpty(),
                    onCardClick = { _, card -> eventHandler.navigate(Screen.StoryViewerScreen.cardId(card.id)) },
                    contentPadding = PaddingValues(0.dp),
                )
            }
        }
        // Albums, favourites, trash, vault: the Library screen, which used to be a bottom tab, opens from here.
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                SectionTitle(stringResource(R.string.shell_home_albums), Modifier.weight(1f))
                TextButton(onClick = { eventHandler.navigate(Screen.AlbumsScreen()) }) { Text(stringResource(R.string.shell_home_all_albums)) }
                TextButton(onClick = { eventHandler.navigate(Screen.LibraryScreen()) }) { Text(stringResource(R.string.shell_home_library)) }
            }
        }
        if (albums.isNotEmpty()) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(albums, key = { it.id }) { album -> AlbumCard(album) }
                }
            }
        }
        item { SyncCard(sync) }
        item { IndexCard(index) }
    }
}

/** The top multi-select of the storage sources (Emulated, External, App) every page reads from. */
@Composable
private fun SourceSelector(selected: Set<StorageSource>, onToggle: (StorageSource) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.shell_home_sources), style = MaterialTheme.typography.titleSmall)
        StorageSource.entries.forEach { source ->
            FilterChip(
                selected = source in selected,
                onClick = { onToggle(source) },
                label = {
                    Text(
                        stringResource(
                            when (source) {
                                StorageSource.EMULATED -> R.string.source_emulated
                                StorageSource.EXTERNAL -> R.string.source_external
                                StorageSource.APP -> R.string.source_app
                            }
                        )
                    )
                },
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.titleSmall)
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun Thumb(model: Any, modifier: Modifier) {
    GlideImage(
        model = model,
        contentDescription = null,
        modifier = modifier.clip(RoundedCornerShape(10.dp)),
        contentScale = ContentScale.Crop,
    )
}

@Composable
private fun AlbumCard(album: Album) {
    val eventHandler = LocalEventHandler.current
    Column(
        Modifier.width(110.dp).clickable {
            eventHandler.navigate(Screen.AlbumViewScreen.route + "?albumId=${album.id}&albumName=${Uri.encode(album.label)}")
        },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Thumb(model = album.uri, modifier = Modifier.size(110.dp))
        Text(album.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(R.string.shell_count_photos, album.count.toInt()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SyncCard(sync: SyncSummary?) {
    val eventHandler = LocalEventHandler.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionTitle(stringResource(R.string.shell_sync_title))
            when {
                sync == null -> Text(stringResource(R.string.shell_sync_reading), style = MaterialTheme.typography.bodyMedium)
                !sync.connected -> {
                    Text(stringResource(R.string.shell_sync_none), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.shell_sync_none_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { eventHandler.navigate(Screen.CloudAccountsScreen()) }) { Text(stringResource(R.string.shell_sync_connect)) }
                }
                else -> {
                    Text(
                        stringResource(if (sync.upToDate) R.string.shell_sync_up_to_date else R.string.shell_sync_waiting),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(R.string.shell_sync_counts, sync.synced, sync.pending, sync.remoteOnly, sync.conflicts),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { eventHandler.navigate(Screen.SyncStatusScreen()) }) { Text(stringResource(R.string.shell_sync_open)) }
                }
            }
        }
    }
}

@Composable
private fun IndexCard(index: IndexOverview) {
    val eventHandler = LocalEventHandler.current
    Card(Modifier.fillMaxWidth().clickable { eventHandler.navigate(Screen.ObjectsTabScreen()) }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionTitle(stringResource(R.string.shell_index_title))
            for (kind in IndexKind.entries) {
                val status = index.status[kind] ?: IndexStatus.Idle
                Row(Modifier.fillMaxWidth()) {
                    Text(kind.label(), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(status.label(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
