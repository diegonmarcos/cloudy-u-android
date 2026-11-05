package com.diegonmarcos.cloudaccount.hub

import android.util.Base64
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.diegonmarcos.cloudaccount.BuildConfig
import com.diegonmarcos.superapp.uikit.KitStoreCaption
import com.diegonmarcos.superapp.uikit.KitStoreName
import com.diegonmarcos.superapp.uikit.KitStoreRow
import com.diegonmarcos.superapp.uikit.KitStoreRowLine
import com.diegonmarcos.superapp.uikit.KitStoreDot
import org.json.JSONArray

/** One declared sample row: build.json::ui.sections[].pages[].sample.rows[] (title, caption, state ok | warn | bad). */
data class SampleRow(val title: String, val caption: String, val state: String)

/** Reads the sample rows of page [page] of [section] from the baked section declaration; nothing here names a row. */
object SampleRows {
    fun of(sectionsJson: String, section: String, page: String): List<SampleRow> {
        fun find(a: JSONArray?, id: String): org.json.JSONObject? {
            for (i in 0 until (a?.length() ?: 0)) {
                val o = a!!.optJSONObject(i) ?: continue
                if (o.optString("id") == id) return o
                find(o.optJSONArray("pages"), id)?.let { return it }
            }
            return null
        }
        val root = runCatching { JSONArray(sectionsJson) }.getOrNull()
        val rows = find(find(root, section)?.optJSONArray("pages"), page)?.optJSONObject("sample")?.optJSONArray("rows") ?: return emptyList()
        return (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.map { SampleRow(it.optString("title"), it.optString("caption"), it.optString("state")) }
    }
}

/** A declared page with sample data only (dense Store rows); used where no live content exists yet. */
@Composable
fun SamplePage(section: String, page: String) {
    val rows = remember(section, page) {
        SampleRows.of(String(Base64.decode(BuildConfig.UI_SECTIONS_B64, Base64.DEFAULT)), section, page)
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("account:sample:$page")) {
        rows.forEach { r ->
            val ink = when (r.state) { "ok" -> KitStoreRow.UP; "warn" -> KitStoreRow.UPDATE; else -> KitStoreRow.BAD }
            KitStoreRowLine {
                KitStoreDot(if (r.state == "ok") "✓" else if (r.state == "warn") "⚠" else "✗", ink)
                Column {
                    KitStoreName(r.title)
                    KitStoreCaption(r.caption)
                }
            }
        }
    }
}
