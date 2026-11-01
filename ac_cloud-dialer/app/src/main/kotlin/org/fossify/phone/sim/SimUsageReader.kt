package org.fossify.phone.sim

import android.Manifest
import android.annotation.SuppressLint
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.provider.CallLog
import android.provider.Telephony

/**
 * Usage events per SIM: CallLog (PHONE_ACCOUNT_ID, which telephony fills with the subscription id or the ICCID), the SMS
 * provider (sub_id) and NetworkStatsManager (per subscriber where the platform allows it, else all mobile traffic on the
 * default data SIM). Grants: call log (dialer role), sms_read and usage_access through libs:perms. Nothing is logged.
 */
@SuppressLint("MissingPermission")
class SimUsageReader(private val ctx: Context) {
    private fun has(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun events(card: SimCard, since: Long): List<UsageEvent> = calls(card, since) + sms(card, since)

    private fun matches(card: SimCard, account: String?): Boolean =
        account != null && (account == card.subscriptionId?.toString() || (card.iccid != null && account.startsWith(card.iccid)))

    private fun calls(card: SimCard, since: Long): List<UsageEvent> {
        if (!has(Manifest.permission.READ_CALL_LOG)) return emptyList()
        val out = mutableListOf<UsageEvent>()
        runCatching {
            ctx.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.DATE, CallLog.Calls.TYPE, CallLog.Calls.DURATION, CallLog.Calls.PHONE_ACCOUNT_ID),
                "${CallLog.Calls.DATE} >= ?", arrayOf(since.toString()), null,
            )?.use { c ->
                while (c.moveToNext()) {
                    if (!matches(card, c.getString(3))) continue
                    val type = c.getInt(1)
                    if (type != CallLog.Calls.INCOMING_TYPE && type != CallLog.Calls.OUTGOING_TYPE) continue
                    out += UsageEvent(c.getLong(0), UsageEvent.Kind.CALL, type == CallLog.Calls.OUTGOING_TYPE, c.getLong(2))
                }
            }
        }
        return out
    }

    private fun sms(card: SimCard, since: Long): List<UsageEvent> {
        if (!has(Manifest.permission.READ_SMS) || card.subscriptionId == null) return emptyList()
        val out = mutableListOf<UsageEvent>()
        runCatching {
            ctx.contentResolver.query(
                Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.DATE, Telephony.Sms.TYPE),
                "${Telephony.Sms.DATE} >= ? AND ${Telephony.Sms.SUBSCRIPTION_ID} = ?",
                arrayOf(since.toString(), card.subscriptionId.toString()), null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val t = c.getInt(1)
                    if (t == Telephony.Sms.MESSAGE_TYPE_INBOX) out += UsageEvent(c.getLong(0), UsageEvent.Kind.SMS, false)
                    if (t == Telephony.Sms.MESSAGE_TYPE_SENT) out += UsageEvent(c.getLong(0), UsageEvent.Kind.SMS, true)
                }
            }
        }
        return out
    }

    /** Mobile bytes in [p]; null without usage access or when this SIM is not the default data SIM. */
    @Suppress("DEPRECATION")
    fun dataBytes(card: SimCard, p: UsagePeriod): Long? {
        if (!card.defaultData) return null
        return runCatching {
            val b = ctx.getSystemService(NetworkStatsManager::class.java)
                ?.querySummaryForDevice(ConnectivityManager.TYPE_MOBILE, null, p.startMillis, p.endMillis) ?: return null
            b.rxBytes + b.txBytes
        }.getOrNull()
    }
}
