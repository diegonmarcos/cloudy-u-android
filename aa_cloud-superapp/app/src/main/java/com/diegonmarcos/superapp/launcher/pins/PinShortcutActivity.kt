package com.diegonmarcos.superapp.launcher.pins

import android.app.Activity
import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import java.io.ByteArrayOutputStream

/**
 * #1840 THE SUPERAPP LAUNCHER ACCEPTS PINNED SHORTCUTS. Android routes ShortcutManager.requestPinShortcut (what Cloud Browser's Add to Home
 * Screen, Chrome and every app call) to the DEFAULT home app's activity that answers android.content.pm.action.CONFIRM_PIN_SHORTCUT; with
 * none, the app is told "this launcher cannot pin shortcuts". This is that activity: no screen of its own (a translucent shell), it reads the
 * request with LauncherApps.getPinItemRequest, keeps the shortcut and its icon in [PinnedShortcutStore] (drawn on the home by [PinnedStrip],
 * removable there), then accept()s the request so the system registers the pin. A request that is not a shortcut, or that is no longer valid,
 * is ignored.
 */
class PinShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val result = runCatching { handle() }
        result.onFailure { Log.w(TAG, "pin request failed: ${it.javaClass.simpleName}: ${it.message}") }
        finish()
    }

    private fun handle() {
        val la = getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val req = la.getPinItemRequest(intent)
        if (req == null || !req.isValid || req.requestType != LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT) {
            Log.i(TAG, "pin request ignored (null, invalid or not a shortcut)")
            return
        }
        val info = req.shortcutInfo ?: return
        val label = PinnedShortcutStore.cleanLabel(info.shortLabel?.toString(), info.id)
        val key = PinnedShortcutStore.keyOf(info.`package`, info.id)
        val iconPath = la.getShortcutIconDrawable(info, resources.displayMetrics.densityDpi)?.let { d ->
            val size = (resources.displayMetrics.density * 96).toInt().coerceAtLeast(48)
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, size, size); d.draw(Canvas(bmp))
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            PinnedShortcutStore.saveIcon(this, key, out.toByteArray())
        }.orEmpty()
        val kept = PinnedShortcutStore.add(this, PinnedShortcutStore.Pin(info.id, info.`package`, label, PinnedShortcutStore.Kind.SYSTEM, "", iconPath, System.currentTimeMillis()))
        val accepted = kept && req.accept()
        Log.i(TAG, "pin ${if (accepted) "accepted" else "refused"}: pkg=${info.`package`} id=${info.id}")
        Toast.makeText(this, if (accepted) "Pinned “$label” on the home" else "Could not pin “$label”", Toast.LENGTH_SHORT).show()
    }

    companion object { private const val TAG = "SuperApp/Pins" }
}
