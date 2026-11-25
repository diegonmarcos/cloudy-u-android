package cld.camera.scanner

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.diegonmarcos.cloudlib.scanner.PageOcr
import com.diegonmarcos.cloudlib.scanner.MlKitPageOcr
import com.diegonmarcos.cloudlib.scanner.OcrResult
import com.diegonmarcos.cloudlib.scanner.ui.ScannerHost
import com.diegonmarcos.cloudlib.scanner.ui.ScannerUi

/**
 * THE Scanner page (the bottom nav's fifth slot; #1839, redesigned on libs:scanner by #1908). The whole scanner (capture with the
 * live edge overlay, the review strip, the per-page editor, the Tools hub, the export sheet) and every engine live in libs:scanner;
 * this activity is only the host: it owns the camera permission (asked through libs:perms, never a raw settings intent) and picks the
 * OCR engine: the fleet's image engine first (ScannerOcr), the bundled ML Kit text recognition when that engine is not installed.
 */
class ScannerActivity : AppCompatActivity(), ScannerHost {
    private val engine by lazy { ScannerOcr(this) }
    private val bundled by lazy { MlKitPageOcr() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ScannerUi.show(this, this)
    }

    override fun cameraGranted(): Boolean = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun requestCamera() {
        // Permissions are asked in one place: libs:perms (never a raw settings intent).
        runCatching { com.diegonmarcos.superapp.perms.PermsLink.open(this, "Scanner", listOf("camera")) }
    }

    override fun ocr(): PageOcr = PageOcr { page ->
        val r = engine.read(page)
        if (r.error == null) OcrResult(r.text, r.words, null) else bundled.read(page)
    }

    override fun close() { finish() }

    companion object {
        fun intent(ctx: Context) = Intent(ctx, ScannerActivity::class.java)
    }
}
