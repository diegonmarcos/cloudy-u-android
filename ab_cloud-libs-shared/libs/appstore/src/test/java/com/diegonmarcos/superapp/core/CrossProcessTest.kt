package com.diegonmarcos.superapp.core

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #1121: only android.os.ResultReceiver may cross into another app's process. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CrossProcessTest {

    private class Mine : ResultReceiver(Handler(Looper.getMainLooper())) {
        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {}
    }

    @Test fun aSubclassBecomesThePlainFrameworkClass() {
        val plain = CrossProcess.plainResultReceiver(Mine())
        assertEquals(ResultReceiver::class.java, plain.javaClass)
    }

    @Test fun anAnonymousSubclassBecomesThePlainFrameworkClass() {
        val anon = object : ResultReceiver(Handler(Looper.getMainLooper())) {}
        assertEquals(ResultReceiver::class.java, CrossProcess.plainResultReceiver(anon).javaClass)
    }

    @Test fun aPlainReceiverIsReturnedAsIs() {
        val plain = ResultReceiver(Handler(Looper.getMainLooper()))
        assertSame(plain, CrossProcess.plainResultReceiver(plain))
    }

    /** The class name a Parcelable extra writes to the wire is its runtime class (Parcel.writeParcelable writes
     *  getClass().getName()). Robolectric cannot marshall a Binder-backed ResultReceiver (NotSerializableException),
     *  so the test reads the extra's runtime class instead of the marshalled bytes. */
    @Suppress("DEPRECATION")
    private fun wireClass(i: Intent, key: String): String = (i.extras!!.get(key) as Any).javaClass.name

    /** What reaches the other process: the class names in the intent's bytes. None of ours may be there. */
    @Test fun theWireNamesNoAppClass() {
        val raw = wireClass(Intent("x").putExtra("receiver", Mine()), "receiver")
        assertTrue("control: a raw subclass puts its own class name on the wire", raw.contains("CrossProcessTest"))
        val safe = wireClass(CrossProcess.putPlainResultReceiver(Intent("x"), "receiver", Mine()), "receiver")
        assertFalse("the plain receiver still names an app class", safe.contains("CrossProcessTest"))
        assertEquals("android.os.ResultReceiver", safe)
    }
}
