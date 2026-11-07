package com.diegonmarcos.superapp.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TorrentPathsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun defaultIsDownloadsCloudyStoreTorrent() {
        assertEquals("/storage/emulated/0/Download/Cloudy-store/Torrent", TorrentPaths.defaultDir(File("/storage/emulated/0/Download")).path)
    }

    @Test fun prepareCreatesTheFolderAndProvesItWritable() {
        val d = File(tmp.root, "a/b/Torrent")
        val p = TorrentPaths.prepare(d)
        assertTrue(p.ok); assertTrue(d.isDirectory)
        assertFalse(TorrentPaths.prepare(File(tmp.newFile("f"), "x")).ok)
    }

    @Test fun volumeRootsAreTrimmedFromTheAppSpecificDirs() {
        val dirs = listOf(File("/storage/emulated/0/Android/data/com.x/files"), File("/storage/1A2B-3C4D/Android/data/com.x/files"), null)
        assertEquals(listOf("/storage/emulated/0", "/storage/1A2B-3C4D"), TorrentPaths.volumeRoots(dirs).map { it.path })
        assertTrue(TorrentPaths.isExternalVolume(File("/storage/1A2B-3C4D/Torrent")))
        assertFalse(TorrentPaths.isExternalVolume(File("/storage/emulated/0/Download")))
    }

    @Test fun treePickerIdsMapToVolumePaths() {
        assertEquals("/storage/emulated/0/Download/Torrents", TorrentPaths.pathFromTreeDocId("primary:Download/Torrents")?.path)
        assertEquals("/storage/1A2B-3C4D/Torrents", TorrentPaths.pathFromTreeDocId("1A2B-3C4D:Torrents")?.path)
        assertEquals("/storage/1A2B-3C4D", TorrentPaths.pathFromTreeDocId("1A2B-3C4D:")?.path)
        assertEquals(null, TorrentPaths.pathFromTreeDocId("msf:1234"))
        assertEquals(null, TorrentPaths.pathFromTreeDocId("primary:a/../../etc"))
        assertEquals(null, TorrentPaths.pathFromTreeDocId("nocolon"))
    }
}
