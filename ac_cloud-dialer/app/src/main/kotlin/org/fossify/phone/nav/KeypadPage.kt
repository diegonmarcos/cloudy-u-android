package org.fossify.phone.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import org.fossify.phone.helpers.rememberFleetDataset
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.themebg.ThemeColors
import com.diegonmarcos.superapp.uikit.KitStoreCaption
import com.diegonmarcos.superapp.uikit.KitStoreName
import com.diegonmarcos.superapp.uikit.KitStoreRow
import com.diegonmarcos.superapp.uikit.KitStoreRowLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** #1528 the Keypad bottom tab, inside the shell (the bars stay): declared keys with letters, T9 contact results, call through the provider chooser. */
object KeypadPageSpec {
    const val TAG = "dialer_keypad"
    const val TAG_RESULT = "dialer_keypad_result:"
    /** The typed number and the key digits are the ui-kit title size times this; the letters are the caption size. */
    const val DIGIT_SCALE = 2f
}

/** The keypad declaration is the runtime dataset `keypad` (#1547); blank until Cloud Store has served it, then the page. */
@Composable
fun KeypadPage(c: ThemeColors, onCall: (String) -> Unit, onFullDialpad: (String) -> Unit) {
    val spec = rememberFleetDataset(KeypadSpec.DATASET) { KeypadSpec.parse(it) } ?: return
    KeypadPageBody(spec, c, onCall, onFullDialpad)
}

@Composable
private fun KeypadPageBody(spec: KeypadSpec, c: ThemeColors, onCall: (String) -> Unit, onFullDialpad: (String) -> Unit) {
    val ctx = LocalContext.current
    var number by rememberSaveable { mutableStateOf("") }
    var contacts by remember { mutableStateOf<List<T9Contact>>(emptyList()) }
    LaunchedEffect(Unit) { contacts = withContext(Dispatchers.IO) { runCatching { ContactCandidates.load(ctx) }.getOrDefault(emptyList()) } }
    val hits = remember(number, contacts) { T9.search(number, contacts, spec) }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 12.dp).testTag(KeypadPageSpec.TAG),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
    ) {
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(hits, key = { it.contact.name + "|" + it.contact.number }) { h ->
                KitStoreRowLine(Modifier.testTag(KeypadPageSpec.TAG_RESULT + h.contact.number), onClick = { onCall(h.contact.number) }) {
                    KitStoreName(h.contact.name, Modifier.weight(1f), color = c.ink)
                    KitStoreCaption(h.contact.number, color = c.inkDim)
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(number, Modifier.weight(1f), color = c.ink, fontSize = KitStoreRow.title * KeypadPageSpec.DIGIT_SCALE, maxLines = 1, overflow = TextOverflow.StartEllipsis)
            if (number.isNotEmpty()) Icon(
                Icons.AutoMirrored.Filled.Backspace, contentDescription = "Delete", tint = c.inkDim,
                modifier = Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button) { number = number.dropLast(1) }.padding(8.dp),
            )
        }
        spec.rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { k ->
                    Box(
                        Modifier.size(60.dp).clip(CircleShape).background(c.tabStrip).clickable(role = Role.Button) { number += k.digit }
                            .semantics { contentDescription = k.digit + k.letters },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(k.digit, color = c.ink, fontSize = KitStoreRow.title * KeypadPageSpec.DIGIT_SCALE)
                            if (k.letters.isNotEmpty()) KitStoreCaption(k.letters, color = c.inkDim)
                        }
                    }
                }
            }
        }
        Box(
            Modifier.size(60.dp).clip(CircleShape).background(c.accent)
                .clickable(enabled = number.isNotEmpty(), role = Role.Button) { onCall(number) }.semantics { contentDescription = "Call" },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Call, contentDescription = null, tint = c.top) }
        TextButton(onClick = { onFullDialpad(number) }) { Text("Full dialpad (speed dial)", fontSize = KitStoreRow.body) }
    }
}
