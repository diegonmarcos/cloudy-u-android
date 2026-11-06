package com.diegonmarcos.superapp.launcher.folders

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1553 The runtime repository: on-demand Refresh re-enumerates so a newly installed/added app surfaces in Others,
 * scopes enumerate independently, and a user edit persists as an override. No provider is running under Robolectric,
 * so the served tables are empty and every app lands in Others — exactly the "dataset not served yet" path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FolderRepositoryTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()

    // Injected enumeration: Phone grows by one app on the second scan; Cloud is a different, fixed set.
    private var phone = mutableListOf(FolderApp("p.one", "One"))
    private val cloud = listOf(FolderApp("c.alpha", "Alpha"))
    private fun enumerate(@Suppress("UNUSED_PARAMETER") c: android.content.Context, s: FolderScope) =
        if (s == FolderScope.PHONE) phone.toList() else cloud

    @Before fun clean() {
        ctx.getSharedPreferences("launcher_folder_overrides", android.content.Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun repo(): FolderRepository {
        var t = 0L
        return FolderRepository(ctx, enumerate = ::enumerate, now = { t += 100_000L; t })  // each read is "stale" → re-enumerates
    }

    @Test fun `R1 refresh adds a newly installed app to Others`() {
        val r = repo()
        val before = r.snapshot(FolderScope.PHONE).flatMap { it.folders }.flatMap { it.apps }.map { it.pkg }
        assertEquals(listOf("p.one"), before)
        phone.add(FolderApp("p.two", "Two"))          // a new app installs
        val after = r.refresh(FolderScope.PHONE).single { it.title == OTHERS_SECTION }.folders.single().apps.map { it.pkg }
        assertEquals("the new app appears in Others on refresh", listOf("p.one", "p.two"), after.sorted())
    }

    @Test fun `R2 Cloud and Phone enumerate independent app sets`() {
        val r = repo()
        val p = r.snapshot(FolderScope.PHONE).flatMap { it.folders }.flatMap { it.apps }.map { it.pkg }
        val c = r.snapshot(FolderScope.CLOUD).flatMap { it.folders }.flatMap { it.apps }.map { it.pkg }
        assertTrue(p.contains("p.one") && !p.contains("c.alpha"))
        assertTrue(c.contains("c.alpha") && !c.contains("p.one"))
    }

    @Test fun `R3 a user assignment persists as an override`() {
        FolderRepository(ctx, enumerate = ::enumerate).assign(FolderScope.PHONE, "p.one", "dev")
        assertEquals("dev", FolderOverrideStore(ctx).all(FolderScope.PHONE)["p.one"])
        // and it is scoped: Cloud has no such override
        assertTrue(FolderOverrideStore(ctx).all(FolderScope.CLOUD).isEmpty())
    }
}
