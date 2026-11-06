package org.fossify.phone.nav

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import com.diegonmarcos.superapp.themebg.ThemeColors
import com.diegonmarcos.superapp.uikit.KitStoreCaption
import com.diegonmarcos.superapp.uikit.KitStoreName
import com.diegonmarcos.superapp.uikit.KitStoreQuick
import com.diegonmarcos.superapp.uikit.KitStoreRow
import com.diegonmarcos.superapp.uikit.KitStoreRowLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.fossify.phone.helpers.isPackageInstalled

/** One VoIP app, declared in assets/voip_providers.json: [uri] with {number}, the [packages] to try in order. */
@Serializable
data class VoipProvider(val id: String, val label: String, val uri: String, val packages: List<String> = emptyList())

@Serializable
data class VoipSpec(
    @SerialName("number_label") val numberLabel: String = "",
    @SerialName("recent_label") val recentLabel: String = "",
    @SerialName("recent_limit") val recentLimit: Int = 8,
    val providers: List<VoipProvider>,
) {
    companion object {
        const val ASSET = "voip_providers.json"
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(raw: String): VoipSpec = json.decodeFromString(serializer(), raw)
        fun load(ctx: Context): VoipSpec = parse(ctx.assets.open(ASSET).bufferedReader().use { it.readText() })
    }
}

/** What to open: the link and the package that handles it (null = no declared package is installed, let the system choose). */
data class VoipTarget(val uri: String, val packageName: String?)

object Voip {
    const val TAG = "dialer_voip"
    const val TAG_NUMBER = "dialer_voip_number"
    const val TAG_OPEN = "dialer_voip_open:"
    const val TAG_RECENT = "dialer_voip_recent:"

    /** The provider's link for [number] (digits only) and the first installed package of the declared list; null without digits. */
    fun resolve(p: VoipProvider, number: String, isInstalled: (String) -> Boolean): VoipTarget? {
        val digits = T9.digits(number)
        if (digits.isEmpty()) return null
        return VoipTarget(p.uri.replace("{number}", digits), p.packages.firstOrNull(isInstalled))
    }

    fun intent(t: VoipTarget): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(t.uri)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        t.packageName?.let { setPackage(it) }
    }

    /** The most recently called distinct names, newest first. */
    fun recents(all: List<T9Contact>, limit: Int): List<T9Contact> =
        all.filter { it.lastMillis > 0 }.sortedByDescending { it.lastMillis }.distinctBy { T9.digits(it.number) }.take(limit)
}

/** #1528 the VoIP bottom tab: a number, the recent contacts, and one button per declared provider (WhatsApp Business now). */
@Composable
fun VoipPage(c: ThemeColors) {
    val ctx = LocalContext.current
    val spec = remember { VoipSpec.load(ctx) }
    var number by rememberSaveable { mutableStateOf("") }
    var recent by remember { mutableStateOf<List<T9Contact>>(emptyList()) }
    LaunchedEffect(Unit) {
        recent = withContext(Dispatchers.IO) { Voip.recents(runCatching { ContactCandidates.load(ctx) }.getOrDefault(emptyList()), spec.recentLimit) }
    }
    LazyColumn(Modifier.fillMaxSize().testTag(Voip.TAG)) {
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = KitStoreRow.padH, vertical = KitStoreRow.padV)) {
                OutlinedTextField(
                    number, { number = it }, Modifier.fillMaxWidth().testTag(Voip.TAG_NUMBER), label = { KitStoreCaption(spec.numberLabel) },
                    textStyle = TextStyle(fontSize = KitStoreRow.body, color = c.ink), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                )
            }
        }
        items(spec.providers, key = { it.id }) { p ->
            KitStoreRowLine {
                KitStoreQuick(p.label, KitStoreRow.BLUE_FILL, Modifier.testTag(Voip.TAG_OPEN + p.id)) {
                    val t = Voip.resolve(p, number) { isPackageInstalled(ctx, it) }
                    if (t != null) runCatching { ctx.startActivity(Voip.intent(t)) }.onFailure { Toast.makeText(ctx, p.label, Toast.LENGTH_SHORT).show() }
                }
            }
        }
        item { KitStoreRowLine { KitStoreCaption(spec.recentLabel.uppercase(), color = c.accent) } }
        items(recent, key = { it.name + "|" + it.number }) { r ->
            KitStoreRowLine(Modifier.testTag(Voip.TAG_RECENT + r.number), onClick = { number = r.number }) {
                KitStoreName(r.name, Modifier.weight(1f), color = c.ink)
                KitStoreCaption(r.number, color = c.inkDim)
            }
        }
        item { Spacer(Modifier.height(KitStoreRow.step(96))) }
    }
}
