package app.sterna.core.data.mail

import app.sterna.core.data.text.sanitiseReceivedHtml
import app.sterna.core.jmap.model.Email

/**
 * #1869 step 7: the reader's sanitised fragment of a message, stored with its body (`email_bodies.fragment`) so an open
 * never runs the sanitiser over a large newsletter on a cached body.
 *
 * The fragment is EXACTLY what the one policy function ([sanitiseReceivedHtml]) answers for the message's html part, so
 * the reader's document built from it is byte-identical to the one built from the html (the golden test); `cid:`
 * references are kept (the policy keeps them; the reader embeds or intercepts them). The dark-mode rewrite, the
 * deceptive-link marker and the quote fold stay in the document builder, which the print document shares.
 *
 * The app's reader reaches the policy only through [sanitised], a small memo keyed by the exact source html; a stored
 * fragment read from Room seeds it ([remember]), so the first frame of a cached message finds its fragment already made.
 * Pure in, pure out: the memo can only answer what the policy answered for the same input, or what was stored from it
 * under the same [VERSION].
 */
object ReaderFragment {
    /** Bump when the policy or this fragment's shape changes: older rows are rebuilt lazily on Default, never purged. */
    const val VERSION = 1

    /** The fragment of [email], or null when it has no html part (a text page is cheap and built at render). */
    fun build(email: Email): String? = email.htmlContent()?.takeIf { it.isNotBlank() }?.let(::sanitised)

    /** The policy's answer for [html], from the memo when this source was sanitised (or read from Room) before. */
    fun sanitised(html: String): String {
        synchronized(memo) { memo[html] }?.let { return it }
        val clean = sanitiseReceivedHtml(html)
        synchronized(memo) { memo[html] = clean }
        return clean
    }

    /** Seed the memo with a fragment stored for [email] under the current [VERSION]. */
    fun remember(email: Email, fragment: String) {
        val html = email.htmlContent()?.takeIf { it.isNotBlank() } ?: return
        synchronized(memo) { memo[html] = fragment }
    }

    /** The open message and its pager neighbours (2 each side) and one spare, least recently used out first. */
    private const val MEMO_SIZE = 6

    private val memo = object : LinkedHashMap<String, String>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > MEMO_SIZE
    }
}
