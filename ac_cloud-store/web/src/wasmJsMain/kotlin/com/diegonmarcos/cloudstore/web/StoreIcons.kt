package com.diegonmarcos.cloudstore.web

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The glyphs of the store's sections (ui.sections[].icon in ac_cloud-store/build.json), drawn
 * as plain path data so the page needs no icon artifact. The Android shell maps the same names to
 * Material icons (Cloud, PhoneAndroid, Search, CloudDownload, Security, Settings); the shapes here are the same idea, not a
 * pixel copy.
 */
internal object StoreIcons {
    private fun glyph(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(addPathNodes(path), pathFillType = PathFillType.EvenOdd, fill = SolidColor(Color.Black))
            .build()

    private val cloud = glyph(
        "cloud",
        "M19.35,10.04C18.67,6.59 15.64,4 12,4 9.11,4 6.6,5.64 5.35,8.04 2.34,8.36 0,10.91 0,14c0,3.31 2.69,6 6,6h13c2.76,0 5,-2.24 5,-5 0,-2.64 -2.05,-4.78 -4.65,-4.96z",
    )
    private val phone = glyph(
        "phone",
        "M7,2h10a2,2 0 0 1 2,2v16a2,2 0 0 1 -2,2H7a2,2 0 0 1 -2,-2V4a2,2 0 0 1 2,-2zM7,5v12h10V5z",
    )
    private val search = glyph(
        "search",
        "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z",
    )
    private val mesh = glyph(
        "mesh",
        "M15,12a3,3 0 1,1 -6,0a3,3 0 1,1 6,0zM14,4a2,2 0 1,1 -4,0a2,2 0 1,1 4,0zM8,19a2,2 0 1,1 -4,0a2,2 0 1,1 4,0z" +
            "M20,19a2,2 0 1,1 -4,0a2,2 0 1,1 4,0zM11.4,6h1.2v3h-1.2zM6.4,16.6l2.6,-2.6l0.8,0.8l-2.6,2.6z" +
            "M17.6,16.6l-0.8,0.8l-2.6,-2.6l0.8,-0.8z",
    )
    private val feed = glyph(
        "feed",
        "M6.18,17.82a2.18,2.18 0 1,1 -4.36,0a2.18,2.18 0 1,1 4.36,0zM4,4.44v2.83c7.03,0 12.73,5.7 12.73,12.73h2.83C19.56,11.27 12.73,4.44 4,4.44z" +
            "M4,10.1v2.83c3.9,0 7.07,3.17 7.07,7.07h2.83c0,-5.47 -4.43,-9.9 -9.9,-9.9z",
    )
    private val torrent = glyph(
        "torrent",
        "M19.35,10.04C18.67,6.59 15.64,4 12,4 9.11,4 6.6,5.64 5.35,8.04 2.34,8.36 0,10.91 0,14c0,3.31 2.69,6 6,6h13c2.76,0 5,-2.24 5,-5 0,-2.64 -2.05,-4.78 -4.65,-4.96z" +
            "M17,13l-5,5 -5,-5h3V9h4v4z",
    )
    private val perms = glyph(
        "perms",
        "M12,1L3,5v6c0,5.55 3.84,10.74 9,12 5.16,-1.26 9,-6.45 9,-12V5l-9,-4zM12,11.99h7c-0.53,4.12 -3.28,7.79 -7,8.94V12H5V6.3l7,-3.11v8.8z",
    )
    private val settings = glyph(
        "settings",
        "M19,12a7,7 0 1,1 -14,0a7,7 0 1,1 14,0zM15.5,12a3.5,3.5 0 1,0 -7,0a3.5,3.5 0 1,0 7,0z" +
            "M11,1h2v3h-2zM11,20h2v3h-2zM1,11h3v2H1zM20,11h3v2h-3z",
    )

    fun of(name: String): ImageVector = when (name) {
        "cloud" -> cloud
        "phone" -> phone
        "search" -> search
        "mesh" -> mesh
        "feed" -> feed
        "torrent" -> torrent
        "access" -> perms
        else -> settings
    }
}
