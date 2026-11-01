package com.diegonmarcos.cloudcalc.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.KeyboardVoice
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CurrencyExchange
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The icon VOCABULARY a build.json `icon` may name. test/test-calc-shell.sh holds every declared
 * icon to a branch here, so a misspelt name fails the build instead of drawing [fallback].
 */
object IconCatalog {
    val fallback: ImageVector = Icons.Filled.Apps

    fun vector(name: String): ImageVector = when (name) {
        "calculate" -> Icons.Filled.Calculate
        "swap" -> Icons.Filled.SwapHoriz
        "chart" -> Icons.Filled.ShowChart
        "tools" -> Icons.Filled.Build
        "history" -> Icons.Filled.History
        "clock" -> Icons.Filled.AccessTime
        "speed" -> Icons.Filled.Speed
        "psychology" -> Icons.Filled.Psychology
        "mic" -> Icons.Filled.Mic
        "camera" -> Icons.Filled.PhotoCamera
        "ask" -> Icons.Filled.QuestionAnswer
        "settings" -> Icons.Filled.Settings
        "security" -> Icons.Filled.Security
        "info" -> Icons.Filled.Info
        "science" -> Icons.Filled.Science
        "memory" -> Icons.Filled.Memory
        "functions" -> Icons.Filled.Functions
        "bolt" -> Icons.Filled.Bolt
        "straighten" -> Icons.Filled.Straighten
        "currency" -> Icons.Filled.CurrencyExchange
        "network" -> Icons.Filled.NetworkCheck
        "event" -> Icons.Filled.Event
        "payments" -> Icons.Filled.Payments
        "graphic_eq" -> Icons.Filled.GraphicEq
        "table_chart" -> Icons.Filled.TableChart
        "auto_awesome" -> Icons.Filled.AutoAwesome
        "build" -> Icons.Filled.Build
        "flashlight_on" -> Icons.Filled.FlashlightOn
        "keyboard_voice" -> Icons.Filled.KeyboardVoice
        "sensors" -> Icons.Filled.Sensors
        "timeline" -> Icons.Filled.Timeline
        "directions_walk" -> Icons.Filled.DirectionsWalk
        "explore" -> Icons.Filled.Explore
        "bedtime" -> Icons.Filled.Bedtime
        "apps" -> Icons.Filled.Apps
        "bluetooth" -> Icons.Filled.Bluetooth
        "bluetooth_searching" -> Icons.Filled.BluetoothSearching
        "nfc" -> Icons.Filled.Nfc
        "wifi" -> Icons.Filled.Wifi
        "edit" -> Icons.Filled.Edit
        "restaurant" -> Icons.Filled.Restaurant
        else -> fallback
    }
}
