package app.sterna.core.data.mail

/**
 * #1869 step 10: the tap-time handoff. A tap on a list row starts the reader's local snapshot at once ([offer]), before
 * the navigation and the page's composition; the page's first read takes it ([take]) instead of a second IO hop, and the
 * route skips its fade when one is [ready].
 *
 * A small in-memory LRU with a short life. It REFUSES a decrypted body (anything whose crypto state is not the Locked
 * card): plaintext of encrypted mail lives only in the repository's own decrypted cache (ENCRYPTION.md), never here.
 */
class ReaderHandoff(
    private val capacity: Int = 4,
    private val ttlMillis: Long = 10_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Entry(val snapshot: ReaderSnapshot, val at: Long)

    private val entries = object : LinkedHashMap<String, Entry>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > capacity
    }

    private fun key(accountId: String, emailId: String) = "$accountId\u0000$emailId"

    /** Hold [snapshot] for the page about to open; false (and nothing held) for a decrypted body or a [ReaderSnapshot.Miss]. */
    fun offer(accountId: String, emailId: String, snapshot: ReaderSnapshot): Boolean {
        val hit = snapshot as? ReaderSnapshot.Hit ?: return false
        if (!holdable(hit.body)) return false
        synchronized(entries) { entries[key(accountId, emailId)] = Entry(snapshot, clock()) }
        return true
    }

    /** The held snapshot, once (it is removed), or null when none is held or it is older than [ttlMillis]. */
    fun take(accountId: String, emailId: String): ReaderSnapshot? = synchronized(entries) {
        entries.remove(key(accountId, emailId))?.takeIf { clock() - it.at <= ttlMillis }?.snapshot
    }

    /** Whether a fresh snapshot is held for this message (the route's fade decision); does not consume it. */
    fun ready(accountId: String?, emailId: String): Boolean = synchronized(entries) {
        val now = clock()
        entries.entries.any { (k, e) ->
            (if (accountId != null) k == key(accountId, emailId) else k.endsWith("\u0000$emailId")) && now - e.at <= ttlMillis
        }
    }

    companion object {
        /** Only plain mail or the Locked card of ciphertext; never a decrypted (or verified) body. */
        fun holdable(body: MessageBody): Boolean = body.crypto == null || body.crypto is MessageCrypto.Locked
    }
}
