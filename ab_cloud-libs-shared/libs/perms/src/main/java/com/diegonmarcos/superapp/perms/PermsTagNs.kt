package com.diegonmarcos.superapp.perms

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.testTag

/**
 * #1546 the namespace of this library's test tags. Default "perms" (every host and test unchanged); a host page that draws
 * its own catalogue rows with the same "perms:*" tags (SuperApp Configs > Perms) provides another namespace around
 * [AppPermsSection], so both lists are on screen at once without two nodes answering one tag.
 */
val LocalPermsTagNs = staticCompositionLocalOf { "perms" }

fun permsTagIn(ns: String, tag: String): String = if (ns == "perms" || !tag.startsWith("perms")) tag else ns + tag.removePrefix("perms")

fun Modifier.permsTag(tag: String): Modifier = composed { testTag(permsTagIn(LocalPermsTagNs.current, tag)) }
