package com.diegonmarcos.superapp.network

import android.app.Application
import android.os.Looper
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Regression for the StackOverflowError at MobileProbe.kt:247 (hundreds of identical
 * MobileProbe$Watch$observer$1.onChange frames). The Watch registers a ContentObserver on the
 * mobile-data / data-roaming / airplane Settings.Global switches; the observer used to call the
 * consumer straight away, so a consumer that (directly or via a write it makes) re-triggered the
 * observer recursed into onChange without bound and crashed the app — the runaway that also pegged
 * the CPU and jammed the adb shell. The fix routes every event through a re-entrancy-guarded,
 * coalesced dispatch: a change provoked while a delivery is in flight must never re-enter it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MobileProbeWatchTest {

    @Test fun `a change the consumer provokes mid-delivery never re-enters onChange`() {
        val app = RuntimeEnvironment.getApplication()
        val looper = shadowOf(Looper.getMainLooper())
        var calls = 0
        var provoke: () -> Unit = {}

        val watch = MobileProbe.Watch(app) { _ -> calls++; provoke() }
        watch.start()
        looper.idle()

        val uri = Settings.Global.getUriFor("mobile_data")
        val observers = shadowOf(app.contentResolver).getContentObservers(uri)
        assertTrue("the Watch did not register an observer on the mobile-data switch", observers.isNotEmpty())
        val observer = observers.first()

        // The consumer, while being told of one change, provokes the very observer again — exactly the
        // self-feeding the crash was. Before the guard this recursed into onChange until the stack blew.
        provoke = { observer.onChange(false) }

        observer.onChange(false)
        looper.idleFor(Duration.ofMillis(500))   // drain the coalesced delivery and any it would spawn

        assertEquals("onChange re-entered: the Watch is still a feedback loop", 1, calls)

        watch.stop()
    }
}
