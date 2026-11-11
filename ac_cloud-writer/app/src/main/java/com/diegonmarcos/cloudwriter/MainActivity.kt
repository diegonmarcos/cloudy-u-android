package com.diegonmarcos.cloudwriter

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.Modifier
import com.diegonmarcos.superapp.bottomnav.FleetChrome

/**
 * Cloud Writer's launcher activity, as a THIN SHELL (#1652): the whole app (documents, editor,
 * the four tool tabs, Tools, Configs, Listen) is libs:writer-ui's [WriterSurface], drawn here
 * through [WriterSurface.Content]. Cloud-keyboard's Writer tab hosts the same class, so the app
 * and the keyboard run one body of code. The manifest, ListenService, the OCR FileProvider and the
 * debug-API provider stay declared once, in this module's AndroidManifest.xml.
 *
 * android:configChanges keeps this activity alive across rotation and the uiMode flip, so the
 * surface's plain mutableStateOf state needs no ViewModel (as before the extraction).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var surface: WriterSurface

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FleetChrome.apply(this)
        surface = WriterSurface(this)
        surface.adoptIntent(intent)
        setContent { surface.Content(Modifier.statusBarsPadding()) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        surface.onNewIntent(intent)
    }

    companion object {
        /** The ui.sections id a tool page's bottom nav asks the main screen to open (kept for the emulator walk and deep links). */
        const val EXTRA_SECTION = WriterSurface.EXTRA_SECTION
        const val EXTRA_PAGE = WriterSurface.EXTRA_PAGE
    }
}
