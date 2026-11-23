package app.sterna.core.data.mail

import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * #1869 step 11: the offline read-state queue. Marking a message read (or unread) is applied to the local row AT ONCE and
 * queued here; the queue is flushed to the server right away and again at the start of every sync pass (which a
 * connectivity regain triggers), so a message read on a plane is read on the server once the phone is back online, and
 * the reader never waits on the network to clear an unread dot.
 *
 * One entry per (account, message), the LATEST state wins (read then unread offline is one "unread"). Persisted to
 * [file] (null: memory only, as in tests) so a queued change survives the process; at most [max] entries, oldest out.
 * A flushed entry is removed only if it still says what was sent, so a toggle queued during the flush is not lost.
 */
class ReadStateQueue(private val file: File?, private val max: Int = 500) {
    data class Entry(val accountId: String, val emailId: String, val seen: Boolean)

    private val lock = Any()
    private var entries: LinkedHashMap<String, Entry>? = null

    private fun key(accountId: String, emailId: String) = "$accountId\u0000$emailId"

    private fun loaded(): LinkedHashMap<String, Entry> {
        entries?.let { return it }
        val map = LinkedHashMap<String, Entry>()
        runCatching {
            file?.takeIf { it.isFile }?.readLines()?.forEach { line ->
                val parts = line.split('\t')
                if (parts.size != 3) return@forEach
                val e = Entry(dec(parts[0]), dec(parts[1]), parts[2] == "1")
                map[key(e.accountId, e.emailId)] = e
            }
        }
        entries = map
        return map
    }

    private fun save(map: LinkedHashMap<String, Entry>) {
        val f = file ?: return
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".part")
            tmp.writeText(map.values.joinToString("") { "${enc(it.accountId)}\t${enc(it.emailId)}\t${if (it.seen) 1 else 0}\n" })
            if (!tmp.renameTo(f)) tmp.delete()
        }
    }

    fun put(accountId: String, emailId: String, seen: Boolean) = synchronized(lock) {
        val map = loaded()
        val k = key(accountId, emailId)
        map.remove(k)
        map[k] = Entry(accountId, emailId, seen)
        while (map.size > max) map.remove(map.keys.first())
        save(map)
    }

    /** The account's queued changes, oldest first. */
    fun pending(accountId: String): List<Entry> = synchronized(lock) { loaded().values.filter { it.accountId == accountId } }

    /** Drop [entry] once the server took it, unless a newer state was queued for the message meanwhile. */
    fun done(entry: Entry) = synchronized(lock) {
        val map = loaded()
        val k = key(entry.accountId, entry.emailId)
        if (map[k] == entry) {
            map.remove(k)
            save(map)
        }
    }

    /** Send [accountId]'s queued states, oldest first, with [send]; stop at the first failure (offline) and keep the rest.
     *  Returns how many were sent. A cancellation is rethrown, never counted as a failure to keep going past. */
    suspend fun flush(accountId: String, send: suspend (Entry) -> Unit): Int {
        var sent = 0
        for (entry in pending(accountId)) {
            try {
                send(entry)
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (_: Throwable) {
                break
            }
            done(entry)
            sent++
        }
        return sent
    }

    fun dropAccount(accountId: String) = synchronized(lock) {
        val map = loaded()
        if (map.values.removeAll { it.accountId == accountId }) save(map)
    }

    val size: Int get() = synchronized(lock) { loaded().size }

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
    private fun dec(v: String) = URLDecoder.decode(v, "UTF-8")
}
