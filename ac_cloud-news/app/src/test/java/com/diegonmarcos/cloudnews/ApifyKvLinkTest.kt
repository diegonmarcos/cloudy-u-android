package com.diegonmarcos.cloudnews

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.apify.EncryptedApifyKv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1474 Cloud News crashed at startup with NoClassDefFoundError androidx/security/crypto/MasterKey$Builder in
 * EncryptedApifyKv.<init>: security-crypto was compileOnly in libs:crash and libs:apify, so the APK lacked it.
 * This instantiates the real class on News' own test runtime classpath and round-trips a value.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ApifyKvLinkTest {
    @Test fun securityCryptoIsOnNewsClasspath() {
        assertNotNull(Class.forName("androidx.security.crypto.MasterKey\$Builder"))
    }

    @Test fun encryptedApifyKvInstantiatesAndRoundTrips() {
        val kv = EncryptedApifyKv(ApplicationProvider.getApplicationContext())
        kv.set("token", " abc ")
        assertEquals("abc", kv.get("token"))
    }
}
