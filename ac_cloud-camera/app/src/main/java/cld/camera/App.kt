package cld.camera

import android.app.Application
import android.location.Location
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import cld.camera.capturer.deleteStalePendingRecordings
import cld.camera.ui.activities.MainActivity
import com.diegonmarcos.superapp.locationclient.NavLocationClient
import com.google.android.material.color.DynamicColors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class App : Application() {

    companion object {
        // Must stay above the ~10 min throttle the OS applies to coarse-only apps.
        private const val MAX_LOCATION_AGE = 15 * 60 * 1000L
        private const val POLL_MS = 15_000L
    }

    private var activity: MainActivity? = null
    private var location: Location? = null

    /**
     * #1770 geotagging reads the last known fix from Cloud Nav, the fleet's one location owner (libs:location-client). This app
     * starts no location fix and holds no location permission: [poll] is a cached read over the fleet IPC door.
     */
    private var polling = false
    private val ui = Handler(Looper.getMainLooper())
    private val io by lazy { java.util.concurrent.Executors.newSingleThreadExecutor() }
    private val poll = object : Runnable {
        override fun run() {
            if (!polling) return
            io.execute {
                val fix = NavLocationClient.lastKnown(this@App, MAX_LOCATION_AGE)
                ui.post { if (polling && fix != null) location = toLocation(fix) }
            }
            ui.postDelayed(this, POLL_MS)
        }
    }

    private fun toLocation(f: NavLocationClient.Fix) = Location("cloud-nav").apply {
        latitude = f.lat; longitude = f.lon
        f.accuracyM?.let { accuracy = it }
        time = f.timeMs
        val ageMs = (System.currentTimeMillis() - f.timeMs).coerceAtLeast(0L)
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - TimeUnit.MILLISECONDS.toNanos(ageMs)
    }

    private val autoSleepDuration: Long = 5 * 60 * 1000 // 5 minutes
    private val autoSleepTimer = object : CountDownTimer(
        autoSleepDuration,
        autoSleepDuration / 2
    ) {
        override fun onTick(milliLeft: Long) {}

        override fun onFinish() {
            activity?.enableAutoSleep()
        }
    }

    private val activityLifeCycleHelper by lazy {
        ActivityLifeCycleHelper { activity ->
            if (activity != null) activity.disableAutoSleep() else this.activity?.enableAutoSleep()
            this.activity = activity
        }
    }

    /** Cloud Nav answered its last fix at the latest read (the only "provider" this app has). */
    fun isAnyLocationProvideActive(): Boolean = NavLocationClient.last is NavLocationClient.Answer.Ok

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(activityLifeCycleHelper)
        DynamicColors.applyToActivitiesIfAvailable(this)

        thread {
            deleteStalePendingRecordings(this)
        }
    }

    fun readNavLocation(reAttach: Boolean = false) {
        if (polling && !reAttach) return
        polling = true
        ui.removeCallbacks(poll)
        ui.post(poll)
    }

    fun dropLocationUpdates() {
        polling = false
        ui.removeCallbacks(poll)
    }

    fun disableLocationFetching() {
        dropLocationUpdates()
        location = null
    }

    fun getLocation(): Location? {
        val location = this.location ?: return null
        val ageMs = TimeUnit.NANOSECONDS.toMillis(
            SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
        )
        if (ageMs > MAX_LOCATION_AGE) {
            this.location = null
            return null
        }
        return location
    }

    /** No grant is needed: the position is read from Cloud Nav, never from the platform. */
    fun shouldAskForLocationPermission() = false

    override fun onTerminate() {
        super.onTerminate()
        unregisterActivityLifecycleCallbacks(activityLifeCycleHelper)
    }

    private fun AppCompatActivity.disableAutoSleep() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        resetPreventScreenFromSleeping()
    }

    fun resetPreventScreenFromSleeping() {
        autoSleepTimer.cancel()
        autoSleepTimer.start()
    }

    private fun AppCompatActivity.enableAutoSleep() {
        autoSleepTimer.cancel()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
