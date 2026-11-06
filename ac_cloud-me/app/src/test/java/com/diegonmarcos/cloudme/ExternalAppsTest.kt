package com.diegonmarcos.cloudme

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
/** Cloud Me > Tasks links to Cloud Tasks: the declared external app resolves to its explicit launcher activity, or to a clear "missing" answer. */
class ExternalAppsTest {
    private val build = JSONObject(BuroLlcTestData.text("build.json"))
    private val all = ExternalApps.parse(build.getJSONObject("ui").getJSONArray("external_apps"))
    private val tasksManifest = BuroLlcTestData.text("ac_cloud-tasks/app/src/main/AndroidManifest.xml")
    private val tasksBuild = JSONObject(BuroLlcTestData.text("ac_cloud-tasks/build.json"))

    @Test fun `the Tasks target resolves to the declared launcher activity of Cloud Tasks`() {
        val r = ExternalApps.resolve(all, "extapp:cloud-tasks") { true }
        val pkg = tasksBuild.getJSONObject("android").getString("application_id")
        assertEquals(ExternalApps.Resolution.Open(pkg, "$pkg.MainActivity"), r)
        assertTrue(tasksManifest.contains("android:name=\".MainActivity\""))
    }

    @Test fun `a hint after the slash is ignored when resolving`() {
        assertTrue(ExternalApps.resolve(all, "extapp:cloud-tasks/inbox") { true } is ExternalApps.Resolution.Open)
    }

    @Test fun `not installed gives the declared label for the message`() {
        assertEquals(ExternalApps.Resolution.Missing("Cloud Tasks"), ExternalApps.resolve(all, "extapp:cloud-tasks") { false })
    }

    @Test fun `an undeclared id stays a silent no-op`() {
        assertEquals(ExternalApps.Resolution.Unknown, ExternalApps.resolve(all, "extapp:nope") { true })
    }

    @Test fun `the Tasks backlogs page declares the Open in Cloud Tasks link`() {
        val p = org.json.JSONArray(BuroLlcTestData.text("data/ui/tasks/backlogs.json")).getJSONObject(0)
        assertEquals("extapp:cloud-tasks", p.getString("open"))
    }
}
