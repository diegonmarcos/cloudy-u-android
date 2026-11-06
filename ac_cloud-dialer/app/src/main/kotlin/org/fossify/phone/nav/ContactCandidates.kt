package org.fossify.phone.nav

import android.content.Context
import android.provider.CallLog
import org.fossify.phone.sms.ContactLookup

/** The contacts the keypad's T9 search and the VoIP page's recents draw from: every contact number plus how recently and often it was called. */
object ContactCandidates {
    private const val CALL_LOG_ROWS = 500
    private const val KEY_TAIL = 9

    private fun tail(number: String) = T9.digits(number).takeLast(KEY_TAIL)

    fun load(ctx: Context): List<T9Contact> {
        val stats = callStats(ctx)
        val people = ContactLookup(ctx).recipients()
        val known = people.mapTo(HashSet()) { tail(it.number) }
        val contacts = people.map { r -> stats[tail(r.number)].let { s -> T9Contact(r.name, r.number, s?.last ?: 0, s?.count ?: 0) } }
        val strangers = stats.filterKeys { it !in known }.values.map { T9Contact(it.number, it.number, it.last, it.count) }
        return contacts + strangers
    }

    private class Stat(val number: String, var last: Long, var count: Int)

    /** Call log (newest rows first) folded by number tail; empty without the call-log grant. */
    private fun callStats(ctx: Context): Map<String, Stat> {
        val out = HashMap<String, Stat>()
        runCatching {
            ctx.contentResolver.query(
                CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE), null, null,
                "${CallLog.Calls.DATE} DESC LIMIT $CALL_LOG_ROWS",
            )?.use { c ->
                while (c.moveToNext()) {
                    val number = c.getString(0)?.takeIf { T9.digits(it).isNotEmpty() } ?: continue
                    val s = out.getOrPut(tail(number)) { Stat(number, c.getLong(1), 0) }
                    s.count++
                    if (c.getLong(1) > s.last) s.last = c.getLong(1)
                }
            }
        }
        return out
    }
}
