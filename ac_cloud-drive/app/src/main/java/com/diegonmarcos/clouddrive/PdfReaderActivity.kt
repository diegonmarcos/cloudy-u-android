package com.diegonmarcos.clouddrive

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.diegonmarcos.clouddrive.ui.DriveTheme
import com.diegonmarcos.cloudlib.pdf.PdfReaderActivityBase
import java.io.File

/**
 * The PDF reader screen (#577), now the shared libs:pdf reader (#1949: PdfReaderActivityBase, PdfReaderView, PdfEngine; the same
 * screen Cloud Notes opens) wearing Drive's theme, its own FileProvider and prefs, and the four PDF to text conversions (#579).
 * Every reader control (continuous and paged scrolling, pinch zoom, go to page, search with highlights, outline, night mode, share,
 * print, Edit) lives in the lib; this class adds only what is Drive's.
 */
internal class PdfReaderActivity : PdfReaderActivityBase() {

    @Composable
    override fun ReaderTheme(content: @Composable () -> Unit) { DriveTheme { content() } }

    override fun Modifier.screenInsets(): Modifier = this.systemBarsPadding().displayCutoutPadding()

    override val fileAuthority: String get() = "$packageName.files"
    override val prefsName: String get() = PREFS
    override val conversionTargets: List<String> get() = PdfConvert.TARGETS

    // #579 the ONE conversion path, shared with the Files tab's row action.
    override fun runConversion(file: File, target: String): Result<File> = PdfConversion.convertPdf(applicationContext, file, target)

    companion object {
        private const val PREFS = "cloud-drive-pdf-reader"

        /** The intent the Files tab uses: an explicit launch of this reader on a path it listed. */
        fun intent(context: Context, path: String): Intent =
            Intent(context, PdfReaderActivity::class.java)
                .setDataAndType(Uri.fromFile(File(path)), "application/pdf")
    }
}
