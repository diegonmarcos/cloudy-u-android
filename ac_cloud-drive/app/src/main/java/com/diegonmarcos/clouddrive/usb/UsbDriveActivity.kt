package com.diegonmarcos.clouddrive.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.diegonmarcos.clouddrive.MainActivity
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.ui.DriveTheme
import com.diegonmarcos.superapp.perms.PermsUsb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * #1375 what "Choose an app for the USB device" opens (the USB_DEVICE_ATTACHED filter + res/xml/device_filter.xml,
 * mass-storage class 8), and the Volumes card's "Open" for a drive Android did not mount.
 *
 *  1. Android mounted the drive (the usual case: FAT32/exFAT, NTFS on some phones): Cloud Drive opens Explorer
 *     straight on it (MainActivity + [EXTRA_OPEN_PATH]) and this activity is gone. Mounting takes a moment after the
 *     attach, so the volumes are polled for up to [MOUNT_WAIT_MS].
 *  2. It did not: the USB permission is asked through libs:perms (PermsUsb.request) and the drive is browsed here,
 *     raw, with libaums (RawUsbDrive, FAT32): list, copy to the phone, delete, Eject.
 *
 * Detach is safe: USB_DEVICE_DETACHED closes every open stream and the device; Eject does the same first and then
 * says "Safe to remove".
 */
class UsbDriveActivity : ComponentActivity() {

    private sealed class Ui {
        object Looking : Ui()
        object Asking : Ui()
        object Denied : Ui()
        object NoDrive : Ui()
        data class Failed(val why: String) : Ui()
        data class Browsing(val path: String, val entries: List<RawUsbDrive.Entry>, val note: String = "") : Ui()
        data class Gone(val state: UsbVolumeModel.DriveState) : Ui()
    }

    private var ui by mutableStateOf<Ui>(Ui.Looking)
    private var drive: RawUsbDrive? = null
    private var state = UsbVolumeModel.DriveState.MOUNTED

    private val detach = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val gone = deviceOf(i) ?: return
            val d = drive
            if (d != null && d.usbDevice.deviceId != gone.deviceId) return
            val closedBefore = d == null || d.closed
            d?.close()
            state = UsbVolumeModel.next(state, UsbVolumeModel.ACTION_USB_DEVICE_DETACHED, sessionClosed = closedBefore)
            StorageVolumes.onBroadcast("usb:" + gone.deviceName, UsbVolumeModel.ACTION_USB_DEVICE_DETACHED, closedBefore)
            ui = Ui.Gone(state)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        // A system broadcast: exported so the platform's USB service reaches it on every API level.
        ContextCompat.registerReceiver(this, detach, filter, ContextCompat.RECEIVER_EXPORTED)
        setContent { DriveTheme { Screen() } }
        start(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (drive == null) start(intent)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(detach) }
        drive?.close()
        super.onDestroy()
    }

    private fun start(i: Intent?) {
        val device = i?.let(::deviceOf) ?: PermsUsb.drives(this).firstOrNull()
        ui = Ui.Looking
        lifecycleScope.launch {
            // 1. the system mount, if Android makes one
            val waited = if (device == null) 0L else MOUNT_WAIT_MS
            var t = 0L
            while (true) {
                val target = withContext(Dispatchers.IO) { UsbVolumeModel.toOpen(emptySet(), StorageVolumes.read(this@UsbDriveActivity)) }
                if (target != null) { openInExplorer(target.path); return@launch }
                if (t >= waited) break
                delay(POLL_MS); t += POLL_MS
            }
            // 2. raw USB
            if (device == null) { ui = Ui.NoDrive; return@launch }
            ui = Ui.Asking
            PermsUsb.request(this@UsbDriveActivity, device) { ok -> if (ok) openRaw(device) else { ui = Ui.Denied } }
        }
    }

    private fun openInExplorer(path: String) {
        startActivity(Intent(this, MainActivity::class.java).putExtra(EXTRA_OPEN_PATH, path).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    private fun openRaw(device: UsbDevice) {
        lifecycleScope.launch {
            when (val r = withContext(Dispatchers.IO) { RawUsbDrive.open(this@UsbDriveActivity, device) }) {
                is RawUsbDrive.Opened.Ok -> { drive = r.drive; state = UsbVolumeModel.DriveState.MOUNTED; browse("/") }
                is RawUsbDrive.Opened.Unsupported -> { ui = Ui.Failed(r.why) }
                is RawUsbDrive.Opened.Failed -> { ui = Ui.Failed(r.why) }
            }
        }
    }

    private fun browse(path: String, note: String = "") {
        val d = drive ?: return
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { d.list(path) } }
                .onSuccess { ui = Ui.Browsing(path, it, note) }
                .onFailure { ui = Ui.Failed(it.message ?: it.javaClass.simpleName) }
        }
    }

    private fun eject() {
        val d = drive ?: return
        ui = Ui.Gone(UsbVolumeModel.DriveState.EJECTING)
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { d.close() }
            state = UsbVolumeModel.next(UsbVolumeModel.DriveState.EJECTING, UsbVolumeModel.ACTION_MEDIA_UNMOUNTED)
            ui = Ui.Gone(state)
        }
    }

    /** Copies land in Download/Cloud Drive USB on the phone. */
    private fun copyDir(): File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Cloud Drive USB")

    @Composable
    private fun Screen() {
        val u = ui
        BackHandler(enabled = u is Ui.Browsing && u.path != "/") { (u as? Ui.Browsing)?.let { browse(UsbVolumeModel.rawParent(it.path)) } }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(DriveMetrics.gutter), verticalArrangement = Arrangement.spacedBy(DriveMetrics.gapWide)) {
                Text(drive?.label ?: getString(R.string.usb_open_drive), style = MaterialTheme.typography.titleLarge)
                drive?.sizeHint?.takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                when (u) {
                    Ui.Looking -> Text(getString(R.string.usb_looking))
                    Ui.Asking -> Text(getString(R.string.usb_asking))
                    Ui.Denied -> Message(getString(R.string.usb_denied))
                    Ui.NoDrive -> Message(getString(R.string.usb_none))
                    is Ui.Failed -> Message(getString(R.string.usb_failed, u.why))
                    is Ui.Gone -> Message(getString(when (u.state) {
                        UsbVolumeModel.DriveState.EJECTING -> R.string.usb_ejecting
                        UsbVolumeModel.DriveState.SAFE_TO_REMOVE -> R.string.usb_safe_to_remove
                        UsbVolumeModel.DriveState.REMOVED -> R.string.usb_removed
                        UsbVolumeModel.DriveState.REMOVED_UNSAFELY -> R.string.usb_removed_unsafely
                        UsbVolumeModel.DriveState.MOUNTED -> R.string.usb_looking
                    }))
                    is Ui.Browsing -> Browser(u)
                }
            }
        }
    }

    @Composable
    private fun Message(text: String) {
        Text(text)
        Button(onClick = { finish() }) { Text(getString(R.string.usb_close)) }
    }

    @Composable
    private fun Browser(b: Ui.Browsing) {
        Text(getString(R.string.usb_raw_hint), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(b.path, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            if (b.path != "/") TextButton(onClick = { browse(UsbVolumeModel.rawParent(b.path)) }) { Text(getString(R.string.usb_up)) }
            Button(onClick = { eject() }) { Text(getString(R.string.usb_eject)) }
        }
        if (b.note.isNotEmpty()) Text(b.note, style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        if (b.entries.isEmpty()) Text(getString(R.string.usb_empty))
        LazyColumn(Modifier.fillMaxSize()) {
            items(b.entries, key = { it.path }) { e ->
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = e.isDir) { browse(e.path) }.padding(vertical = DriveMetrics.gapWide),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(if (e.isDir) e.name + "/" else e.name)
                        if (!e.isDir) Text(UsbVolumeModel.human(e.size), style = MaterialTheme.typography.bodySmall)
                    }
                    if (!e.isDir) TextButton(onClick = { copy(e, b.path) }) { Text(getString(R.string.usb_copy_to_phone)) }
                    TextButton(onClick = { delete(e, b.path) }) { Text(getString(R.string.usb_delete)) }
                }
            }
        }
    }

    private fun copy(e: RawUsbDrive.Entry, here: String) {
        val d = drive ?: return
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { d.copyToPhone(e.path, copyDir()) } }
                .onSuccess { browse(here, getString(R.string.usb_copied, it.absolutePath)) }
                .onFailure { ui = Ui.Failed(it.message ?: it.javaClass.simpleName) }
        }
    }

    private fun delete(e: RawUsbDrive.Entry, here: String) {
        val d = drive ?: return
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { d.delete(e.path) } }
                .onSuccess { browse(here) }
                .onFailure { ui = Ui.Failed(it.message ?: it.javaClass.simpleName) }
        }
    }

    companion object {
        /** MainActivity opens Explorer on this path (the drive just plugged in). */
        const val EXTRA_OPEN_PATH = "com.diegonmarcos.clouddrive.extra.OPEN_PATH"
        const val MOUNT_WAIT_MS = 6000L
        private const val POLL_MS = 500L

        fun intent(ctx: Context): Intent = Intent(ctx, UsbDriveActivity::class.java)

        /** The Volumes card's "Open" for a drive Android did not mount. */
        fun open(ctx: Context) { runCatching { ctx.startActivity(intent(ctx)) } }

        @Suppress("DEPRECATION")
        fun deviceOf(i: Intent): UsbDevice? =
            if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java) else i.getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }
}
