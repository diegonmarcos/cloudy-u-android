package com.diegonmarcos.cloudkeyboard

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.Modifier
import com.diegonmarcos.cloudwriter.WriterSurface
import com.diegonmarcos.superapp.bottomnav.FleetChrome

/**
 * #1707 the Writer entry points the retired standalone Cloud Writer had, now Cloud Keyboard's: text shared in (SEND text/plain),
 * an image shared in (SEND image/*, read by OCR into the Enhancer), text selected in another app (PROCESS_TEXT), and the deep link
 * any fleet app uses to open the Writer (an explicit start, optionally with EXTRA_SECTION / EXTRA_PAGE). It hosts libs:writer-ui's
 * [WriterSurface] exactly as the old MainActivity did, so a shared text opens in the Writer editor.
 */
class WriterShareActivity : AppCompatActivity() {

    private var surface: WriterSurface? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FleetChrome.apply(this)
        val s = WriterSurface(this)
        surface = s
        runCatching { s.adoptIntent(intent) }
        setContent { s.Content(Modifier.statusBarsPadding()) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        runCatching { surface?.onNewIntent(intent) }
    }

    companion object {
        const val EXTRA_SECTION = WriterSurface.EXTRA_SECTION
        const val EXTRA_PAGE = WriterSurface.EXTRA_PAGE
    }
}
