package com.diegonmarcos.cloudkeyboard

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.diegonmarcos.cloudwriter.WriterSurface

/**
 * #1652 the Writer tab: cloud-writer's UI itself (libs:writer-ui), one named page at a time. The keyboard's top bar lists the pages
 * (KbTab.WRITER) and this draws the one picked, so the Writer tab IS cloud-writer, not a copy. One [WriterSurface] per activity, so the
 * boxes and the open document survive a move between sub-pages.
 */
object WriterHost {
    private var surface: WriterSurface? = null

    private tailrec fun Context.componentActivity(): ComponentActivity? =
        if (this is ComponentActivity) this else (this as? ContextWrapper)?.baseContext?.componentActivity()

    @Composable
    fun Page(id: String) {
        val activity = LocalContext.current.componentActivity() ?: return
        val s = surface?.takeIf { it.activity === activity } ?: WriterSurface(activity, onExit = { }).also { surface = it }
        s.Page(id)
    }
}
