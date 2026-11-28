package app.sterna.core.data.mail

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1952: app/compose-stability.conf tells Compose that Email, EmailAddress, EmailBodyPart and InboxRow never change after construction, so a
 * row whose Email is an equal copy is skipped. That is only true while (a) none of those classes (nor the two small ones inside Email) has a
 * `var` or a mutable collection field, and (b) no code edits one of their lists or maps in place. This test reads the source for both.
 * Mutation: workflow mail-cache-1952b-mutation, leg email-gains-var.
 */
class MailModelImmutability1952Test {
    private val mutableToken = Regex("""\bvar\b|\bMutable(List|Map|Set|Collection)\b|\b(ArrayList|HashMap|HashSet|LinkedHashMap|LinkedHashSet|LinkedList)\b|java\.util\.""")
    private val modelFields = "from|to|cc|bcc|replyTo|messageId|inReplyTo|references|htmlBody|textBody|attachments|headers|keywords|mailboxIds|bodyValues"
    private val inPlace = listOf(
        Regex("""\bas\s+(kotlin\.collections\.)?Mutable(List|Map|Set|Collection)\b"""),
        Regex("""\bas\s+(java\.util\.)?(ArrayList|HashMap|LinkedHashMap|LinkedList)\b"""),
        Regex("""\.($modelFields)\s*\.(add|addAll|remove|removeAll|removeAt|clear|sort|sortBy|sortWith|reverse|shuffle|set|put|putAll|retainAll)\("""),
        Regex("""\.($modelFields)\[[^\]]*]\s*=[^=]"""),
    )

    private fun root(path: String): File =
        generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, path) }.first { it.exists() }

    private fun code(file: File): List<String> =
        file.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }

    @Test fun `the mail model has no var and no mutable collection field`() {
        val model = code(root("core/jmap/src/main/kotlin/app/sterna/core/jmap/model/Email.kt"))
        assertTrue("Email.kt still declares Email, EmailAddress, EmailBodyPart", model.any { it.startsWith("data class Email(") } && model.any { it.startsWith("data class EmailAddress(") } && model.any { it.startsWith("data class EmailBodyPart(") })
        assertEquals("Email.kt must hold only read-only vals (a `var` or a mutable collection makes the stability declaration a lie):", emptyList<String>(), model.filter { mutableToken.containsMatchIn(it) })
        val repo = code(root("core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt"))
        val start = repo.indexOfFirst { it.startsWith("data class InboxRow(") }
        assertTrue("InboxRow is declared", start >= 0)
        val body = repo.drop(start).takeWhile { it != ")" }
        assertTrue("InboxRow's fields were read", body.size >= 4)
        assertEquals("InboxRow must hold only read-only vals:", emptyList<String>(), body.filter { mutableToken.containsMatchIn(it) })
    }

    @Test fun `no code edits a mail model list or map in place`() {
        val hits = mutableListOf<String>()
        for (dir in listOf("core/jmap/src/main", "core/data/src/main", "app/src/main")) {
            root(dir).walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
                code(f).forEachIndexed { i, line ->
                    if (inPlace.any { it.containsMatchIn(line) }) hits += "${f.name}:${i + 1}: $line"
                }
            }
        }
        assertEquals("these lines edit a mail model collection in place (stale rows under a stable declaration):", emptyList<String>(), hits)
    }
}
