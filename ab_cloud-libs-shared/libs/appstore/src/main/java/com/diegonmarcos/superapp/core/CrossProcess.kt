package com.diegonmarcos.superapp.core

import android.content.Intent
import android.os.Parcel
import android.os.ResultReceiver

/**
 * What one fleet app may put in an Intent bound for ANOTHER package. The receiving process unmarshals
 * every Parcelable extra by class name with its own classloader, so a subclass that only the sender
 * has (an anonymous `object : ResultReceiver(...)`) is a BadParcelableException that kills the
 * receiver's service (#1121, Cloud Terminal's UnpackService on the phone). Only framework classes
 * may cross. test-cross-process-receiver.sh fails a file that sends a custom ResultReceiver to
 * another package without going through here.
 */
object CrossProcess {

    /**
     * A plain [ResultReceiver] that forwards to [local]: written to a Parcel and read back, only the
     * binder is left, so the other app sees android.os.ResultReceiver alone and its send() still
     * reaches [local]'s onReceiveResult in this process.
     */
    @JvmStatic
    fun plainResultReceiver(local: ResultReceiver): ResultReceiver {
        if (local.javaClass == ResultReceiver::class.java) return local
        val p = Parcel.obtain()
        try {
            local.writeToParcel(p, 0)
            p.setDataPosition(0)
            return ResultReceiver.CREATOR.createFromParcel(p)
        } finally {
            p.recycle()
        }
    }

    /** [Intent.putExtra] of [receiver] made safe for another package. */
    @JvmStatic
    fun putPlainResultReceiver(intent: Intent, key: String, receiver: ResultReceiver): Intent =
        intent.putExtra(key, plainResultReceiver(receiver))
}
