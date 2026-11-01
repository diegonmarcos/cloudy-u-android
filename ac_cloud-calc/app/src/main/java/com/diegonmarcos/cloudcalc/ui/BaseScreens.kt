@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.diegonmarcos.cloudcalc.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import com.diegonmarcos.cloudcalc.Declarations
import com.diegonmarcos.cloudcalc.tools.Bases

object BaseTags {
    const val SCREEN = "calc_base"
    const val TWOS = "calc_base_twos"
    const val CUSTOM_BASE = "calc_base_custom_base"
    fun field(key: String) = "calc_base_field_$key"
    fun bits(n: Int) = "calc_base_bits_$n"
}

/**
 * #1409 Decimals ▸ Convert ▸ Base: binary, octal, decimal, hex and a custom base 2..36, every one editable. The field being
 * typed keeps the user's text; the others are the value re-printed. With two's complement on, the non-decimal fields show
 * and read the bit pattern of the chosen width. The maths is :tools Bases.
 */
@Composable
fun BaseConverterMode(@Suppress("UNUSED_PARAMETER") mode: Declarations.Mode) {
    var value by remember { mutableStateOf(Bases.parse("255", 10)!!) }
    var editing by remember { mutableStateOf<Pair<String, String>?>(null) }
    var customText by rememberSaveable { mutableStateOf("36") }
    var twos by rememberSaveable { mutableStateOf(false) }
    var bits by rememberSaveable { mutableStateOf(32) }
    val custom = customText.toIntOrNull()?.takeIf { it in Bases.MIN..Bases.MAX }
    val fields = listOfNotNull("bin" to 2, "oct" to 8, "dec" to 10, "hex" to 16, custom?.let { "custom" to it })
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(CalcMetrics.gutter).testTag(BaseTags.SCREEN),
        verticalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
        fields.forEach { (key, base) ->
            val pattern = twos && base != 10
            val typed = editing?.takeIf { it.first == key }?.second
            val shown = typed ?: if (pattern && value.isInteger) Bases.twos(value, base, bits) ?: "" else Bases.format(value, base)
            val bad = typed != null && read(typed, base, pattern, bits) == null
            OutlinedTextField(
                value = shown,
                onValueChange = { t -> editing = key to t; read(t, base, pattern, bits)?.let { value = it } },
                label = { Text(name(key, base) + if (pattern) " · $bits-bit two's complement" else "") },
                isError = bad,
                supportingText = when {
                    bad -> { { Text("Not a base-$base number") } }
                    typed == null && pattern && value.isInteger && shown.isEmpty() -> { { Text("Out of the $bits-bit range") } }
                    else -> null
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(keyboardType = if (base <= 10) KeyboardType.Decimal else KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth().testTag(BaseTags.field(key)),
            )
        }
        OutlinedTextField(
            value = customText, onValueChange = { customText = it.filter(Char::isDigit).take(2); editing = null },
            label = { Text("Custom base (2–36)") }, isError = custom == null, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().testTag(BaseTags.CUSTOM_BASE),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
            Switch(checked = twos, onCheckedChange = { twos = it; editing = null }, modifier = Modifier.testTag(BaseTags.TWOS))
            Text("Two's complement")
        }
        if (twos) Row(horizontalArrangement = Arrangement.spacedBy(CalcMetrics.small)) {
            Bases.BIT_WIDTHS.forEach { n ->
                FilterChip(selected = bits == n, onClick = { bits = n; editing = null }, label = { Text("$n-bit") },
                    modifier = Modifier.testTag(BaseTags.bits(n)))
            }
        }
    }
}

private fun name(key: String, base: Int) = when (key) {
    "bin" -> "Binary (2)"; "oct" -> "Octal (8)"; "dec" -> "Decimal (10)"; "hex" -> "Hexadecimal (16)"; else -> "Base $base"
}

/** A field's text: the bit pattern when [pattern] (a fraction falls back to the plain reading), else the signed number. */
private fun read(t: String, base: Int, pattern: Boolean, bits: Int): Bases.Value? =
    (if (pattern) Bases.fromTwos(t, base, bits) else null) ?: Bases.parse(t, base)
