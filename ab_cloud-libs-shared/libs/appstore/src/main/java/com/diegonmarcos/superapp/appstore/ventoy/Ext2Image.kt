package com.diegonmarcos.superapp.appstore.ventoy

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * #1405 a Ventoy persistence image, written on the phone: an empty ext2 file system with the label the distro looks
 * for (casper-rw for Ubuntu/Mint, persistence + a "/ union" persistence.conf for Debian/Kali, vtoycow for Arch), as
 * Ventoy's CreatePersistentImg.sh makes with mkfs (ext2/3/4 are all accepted, per the persistence plugin doc).
 * Streamed in one pass, so a multi-GB image never sits in memory. Ext2ImageTest runs e2fsck on the output.
 *
 * Layout: 4 KiB blocks, 32768 blocks per group, 128-byte inodes, rev 1, feature `filetype`, no sparse_super (every
 * group carries a superblock and descriptor copy). Group 0 holds the root directory, lost+found and the optional
 * persistence.conf.
 */
object Ext2Image {
    private const val BS = 4096
    private const val BPG = 32768
    private const val ISZ = 128
    const val MIN_BYTES = 1L shl 20

    data class Layout(val blocks: Int, val groups: Int, val inodesPerGroup: Int, val tableBlocks: Int, val gdtBlocks: Int, val overhead: Int)

    fun layout(bytes: Long): Layout {
        require(bytes >= MIN_BYTES) { "a persistence image needs at least 1 MiB" }
        require(bytes / BS <= Int.MAX_VALUE) { "too large" }
        var total = (bytes / BS).toInt()
        while (true) {
            val groups = (total + BPG - 1) / BPG
            val target = maxOf(16, total / 4)
            val perGroup = (target + groups - 1) / groups
            val ipg = minOf(8192, maxOf(32, (perGroup + 31) / 32 * 32))
            val itb = ipg * ISZ / BS
            val gdt = (groups * 32 + BS - 1) / BS
            val ovh = 1 + gdt + 2 + itb
            val last = total - (groups - 1) * BPG
            if (groups > 1 && last < ovh + 1) { total = (groups - 1) * BPG; continue }
            require(total >= ovh + 3) { "too small" }
            return Layout(total, groups, ipg, itb, gdt, ovh)
        }
    }

    private const val CONF = "/ union\n"

    fun write(out: OutputStream, bytes: Long, label: String, conf: Boolean, now: Int = (System.currentTimeMillis() / 1000).toInt(), uuid: UUID = UUID.randomUUID()) {
        val l = layout(bytes)
        fun gsize(g: Int) = minOf(BPG, l.blocks - g * BPG)
        val used0 = l.overhead + 2 + (if (conf) 1 else 0)
        val usedInodes = 11 + (if (conf) 1 else 0)
        val free = IntArray(l.groups) { g -> gsize(g) - (if (g == 0) used0 else l.overhead) }
        val d0 = l.overhead
        val inodes = l.inodesPerGroup * l.groups

        val gdt = le(l.gdtBlocks * BS)
        for (g in 0 until l.groups) {
            val bb = g * BPG + 1 + l.gdtBlocks
            gdt.position(g * 32)
            gdt.putInt(bb).putInt(bb + 1).putInt(bb + 2)
                .putShort(free[g].toShort())
                .putShort((if (g == 0) l.inodesPerGroup - usedInodes else l.inodesPerGroup).toShort())
                .putShort((if (g == 0) 2 else 0).toShort())
        }

        fun superblock(g: Int): ByteArray {
            val b = le(1024)
            b.putInt(inodes).putInt(l.blocks).putInt(0).putInt(free.sum()).putInt(inodes - usedInodes)
                .putInt(0)            // first data block (4 KiB blocks)
                .putInt(2).putInt(2)  // log block / fragment size: 1024 << 2
                .putInt(BPG).putInt(BPG).putInt(l.inodesPerGroup)
                .putInt(0).putInt(now)                       // mtime, wtime
                .putShort(0.toShort()).putShort((-1).toShort())        // mount count, max mount count
                .putShort(0xEF53.toShort()).putShort(1.toShort()).putShort(1.toShort()).putShort(0.toShort()) // magic, clean, errors=continue, minor
                .putInt(now).putInt(0).putInt(0).putInt(1)   // lastcheck, checkinterval, creator Linux, rev 1
                .putShort(0.toShort()).putShort(0.toShort())                     // reserved uid/gid
                .putInt(11).putShort(ISZ.toShort()).putShort(g.toShort())
                .putInt(0).putInt(2).putInt(0)               // compat, incompat filetype, ro_compat
                .putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits)
            val name = label.toByteArray(Charsets.UTF_8).copyOf(16)
            b.put(name)
            return b.array()
        }

        fun inode(mode: Int, size: Int, links: Int, block: Int): ByteArray {
            val b = le(ISZ)
            b.putShort(mode.toShort()).putShort(0.toShort()).putInt(size).putInt(now).putInt(now).putInt(now).putInt(0)
                .putShort(0.toShort()).putShort(links.toShort()).putInt(BS / 512).putInt(0)
            b.position(40); b.putInt(block)
            return b.array()
        }

        fun entry(b: ByteBuffer, ino: Int, rec: Int, name: String, type: Int) {
            val n = name.toByteArray(Charsets.UTF_8)
            val start = b.position()
            b.putInt(ino).putShort(rec.toShort()).put(n.size.toByte()).put(type.toByte()).put(n)
            b.position(start + rec)
        }

        val zero = ByteArray(64 * 1024)
        fun zeros(n: Long) { var left = n; while (left > 0) { val k = minOf(left, zero.size.toLong()).toInt(); out.write(zero, 0, k); left -= k } }

        for (g in 0 until l.groups) {
            val n = gsize(g)
            val first = ByteArray(BS)
            superblock(g).copyInto(first, if (g == 0) 1024 else 0)
            out.write(first)
            out.write(gdt.array())
            val used = if (g == 0) used0 else l.overhead
            out.write(bitmap(used, n))
            out.write(bitmap(if (g == 0) usedInodes else 0, l.inodesPerGroup))
            val table = ByteArray(l.tableBlocks * BS)
            if (g == 0) {
                inode(0x41ED, BS, 3, d0).copyInto(table, 1 * ISZ)
                inode(0x41C0, BS, 2, d0 + 1).copyInto(table, 10 * ISZ)
                if (conf) inode(0x81A4, CONF.length, 1, d0 + 2).copyInto(table, 11 * ISZ)
            }
            out.write(table)
            var rest = (n - l.overhead).toLong()
            if (g == 0) {
                val root = le(BS)
                entry(root, 2, 12, ".", 2); entry(root, 2, 12, "..", 2)
                if (conf) { entry(root, 11, 20, "lost+found", 2); entry(root, 12, BS - 44, "persistence.conf", 1) }
                else entry(root, 11, BS - 24, "lost+found", 2)
                out.write(root.array())
                val lf = le(BS); entry(lf, 11, 12, ".", 2); entry(lf, 2, BS - 12, "..", 2)
                out.write(lf.array())
                rest -= 2
                if (conf) { out.write(CONF.toByteArray().copyOf(BS)); rest -= 1 }
            }
            zeros(rest * BS)
        }
        zeros(bytes - l.blocks.toLong() * BS)
        out.flush()
    }

    /** A one-block bitmap: the first [used] bits set, and every bit past [valid] set (they do not exist). */
    private fun bitmap(used: Int, valid: Int): ByteArray {
        val b = ByteArray(BS)
        for (i in 0 until used) b[i shr 3] = (b[i shr 3].toInt() or (1 shl (i and 7))).toByte()
        for (i in valid until BS * 8) b[i shr 3] = (b[i shr 3].toInt() or (1 shl (i and 7))).toByte()
        return b
    }

    private fun le(n: Int): ByteBuffer = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
}
