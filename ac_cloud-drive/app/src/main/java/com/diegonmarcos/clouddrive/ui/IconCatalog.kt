package com.diegonmarcos.clouddrive.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Commit
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Publish
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import com.diegonmarcos.clouddrive.Declarations

/**
 * #579 iconography FROM DECLARATIONS. build.json::ui names every tab, page, place
 * and filter glyph by a short name; this is the ONE vocabulary that turns a name
 * into a Material glyph. A name that is not here renders [Declarations.iconDefault]
 * — and test-drive-shell.sh + DeclarationsTest fail the build, because a declared
 * icon that silently falls back is the #170/#380 defect shape (two lists agreeing
 * by luck). One name per line on purpose: the tester reads the vocabulary off
 * this `when`.
 */
object IconCatalog {

    fun vector(name: String): ImageVector? = when (name) {
        "folder" -> Icons.Filled.Folder
        "content_copy" -> Icons.Filled.ContentCopy
        "apps" -> Icons.Filled.Apps
        "sync" -> Icons.Filled.Sync
        "backup" -> Icons.Filled.Backup
        "settings" -> Icons.Filled.Settings
        "home" -> Icons.Filled.Home
        "help_outline" -> Icons.Filled.HelpOutline
        "commit" -> Icons.Filled.Commit
        "cloud_sync" -> Icons.Filled.CloudSync
        "lan" -> Icons.Filled.Lan
        "cloud" -> Icons.Filled.Cloud
        "smartphone" -> Icons.Filled.Smartphone
        "download" -> Icons.Filled.Download
        "description" -> Icons.Filled.Description
        "image" -> Icons.Filled.Image
        "photo_camera" -> Icons.Filled.PhotoCamera
        "movie" -> Icons.Filled.Movie
        "music_note" -> Icons.Filled.MusicNote
        "list" -> Icons.Filled.List
        "archive" -> Icons.Filled.Archive
        "circle" -> Icons.Filled.Circle
        "sd_card" -> Icons.Filled.SdCard
        "usb" -> Icons.Filled.Usb
        "storage" -> Icons.Filled.Storage
        "bookmark" -> Icons.Filled.Bookmark
        "search" -> Icons.Filled.Search
        "lock" -> Icons.Filled.Lock
        "file" -> Icons.Filled.InsertDriveFile
        "pdf" -> Icons.Filled.PictureAsPdf
        "code" -> Icons.Filled.Code
        "tree" -> Icons.Filled.AccountTree
        "history" -> Icons.Filled.History
        "dns" -> Icons.Filled.Dns
        "computer" -> Icons.Filled.Computer
        "terminal" -> Icons.Filled.Terminal
        "public" -> Icons.Filled.Public
        "person" -> Icons.Filled.Person
        "login" -> Icons.Filled.Login
        "vpn_key" -> Icons.Filled.VpnKey
        "cloud_upload" -> Icons.Filled.CloudUpload
        "publish" -> Icons.Filled.Publish
        "restore" -> Icons.Filled.Restore
        "info" -> Icons.Filled.Info
        "link" -> Icons.Filled.Link
        "bar_chart" -> Icons.Filled.BarChart
        "edit_note" -> Icons.Filled.EditNote
        "swap" -> Icons.Filled.SwapHoriz
        "extension" -> Icons.Filled.Extension
        "play_arrow" -> Icons.Filled.PlayArrow
        "smart_toy" -> Icons.Filled.SmartToy
        "auto_awesome" -> Icons.Filled.AutoAwesome
        "screenshot" -> Icons.Filled.Screenshot
        else -> null
    }

    /** True when [name] is in the vocabulary — what the testers hold every declaration to. */
    fun knows(name: String): Boolean = vector(name) != null

    /** The declared glyph, or the declared default when the name is unknown. */
    fun vectorOrDefault(name: String): ImageVector =
        vector(name) ?: vector(Declarations.iconDefault) ?: Icons.Filled.Circle

    @Composable
    fun painter(name: String): Painter = rememberVectorPainter(vectorOrDefault(name))

    /** The glyph for a file entry, by its MIME type and extension — the list's leading icon. */
    fun forEntry(isDirectory: Boolean, mime: String, extension: String, isArchive: Boolean): ImageVector = when {
        isDirectory -> Icons.Filled.Folder
        isArchive -> Icons.Filled.Archive
        mime.startsWith("image/") -> Icons.Filled.Image
        mime.startsWith("video/") -> Icons.Filled.Movie
        mime.startsWith("audio/") -> Icons.Filled.MusicNote
        mime == "application/pdf" -> Icons.Filled.PictureAsPdf
        mime.startsWith("text/") -> Icons.Filled.Description
        extension in CODE_EXTENSIONS -> Icons.Filled.Code
        else -> Icons.Filled.InsertDriveFile
    }

    private val CODE_EXTENSIONS = setOf("kt", "java", "py", "js", "ts", "sh", "json", "xml", "yaml", "yml", "gradle", "nix", "toml", "css", "html", "htm")
}
