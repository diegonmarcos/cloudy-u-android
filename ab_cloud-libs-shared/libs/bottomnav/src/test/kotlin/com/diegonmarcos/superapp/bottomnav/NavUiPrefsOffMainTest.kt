package com.diegonmarcos.superapp.bottomnav

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * NavUiPrefs is built during the first composition (NavBarTopFirstRowScaffold -> currentNavUi), on the main thread. Its SharedPreferences
 * read must not happen there (StrictMode: a 1.4-2.9 s disk read at CG-Omniverse launch). Mutation: nav-mutation.yml `prefs-eager`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NavUiPrefsOffMainTest {
    private class Recording(base: Context) : ContextWrapper(base) {
        @Volatile var readOn: Thread? = null
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
            if (readOn == null) readOn = Thread.currentThread()
            return super.getSharedPreferences(name, mode)
        }
    }

    @Test fun `the stored choice is read off the constructing thread, then shown`() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences(NavUiPrefs.NAME, Context.MODE_PRIVATE).edit().putString(NavUiPrefs.KEY_MODE, NavUiMode.Clean.declared).commit()
        val ctx = Recording(app)
        val prefs = NavUiPrefs(ctx)
        val deadline = System.currentTimeMillis() + 5_000
        while (prefs.mode() == null && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertNotNull("the prefs were read", ctx.readOn)
        assertNotSame("read on the constructing (main) thread", Thread.currentThread(), ctx.readOn)
        assertEquals(NavUiMode.Clean, prefs.mode())
    }
}
