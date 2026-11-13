package com.diegonmarcos.cloudaccount

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1693 MOVED from Cloud Mail (SecretFieldRevealWiringTest), adapted to where the secret field lives now: SOURCE LINT over libs:account's
 * AppCredentialsPanel.kt. The reveal state must be keyed on emptiness, the mask must depend only on the secret flag and the reveal, the
 * trailing icon must ask revealIconOffered, and nothing may dim or persist the reveal or print the value a second time.
 */
class CredentialRevealWiringTest {
    private val file: File by lazy {
        val rel = "ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/profile/AppCredentialsPanel.kt"
        generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, rel) }.firstOrNull { it.isFile } ?: error("cannot locate $rel from ${File("").absolutePath}")
    }

    /** The body of CredentialRow, trimmed, comment-only lines dropped so no rule is satisfied by prose. */
    private fun body(): List<String> {
        val lines = file.readLines().map { it.trim() }.filterNot { it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
        val at = lines.indexOfFirst { it.startsWith("private fun CredentialRow(") }
        check(at >= 0) { "no CredentialRow: the secret field moved, teach this lint where it went instead of leaving it green" }
        var depth = 0; var opened = false
        val out = mutableListOf<String>()
        for (l in lines.drop(at)) {
            depth += l.count { it == '{' } - l.count { it == '}' }
            if (l.contains("{")) opened = true
            out += l
            if (opened && depth <= 0) return out
        }
        error("CredentialRow is never closed")
    }

    @Test fun `the reveal state is remembered under a key that falls on both sides of empty`() =
        assertEquals(listOf("var revealRequested by remember(draft.isEmpty()) { mutableStateOf(false) }"), body().filter { "remember" in it && "revealRequested" in it })

    @Test fun `the mask is on for a secret unless the reveal was asked for`() {
        assertEquals(listOf("val masked = e.secret && !revealRequested"), body().filter { it.startsWith("val masked") })
        assertEquals(listOf("visualTransformation = if (masked) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,"),
            body().filter { it.startsWith("visualTransformation") })
    }

    @Test fun `the trailing icon asks revealIconOffered with the secret flag and the value, and is absent when it says no`() {
        val b = body()
        assertEquals(1, b.count { it.startsWith("trailingIcon") })
        assertEquals("trailingIcon = if (revealIconOffered(e.secret, draft)) {", b.first { it.startsWith("trailingIcon") })
        assertTrue("the icon must be ABSENT (null), not greyed out", b.contains("null"))
    }

    @Test fun `nothing dims or persists the reveal instead of dropping it`() =
        assertEquals(emptyList<String>(), body().filter { l -> listOf("rememberSaveable", "alpha", "enabled =").any { it in l } })
}
