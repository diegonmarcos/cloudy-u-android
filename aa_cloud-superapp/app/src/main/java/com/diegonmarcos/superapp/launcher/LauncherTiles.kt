package com.diegonmarcos.superapp.launcher

import androidx.annotation.DrawableRes
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.extinstall.extInstallRing
import com.diegonmarcos.superapp.ui.Haptics

/**
 * The launcher tile (#1893: drawn by libs:tile-grid's [CompactTileCore]; this adds the SuperApp's install ring, haptic and drawable glyph).
 */
@Composable
internal fun CompactTile(tileId: String, label: String, @DrawableRes iconRes: Int, onClick: () -> Unit, modifier: Modifier = Modifier) =
    CompactTileCore(tileId, label, onClick, modifier, Modifier.extInstallRing(tileId, TILE_ICON, iconTop = 2.dp), { Haptics.tap(it) }) { m, tint ->
        Icon(painterResource(iconRes), contentDescription = null, tint = tint, modifier = m)
    }
