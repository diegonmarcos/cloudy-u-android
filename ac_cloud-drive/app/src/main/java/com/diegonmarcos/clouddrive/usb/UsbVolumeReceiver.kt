package com.diegonmarcos.clouddrive.usb

import com.diegonmarcos.superapp.usbdrive.UsbVolumeModel
import com.diegonmarcos.superapp.usbdrive.StorageVolumes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * #1375 the media mount broadcasts (MEDIA_MOUNTED / EJECT / UNMOUNTED / REMOVED / BAD_REMOVAL on the file scheme, all
 * exempt from the Android 8 implicit-broadcast limits) and USB_DEVICE_DETACHED. Light on purpose: it records the drive
 * state ([StorageVolumes.onBroadcast]) so Explorer's places and the Volumes card re-read and show "Safe to remove";
 * the raw session closes its own streams on detach (UsbDriveActivity).
 */
class UsbVolumeReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action ?: return
        val key = UsbVolumeModel.pathOfMediaUri(intent.dataString)
            ?: UsbDriveActivity.deviceOf(intent)?.let { "usb:" + it.deviceName }
            ?: return
        StorageVolumes.onBroadcast(key, action)
    }
}
