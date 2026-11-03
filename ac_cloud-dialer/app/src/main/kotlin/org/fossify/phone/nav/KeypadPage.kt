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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.themebg.ThemeColors

/** #1415 the Keypad bottom tab, inside the shell (the bars stay): keys, call through the provider chooser, the full dialpad. */
object KeypadPageSpec {
    val KEYS: List<List<String>> = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("*", "0", "#"))
    const val TAG = "dialer_keypad"
}

@Composable
fun KeypadPage(c: ThemeColors, onCall: (String) -> Unit, onFullDialpad: (String) -> Unit) {
    var number by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp).testTag(KeypadPageSpec.TAG),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(number, Modifier.weight(1f), color = c.ink, fontSize = 30.sp, maxLines = 1, overflow = TextOverflow.StartEllipsis)
            if (number.isNotEmpty()) Icon(
                Icons.AutoMirrored.Filled.Backspace, contentDescription = "Delete", tint = c.inkDim,
                modifier = Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button) { number = number.dropLast(1) }.padding(8.dp),
            )
        }
        KeypadPageSpec.KEYS.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { k ->
                    Box(
                        Modifier.size(68.dp).clip(CircleShape).background(c.tabStrip).clickable(role = Role.Button) { number += k }
                            .semantics { contentDescription = k },
                        contentAlignment = Alignment.Center,
                    ) { Text(k, color = c.ink, fontSize = 26.sp) }
                }
            }
        }
        Box(
            Modifier.size(68.dp).clip(CircleShape).background(c.accent)
                .clickable(enabled = number.isNotEmpty(), role = Role.Button) { onCall(number) }.semantics { contentDescription = "Call" },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Call, contentDescription = null, tint = c.top) }
        TextButton(onClick = { onFullDialpad(number) }) { Text("Full dialpad (contact search, speed dial)") }
    }
}
