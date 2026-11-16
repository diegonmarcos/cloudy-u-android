package app.sterna.mail

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import android.webkit.WebView
import app.sterna.core.data.mail.MailTiming
import app.sterna.core.data.mail.PrefetchConditions

/** What the device says about background body downloads (#1763): connected, unmetered (Wi-Fi), battery saver. */
object DevicePrefetchConditions {
    fun read(context: Context): PrefetchConditions {
        val app = context.applicationContext
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val connected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val unmetered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
        val saver = (app.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true
        return PrefetchConditions(connected = connected, unmetered = unmetered, batterySaver = saver)
    }
}

/** Starts the WebView provider once per process, off the open path (#1763): the first `WebView(...)` of a
 *  process costs hundreds of ms, which the first opened mail would otherwise pay. */
object WebViewWarmUp {
    @Volatile private var done = false

    /** Posts to the main thread's idle queue; creating and destroying one view is the whole warm-up. */
    fun once(context: Context) {
        if (done) return
        done = true
        val app = context.applicationContext
        android.os.Looper.getMainLooper().queue.addIdleHandler {
            val t0 = System.nanoTime()
            runCatching { WebView(app).destroy() }
            MailTiming.log("-", "webview-warmup", (System.nanoTime() - t0) / 1_000_000)
            false
        }
    }
}
