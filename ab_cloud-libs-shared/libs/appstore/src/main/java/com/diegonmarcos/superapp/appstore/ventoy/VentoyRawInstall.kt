package com.diegonmarcos.superapp.appstore.ventoy

import android.content.Context
import android.hardware.usb.UsbDevice
import com.diegonmarcos.cloudlib.sharedstore.StoreExec
import com.diegonmarcos.superapp.perms.PermsUsb
import com.diegonmarcos.superapp.usbdrive.RawDiskWriter
import org.json.JSONObject
import java.io.IOException

/**
 * #1444 the real Ventoy install: MBR + boot code, partition 1 exFAT (label Ventoy), partition 2 VTOYEFI, as Ventoy2Disk lays
 * a drive out. Two halves, because neither one can do it alone without root:
 *  - the Cloud Terminal (cld.termux's Debian rootfs, through CLOUD_EXEC, [StoreExec]) FORMATS: it has mkfs.exfat and python,
 *    runs assets/ventoy-build.py and leaves payload.bin + manifest.json (every byte that must land, and where);
 *  - this app WRITES: the terminal's processes cannot open the USB node (usbfs is root:usb, SELinux untrusted_app, and a
 *    Java UsbDeviceConnection fd cannot be inherited by the Runtime.exec children CLOUD_EXEC starts), while this app holds
 *    the UsbManager grant and writes the blocks raw ([RawDiskWriter], SCSI over bulk).
 * The only entry is [install], which wants a [VentoyGate.Armed] (typed phrase, external drive only). Internal storage has no
 * UsbDevice, so it is not reachable here either: the target is a USB mass-storage device, matched to the armed volume.
 */
object VentoyRawInstall {
    const val AVAILABLE = true
    const val WHY = "Erase & install Ventoy formats partition 1 (exFAT) in the Cloud Terminal and writes the drive raw over USB. " +
        "It needs cld.termux installed, and the drive's USB permission."
    const val ASSET = "ventoy-build.py"
    const val WORK = ".ventoy-build"
    const val CHUNK = 256 * 1024

    sealed class Result {
        data class Done(val msg: String) : Result()
        /** Refused or failed BEFORE the first write: the drive is untouched. */
        data class Untouched(val why: String) : Result()
        /** Failed after writes began: the drive is half-written and must be installed again. */
        data class Broken(val why: String) : Result()
    }

    data class Piece(val disk: Long, val pay: Long, val len: Long)
    data class Manifest(val diskBytes: Long, val pieces: List<Piece>, val payloadBytes: Long, val payloadSha256: String)
    data class Slice(val disk: Long, val pay: Long, val len: Int)

    /** Parses and VALIDATES the terminal's manifest: sector aligned, in the drive, contiguous in the payload, not overlapping. */
    fun parseManifest(json: String, capacity: Long): Manifest {
        val o = JSONObject(json)
        require(o.getInt("version") == 1) { "unknown manifest version" }
        val arr = o.getJSONArray("pieces")
        val pieces = (0 until arr.length()).map { arr.getJSONObject(it).let { p -> Piece(p.getLong("disk"), p.getLong("pay"), p.getLong("len")) } }
        val m = Manifest(o.getLong("diskBytes"), pieces, o.getLong("payloadBytes"), o.getString("payloadSha256"))
        require(pieces.isNotEmpty()) { "no pieces" }
        require(m.diskBytes <= capacity) { "the image was built for ${m.diskBytes} bytes, the drive holds $capacity" }
        var pay = 0L
        var end = -1L
        pieces.sortedBy { it.disk }.forEach { p ->
            require(p.disk % RawDiskWriter.SECTOR == 0L && p.len % RawDiskWriter.SECTOR == 0L && p.len > 0) { "piece not sector aligned" }
            require(p.disk >= end && p.disk + p.len <= m.diskBytes) { "piece outside the drive or overlapping" }
            end = p.disk + p.len
        }
        pieces.forEach { p -> require(p.pay == pay) { "payload is not contiguous" }; pay += p.len }
        require(pay == m.payloadBytes) { "payload size disagrees with the pieces" }
        return m
    }

    /** The write plan: slices of at most [chunk] bytes that never cross a piece. */
    fun slices(m: Manifest, chunk: Int = CHUNK): List<Slice> = m.pieces.flatMap { p ->
        generateSequence(0L) { it + chunk }.takeWhile { it < p.len }.map { o -> Slice(p.disk + o, p.pay + o, minOf(chunk.toLong(), p.len - o).toInt()) }.toList()
    }

    /** The one drive an armed volume means. Never guesses: no drive, or more than one, is a refusal (null + the reason). */
    fun <D> pick(drives: List<D>): Pair<D?, String?> = when {
        drives.isEmpty() -> null to "no USB mass-storage drive is plugged in (a drive Android mounts by itself, such as an SD card, is not a USB device the Store can write raw)"
        drives.size > 1 -> null to "${drives.size} USB drives are plugged in: unplug the others so there is no doubt which one is erased"
        else -> drives[0] to null
    }

    /** The shell the terminal runs: prerequisites, then the builder (shipped as base64, no quoting to get wrong). */
    fun buildScript(py: ByteArray, diskBytes: Long, url: String, sha256: String): String {
        require(url.startsWith("https://") && url.none { it in "'\"\\ \n\$`" }) { "bad release url" }
        require(Regex("[0-9a-fA-F]{64}").matches(sha256)) { "bad sha256" }
        return "set -e\n" +
            "command -v mkfs.exfat >/dev/null 2>&1 || { apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq exfatprogs; }\n" +
            "mkdir -p \"\$HOME/$WORK\" && cd \"\$HOME/$WORK\"\n" +
            "echo ${java.util.Base64.getEncoder().encodeToString(py)} | base64 -d > build.py\n" +
            "python3 build.py --disk-bytes $diskBytes --url '$url' --sha256 $sha256 --out \"\$HOME/$WORK/out\"\n"
    }

    private fun need(r: StoreExec.Result, what: String) { if (!r.ok) throw IOException("$what: ${(r.err.ifBlank { r.out }).trim().takeLast(300)}") }

    /** Blocking. [say] gets one status line at a time. */
    fun install(armed: VentoyGate.Armed, ctx: Context, py: ByteArray, releaseOf: () -> VentoyCatalogue.Resolved, say: (String) -> Unit): Result {
        // 1. the drive: VentoyGate already proved an external volume; this is the USB device that volume is.
        val (device, why) = pick(PermsUsb.drives(ctx))
        if (device == null) return Result.Untouched(why!!)
        val label = armed.drive.label
        if (!PermsUsb.granted(ctx, device)) {
            say("waiting for the USB permission...")
            val latch = java.util.concurrent.CountDownLatch(1)
            var ok = false
            android.os.Handler(android.os.Looper.getMainLooper()).post { PermsUsb.request(ctx, device) { ok = it; latch.countDown() } }
            if (!latch.await(120, java.util.concurrent.TimeUnit.SECONDS) || !ok) return Result.Untouched("USB access to the drive was not allowed")
        }
        val exec = StoreExec(ctx)
        // 2. the capacity, read WITHOUT writing (opening claims the interface, which unmounts the drive in Android).
        val capacity = try { RawDiskWriter.open(ctx, device).use { it.capacityBytes } } catch (t: Throwable) { return Result.Untouched("cannot open the drive: ${t.message}") }
        if (capacity < armed.drive.sizeBytes) return Result.Untouched("the USB drive (${capacity} bytes) is smaller than the volume you confirmed (${armed.drive.sizeBytes}): not the same drive")
        // 3. the terminal builds (the release is looked up only now: a refusal above costs no network).
        val release = try { releaseOf() } catch (t: Throwable) { return Result.Untouched("cannot look up the Ventoy release: ${t.message}") }
        say("Cloud Terminal: building Ventoy ${release.version} for $label...")
        val built = try { exec.job(buildScript(py, capacity, release.url, release.sha256), deadlineMs = 25 * 60_000L) } catch (t: Throwable) { return Result.Untouched("Cloud Terminal not reachable: ${t.message}") }
        if (!built.ok) return Result.Untouched("the Cloud Terminal could not build the image: ${built.out.trim().lines().lastOrNull { it.isNotBlank() }.orEmpty().take(300)} ${built.err}")
        val manifest = try {
            val r = exec.runHost("cat \"\$HOME/$WORK/out/manifest.json\""); need(r, "manifest")
            parseManifest(r.out, capacity)
        } catch (t: Throwable) { return Result.Untouched("bad build output: ${t.message}") }
        // 4. the app writes. From the first write on the drive is not untouched any more.
        val dev = try { RawDiskWriter.open(ctx, device) } catch (t: Throwable) { return Result.Untouched("cannot open the drive: ${t.message}") }
        var wrote = 0L
        try {
            dev.use { disk ->
                // First sector last: until the new table is down, the drive does not claim to be a finished Ventoy drive.
                val plan = slices(manifest)
                val first = plan.first { it.disk == 0L }
                (plan - first + first).forEach { s ->
                    val b = fetch(exec, s)
                    disk.write(s.disk, b)
                    check(disk.read(s.disk, s.len).contentEquals(b)) { "read-back differs at byte ${s.disk}" }
                    wrote += s.len
                    say("writing ${wrote shr 20} of ${manifest.payloadBytes shr 20} MiB")
                }
            }
        } catch (t: Throwable) {
            return Result.Broken("${t.message ?: t.javaClass.simpleName} after ${wrote shr 20} MiB: the drive is half-written, install again")
        } finally { runCatching { exec.runHost("rm -rf \"\$HOME/$WORK\"") } }
        return Result.Done("Ventoy ${release.version} installed on $label: partition 1 exFAT (Ventoy), partition 2 VTOYEFI, ${wrote shr 20} MiB written and read back. Unplug and plug the drive in again so Android mounts it.")
    }

    private fun fetch(exec: StoreExec, s: Slice): ByteArray {
        val r = exec.runHost("dd if=\"\$HOME/$WORK/out/payload.bin\" bs=512 skip=${s.pay / 512} count=${s.len / 512} 2>/dev/null | base64 | tr -d '\\n'", timeoutMs = 60_000)
        need(r, "payload at ${s.pay}")
        val b = java.util.Base64.getDecoder().decode(r.out.trim())
        if (b.size != s.len) throw IOException("payload slice at ${s.pay}: ${b.size} bytes, expected ${s.len}")
        return b
    }
}
