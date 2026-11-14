package com.diegonmarcos.cloudbrowser.imageview

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.diegonmarcos.cloudbrowser.MainActivity

/**
 * #1741 the fleet's "Convert to PDF" door (build.json::image_viewer.convert_pdf_action). A fleet app hands an image or HTML
 * file as the intent data; this opens it in the browser (MainActivity) flagged so that, once the page has loaded, the print
 * framework is raised with the A4 sheet and the person picks "Save as PDF". No UI of its own, nothing is rendered here (the
 * one PDF engine stays in Cloud Drive). Exported behind the signature permission: only a fleet app can start it.
 */
class ConvertPdfActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent?.data
        if (data != null && (data.scheme == "content" || data.scheme == "file")) {
            startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(data, intent.type ?: contentResolver.getType(data))
                    .setClass(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_CONVERT_PDF, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
            setResult(RESULT_OK)
        } else setResult(RESULT_CANCELED, Intent().putExtra("error", "a content:// or file:// uri is required as data"))
        finish()
    }
}
