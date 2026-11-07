package com.diegonmarcos.cloudlib.rclone

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine's pure half, proven on the JVM in every drive ship: the
 * rclone.conf reader/writer, the parsers for what the binary prints (fixture
 * lines captured from rclone's own JSON log and lsjson shapes), the job store
 * incl. the host's declare() contract, and the remote-types catalogue read
 * from the REAL asset. Exec-ing the binary itself needs a phone and is what
 * the Remotes tab's Test button does.
 */
class RcloneEngineTest {

    @Test fun configParsesAndRoundTripsIncludingCommentsAndSpacing() {
        val text = """
            # created by rclone config
            [gitea-sftp]
            type = sftp
            host = 10.0.0.6
            user = diego
            pass = 0bscur3d==

            ; another
            [cloud]
            type=webdav
            url = https://files.example/remote.php/dav
        """.trimIndent()
        val remotes = RcloneConfig.parse(text)
        assertEquals(listOf("gitea-sftp", "cloud"), remotes.map { it.name })
        assertEquals("sftp", remotes[0].type)
        assertEquals(mapOf("host" to "10.0.0.6", "user" to "diego", "pass" to "0bscur3d=="), remotes[0].options)
        assertEquals("webdav", remotes[1].type)
        assertEquals(remotes, RcloneConfig.parse(RcloneConfig.serialize(remotes)))
        assertTrue(RcloneConfig.parse("").isEmpty())
        assertTrue(RcloneConfig.parse("key = value with no section").isEmpty())
    }

    @Test fun configFileUpsertAndRemove() {
        val f = File(Files.createTempDirectory("rc").toFile(), "rclone/rclone.conf")
        assertTrue(RcloneConfig.read(f).isEmpty())
        RcloneConfig.upsert(f, RcloneRemote("a", "local"))
        RcloneConfig.upsert(f, RcloneRemote("b", "sftp", mapOf("host" to "h")))
        RcloneConfig.upsert(f, RcloneRemote("a", "local", mapOf("nounc" to "true")))
        assertEquals(listOf("b", "a"), RcloneConfig.read(f).map { it.name })
        assertEquals("true", RcloneConfig.read(f).first { it.name == "a" }.options["nounc"])
        RcloneConfig.remove(f, "b")
        assertEquals(listOf("a"), RcloneConfig.read(f).map { it.name })
        assertFalse(f.parentFile.listFiles()!!.any { it.name.endsWith(".tmp") })
        assertTrue(RcloneConfig.isValidName("my-remote_1"))
        assertFalse(RcloneConfig.isValidName("bad:name"))
        assertFalse(RcloneConfig.isValidName(""))
    }

    @Test fun statsLinesParseAndOtherLinesDoNot() {
        val line = """{"level":"info","msg":"\nTransferred:   \t    5.2 MiB / 20 MiB, 26%, 1.3 MiB/s, ETA 11s\n","source":"accounting/stats.go:568","stats":{"bytes":5452595,"checks":0,"deletedDirs":0,"deletes":0,"elapsedTime":4.2,"errors":1,"eta":11,"fatalError":false,"renames":0,"retryError":false,"serverSideCopies":0,"speed":1363148.7,"totalBytes":20971520,"totalChecks":0,"totalTransfers":7,"transferTime":4.1,"transfers":2},"time":"2026-09-24T22:00:00Z"}"""
        val s = RcloneOutput.parseStatsLine(line)!!
        assertEquals(5452595L, s.bytes); assertEquals(20971520L, s.totalBytes)
        assertEquals(2L, s.transfers); assertEquals(7L, s.totalTransfers); assertEquals(1L, s.errors)
        assertEquals(11L, s.etaSeconds); assertEquals(1363148.7, s.speedBytesPerSecond, 0.01); assertEquals(4.2, s.elapsedSeconds, 0.001)
        assertEquals(0.26f, s.fraction, 0.001f)
        assertNull(RcloneOutput.parseStatsLine("""{"level":"info","msg":"Copied (new)","object":"a.txt"}"""))   // no stats
        assertNull(RcloneOutput.parseStatsLine("2026/09/24 22:00:00 NOTICE: plain text line"))
        assertNull(RcloneOutput.parseStatsLine("{not json"))
        assertEquals(0f, RcloneOutput.parseStatsLine("""{"stats":{"bytes":5,"totalBytes":0}}""")!!.fraction, 0f)
        assertEquals("info" to "Copied (new)", RcloneOutput.parseMessage("""{"level":"info","msg":"Copied (new)"}"""))
        assertNull(RcloneOutput.parseMessage("plain"))
    }

    @Test fun lsjsonParsesAndSortsDirectoriesFirst() {
        val text = """[
          {"Path":"zeta.txt","Name":"zeta.txt","Size":12,"MimeType":"text/plain","ModTime":"2026-09-01T10:00:00.000000000Z","IsDir":false},
          {"Path":"Beta","Name":"Beta","Size":-1,"MimeType":"inode/directory","ModTime":"2026-09-02T10:00:00Z","IsDir":true},
          {"Path":"alpha.md","Name":"alpha.md","Size":3,"ModTime":"2026-09-03T10:00:00Z","IsDir":false}
        ]"""
        val entries = RcloneOutput.parseLsjson(text)
        assertEquals(listOf("Beta", "alpha.md", "zeta.txt"), entries.map { it.name })
        assertTrue(entries[0].isDir); assertEquals(-1L, entries[0].size)
        assertEquals(12L, entries[2].size); assertEquals("text/plain", entries[2].mimeType)
        assertTrue(RcloneOutput.parseLsjson("not json").isEmpty())
        assertTrue(RcloneOutput.parseLsjson("[]").isEmpty())
    }

    @Test fun versionAndHumanUnits() {
        assertEquals("v1.75.1", RcloneOutput.parseVersion("rclone v1.75.1\n- os/version: android\n- go/version: go1.25"))
        assertNull(RcloneOutput.parseVersion("bash: rclone: not found"))
        assertEquals("512 B", RcloneOutput.humanBytes(512))
        assertEquals("1.0 KB", RcloneOutput.humanBytes(1024))
        assertEquals("20.0 MB", RcloneOutput.humanBytes(20971520))
        assertEquals("11s", RcloneOutput.humanEta(11)); assertEquals("2m 5s", RcloneOutput.humanEta(125)); assertEquals("1h 1m", RcloneOutput.humanEta(3660)); assertEquals("–", RcloneOutput.humanEta(null))
    }

    @Test fun configDeclareAddsMissingRemotesAndNeverTouchesExistingOnes() {
        val f = File(Files.createTempDirectory("rc").toFile(), "rclone/rclone.conf")
        // the user already configured oracle_s3 WITH a key on the device
        RcloneConfig.upsert(f, RcloneRemote("oracle_s3", "s3", mapOf("endpoint" to "old", "access_key_id" to "AKIA")))
        val declared = listOf(
            RcloneRemote("oracle_s3", "s3", mapOf("endpoint" to "new")),
            RcloneRemote("gdrive", "drive", mapOf("scope" to "drive")),
            RcloneRemote("bad:name", "s3"),
        )
        val after = RcloneConfig.declare(f, declared)
        assertEquals(listOf("oracle_s3", "gdrive"), after.map { it.name })
        assertEquals("AKIA", RcloneConfig.read(f).first { it.name == "oracle_s3" }.options["access_key_id"])
        assertEquals("old", RcloneConfig.read(f).first { it.name == "oracle_s3" }.options["endpoint"])
        assertEquals("drive", RcloneConfig.read(f).first { it.name == "gdrive" }.type)
        // idempotent: a second declare changes nothing
        assertEquals(after, RcloneConfig.declare(f, declared))
        // an empty file gets exactly the valid skeletons
        val g = File(Files.createTempDirectory("rc2").toFile(), "rclone.conf")
        assertEquals(listOf("oracle_s3", "gdrive"), RcloneConfig.declare(g, declared).map { it.name })
    }

    @Test fun jobStoreDeclareKeepsUserJobsAndDeclaredHistory() {
        val store = RcloneJobStore(File(Files.createTempDirectory("jobs").toFile(), "rclone/jobs.json"))
        store.upsert(RcloneJob("mine", "my copy", "copy", "a:", "/b"))
        store.declare(listOf(RcloneJob("d1", "declared one", "sync", "x:", "/y", listOf("--dry-run"))))
        assertEquals(setOf("d1", "mine"), store.load().map { it.id }.toSet())
        assertTrue(store.load().first { it.id == "d1" }.declared)
        assertFalse(store.load().first { it.id == "mine" }.declared)
        // a run on the declared job is remembered across the next declare()
        store.upsert(store.load().first { it.id == "d1" }.copy(lastRunEpochSeconds = 99, lastRunSummary = "ok"))
        store.declare(listOf(RcloneJob("d1", "declared one renamed", "sync", "x:", "/y"), RcloneJob("d2", "second", "copy", "p:", "/q")))
        val after = store.load()
        assertEquals(setOf("d1", "d2", "mine"), after.map { it.id }.toSet())
        assertEquals("declared one renamed", after.first { it.id == "d1" }.name)
        assertEquals(99L, after.first { it.id == "d1" }.lastRunEpochSeconds)
        assertEquals("ok", after.first { it.id == "d1" }.lastRunSummary)
        // a declared job that disappears from the declaration is gone
        store.declare(emptyList())
        assertEquals(listOf("mine"), store.load().map { it.id })
        assertEquals(listOf("sync", "x:", "/y", "--dry-run"), RcloneJob("d1", "n", "sync", "x:", "/y", listOf("--dry-run")).arguments())
    }

    @Test fun remoteTypesCatalogueIsWellFormed() {
        val types = RcloneRemoteTypes.parse(File("../../../data/fleet/" + RcloneRemoteTypes.DATASET + ".json").readText())
        assertTrue(types.size >= 5)
        assertEquals(types.size, types.map { it.type }.toSet().size)
        val sftp = types.first { it.type == "sftp" }
        assertTrue(sftp.fields.any { it.key == "host" && it.required })
        assertTrue(sftp.fields.any { it.key == "pass" && it.secret })
        assertEquals("22", sftp.fields.first { it.key == "port" }.default)
        for (t in types) assertEquals("no duplicate field keys in ${t.type}", t.fields.size, t.fields.map { it.key }.toSet().size)
    }
}
