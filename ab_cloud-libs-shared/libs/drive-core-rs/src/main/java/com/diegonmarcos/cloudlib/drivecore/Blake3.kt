package com.diegonmarcos.cloudlib.drivecore

/**
 * BLAKE3 (default mode, 32-byte output) in plain Kotlin: the reference path that answers when the Rust core is off or unavailable,
 * so both implementations return the same digest (DriveCoreParityTest compares them with the official vectors and with the core).
 * Follows the BLAKE3 reference implementation: 1024-byte chunks of 64-byte blocks, a chaining-value stack for the tree.
 */
internal class Blake3 {
    private val key = IV.copyOf()
    private val stack = ArrayList<IntArray>()
    private var chunk = ChunkState(key, 0L)

    fun update(b: ByteArray, off: Int = 0, len: Int = b.size) {
        var o = off
        var left = len
        while (left > 0) {
            if (chunk.length() == CHUNK_LEN) {
                val cv = chunk.output().chainingValue()
                val total = chunk.counter + 1
                addChunkCv(cv, total)
                chunk = ChunkState(key, total)
            }
            val n = minOf(CHUNK_LEN - chunk.length(), left)
            chunk.update(b, o, n)
            o += n
            left -= n
        }
    }

    fun digest(): ByteArray {
        var out = chunk.output()
        var remaining = stack.size
        while (remaining > 0) {
            remaining--
            out = parentOutput(stack[remaining], out.chainingValue(), key)
        }
        return out.rootBytes()
    }

    private fun addChunkCv(first: IntArray, totalChunks: Long) {
        var cv = first
        var total = totalChunks
        while ((total and 1L) == 0L) {
            cv = parentOutput(stack.removeAt(stack.size - 1), cv, key).chainingValue()
            total = total ushr 1
        }
        stack.add(cv)
    }

    private class Output(val inputCv: IntArray, val block: IntArray, val counter: Long, val blockLen: Int, val flags: Int) {
        fun chainingValue(): IntArray = compress(inputCv, block, counter, blockLen, flags).copyOfRange(0, 8)
        fun rootBytes(): ByteArray {
            val w = compress(inputCv, block, 0L, blockLen, flags or ROOT)
            val out = ByteArray(32)
            for (i in 0 until 8) for (j in 0 until 4) out[i * 4 + j] = (w[i] ushr (8 * j)).toByte()
            return out
        }
    }

    private class ChunkState(val cv0: IntArray, val counter: Long) {
        private var cv = cv0.copyOf()
        private val buf = ByteArray(BLOCK_LEN)
        private var bufLen = 0
        private var blocks = 0

        fun length(): Int = BLOCK_LEN * blocks + bufLen
        private fun startFlag(): Int = if (blocks == 0) CHUNK_START else 0

        fun update(b: ByteArray, off: Int, len: Int) {
            var o = off
            var left = len
            while (left > 0) {
                if (bufLen == BLOCK_LEN) {
                    cv = compress(cv, words(buf), counter, BLOCK_LEN, startFlag()).copyOfRange(0, 8)
                    blocks++
                    bufLen = 0
                    buf.fill(0)
                }
                val n = minOf(BLOCK_LEN - bufLen, left)
                System.arraycopy(b, o, buf, bufLen, n)
                bufLen += n
                o += n
                left -= n
            }
        }

        fun output(): Output = Output(cv, words(buf), counter, bufLen, startFlag() or CHUNK_END)
    }

    companion object {
        private const val CHUNK_LEN = 1024
        private const val BLOCK_LEN = 64
        private const val CHUNK_START = 1
        private const val CHUNK_END = 2
        private const val PARENT = 4
        private const val ROOT = 8
        private val IV = intArrayOf(0x6A09E667, 0xBB67AE85.toInt(), 0x3C6EF372, 0xA54FF53A.toInt(), 0x510E527F, 0x9B05688C.toInt(), 0x1F83D9AB, 0x5BE0CD19)
        private val PERM = intArrayOf(2, 6, 3, 10, 7, 0, 4, 13, 1, 11, 12, 5, 9, 14, 15, 8)

        private fun words(b: ByteArray): IntArray {
            val w = IntArray(16)
            for (i in 0 until 16) w[i] = (b[i * 4].toInt() and 0xFF) or ((b[i * 4 + 1].toInt() and 0xFF) shl 8) or ((b[i * 4 + 2].toInt() and 0xFF) shl 16) or ((b[i * 4 + 3].toInt() and 0xFF) shl 24)
            return w
        }

        private fun parentOutput(left: IntArray, right: IntArray, key: IntArray): Output {
            val block = IntArray(16)
            System.arraycopy(left, 0, block, 0, 8)
            System.arraycopy(right, 0, block, 8, 8)
            return Output(key, block, 0L, BLOCK_LEN, PARENT)
        }

        private fun g(s: IntArray, a: Int, b: Int, c: Int, d: Int, mx: Int, my: Int) {
            s[a] = s[a] + s[b] + mx
            s[d] = Integer.rotateRight(s[d] xor s[a], 16)
            s[c] = s[c] + s[d]
            s[b] = Integer.rotateRight(s[b] xor s[c], 12)
            s[a] = s[a] + s[b] + my
            s[d] = Integer.rotateRight(s[d] xor s[a], 8)
            s[c] = s[c] + s[d]
            s[b] = Integer.rotateRight(s[b] xor s[c], 7)
        }

        private fun round(s: IntArray, m: IntArray) {
            g(s, 0, 4, 8, 12, m[0], m[1]); g(s, 1, 5, 9, 13, m[2], m[3]); g(s, 2, 6, 10, 14, m[4], m[5]); g(s, 3, 7, 11, 15, m[6], m[7])
            g(s, 0, 5, 10, 15, m[8], m[9]); g(s, 1, 6, 11, 12, m[10], m[11]); g(s, 2, 7, 8, 13, m[12], m[13]); g(s, 3, 4, 9, 14, m[14], m[15])
        }

        private fun compress(cv: IntArray, block: IntArray, counter: Long, blockLen: Int, flags: Int): IntArray {
            val s = intArrayOf(
                cv[0], cv[1], cv[2], cv[3], cv[4], cv[5], cv[6], cv[7],
                IV[0], IV[1], IV[2], IV[3],
                counter.toInt(), (counter ushr 32).toInt(), blockLen, flags,
            )
            var m = block.copyOf()
            for (r in 0 until 7) {
                round(s, m)
                if (r < 6) { val p = IntArray(16); for (i in 0 until 16) p[i] = m[PERM[i]]; m = p }
            }
            for (i in 0 until 8) { s[i] = s[i] xor s[i + 8]; s[i + 8] = s[i + 8] xor cv[i] }
            return s
        }
    }
}
