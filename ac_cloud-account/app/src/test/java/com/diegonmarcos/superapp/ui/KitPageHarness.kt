package com.diegonmarcos.superapp.ui

import android.content.ComponentName
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.diegonmarcos.superapp.profile.AccountHost
import com.diegonmarcos.superapp.uikit.kitComposeView
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * #1693 the page host the libs:account suites stand on, now that they run in Cloud Account (they moved from the
 * SuperApp with libs:account): a FragmentActivity, a page added the way a shell adds pages, and the compose rule
 * that reads its semantics tree. Same shape as the SuperApp's harness; the palette is the Account host's.
 */
abstract class KitPageHarness {

    protected val compose = createAndroidComposeRule<FragmentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                shadowOf(app.packageManager)
                    .addActivityIfNotPresent(ComponentName(app, FragmentActivity::class.java))
                base.evaluate()
            }
        }
    }).around(compose)

    protected fun <F : Fragment> show(page: F): F {
        compose.runOnUiThread {
            compose.activity.supportFragmentManager.beginTransaction()
                .add(android.R.id.content, page).commitNow()
        }
        compose.waitForIdle()
        return page
    }

    protected fun showKit(content: @Composable () -> Unit) {
        compose.runOnUiThread {
            val a = compose.activity
            a.setContentView(a.kitComposeView(AccountHost.palette(a), content))
        }
        compose.waitForIdle()
    }
}
