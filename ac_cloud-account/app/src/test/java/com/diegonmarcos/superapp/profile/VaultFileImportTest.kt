package com.diegonmarcos.superapp.profile

import android.app.Application
import android.net.Uri
import android.util.Base64
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.diegonmarcos.cloudlib.auth.VaultConnect
import com.diegonmarcos.cloudlib.auth.VaultFile
import com.diegonmarcos.superapp.account.BuildConfig
import com.diegonmarcos.superapp.account.R
import com.diegonmarcos.superapp.settings.ImportConfigsFragment
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream

/**
 * #585 — the Profile file import on the REAL bytes the owner picked.
 *
 * `vault-bundle-sops-encrypted.json` is cut from cloud-vault
 * `configs/profile-secrets.json` as committed: its `sops` block and two
 * sections verbatim, every value still `ENC[AES256_GCM,…]`. The old route
 * accepted it (valid JSON), overwrote the paste store and said "saved" —
 * this test drives [ImportConfigsFragment] itself, through the content
 * resolver, and asserts the loud refusal: the sentence on screen names what
 * was read, nothing reaches the store and nothing reaches the Fleet tab.
 * Every expected count is computed from the fixture by an independent walk.
 *
 * The happy paths use fixtures built here: the decrypted export lands in
 * [VaultConnect.Imported] and NOT in the store; the paste shape lands in the
 * store section by section and NOT in the Fleet tab.
 *
 * #773: the page is Compose now, so the box is typed, Save is clicked and the
 * verdict is read through the semantics tree (the page's TAG_* test tags) —
 * the same three gestures the View version made on its widgets.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VaultFileImportTest : KitPageHarness() {

    private val encryptedBytes: ByteArray =
        javaClass.getResourceAsStream("/vault-bundle-sops-encrypted.json")!!.readBytes()
    private val encrypted = JSONObject(encryptedBytes.decodeToString())
    private val known = VaultConnect.knownSchemaVersions
    private val blobSections = VaultFile.blobSections(
        String(Base64.decode(BuildConfig.UI_IMPORT_SCHEMA_B64, Base64.NO_WRAP)))

    /** What the store received, and what it held before: the seams' memory. */
    private val stored = mutableListOf<Triple<String, String, String>>()
    private val existingBlob = """{"auth":{"authelia_token":"t","authelia_email":"a@b.co"}}"""

    @After fun reset() {
        VaultConnect.Imported.last = null
        VaultConnect.Imported.bundle = null
    }

    // ── independent walks over the fixture ───────────────────────────────

    private fun encLeaves(v: Any?): Int = when (v) {
        is String -> if (v.startsWith("ENC[")) 1 else 0
        is JSONObject -> v.keys().asSequence().sumOf { encLeaves(v.opt(it)) }
        is JSONArray -> (0 until v.length()).sumOf { encLeaves(v.opt(it)) }
        else -> 0
    }

    private fun sectionsOf(o: JSONObject) =
        o.keys().asSequence().filter { !it.startsWith("_") && it != "schema_version" && it != "sops" }.toList()

    // ── the fragment, on a real activity, with the store swapped for a list ─

    private fun fragment(): ImportConfigsFragment {
        val f = ImportConfigsFragment()
        f.readBlob = { existingBlob }
        f.putSecret = { _, s, k, v -> stored += Triple(s, k, v) }
        f.clearBlob = { }
        return show(f)
    }

    private fun statusText(): String =
        compose.onNodeWithTag(ImportConfigsFragment.TAG_STATUS).fetchSemanticsNode()
            .config[SemanticsProperties.Text].joinToString("") { it.text }

    private fun ImportConfigsFragment.statusText() = this@VaultFileImportTest.statusText()

    private fun ImportConfigsFragment.type(text: String) =
        compose.onNodeWithTag(ImportConfigsFragment.TAG_INPUT).performTextReplacement(text)

    private fun ImportConfigsFragment.save() =
        compose.onNodeWithTag(ImportConfigsFragment.TAG_SAVE).performScrollTo().performClick()

    /** Registers [bytes] behind a content Uri on the resolver THE FRAGMENT will
     *  ask (Robolectric 4.16: registerInputStream is an instance method of the shadow). */
    private fun ImportConfigsFragment.serve(name: String, bytes: ByteArray): Uri {
        val uri = Uri.parse("content://test/$name")
        shadowOf(requireContext().contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))
        return uri
    }

    // ── the bug: the real encrypted file ─────────────────────────────────

    @Test fun `the real encrypted vault file classifies as Encrypted with every value counted`() {
        val v = VaultFile.classify(encryptedBytes.decodeToString(), known, blobSections)
        assertTrue("got $v", v is VaultFile.Verdict.Encrypted)
        v as VaultFile.Verdict.Encrypted
        val expected = encLeaves(JSONObject(encrypted.toString()).also { it.remove("sops") })
        assertTrue("fixture must still be ciphertext", expected > 0)
        assertEquals(expected, v.encValues)
        assertEquals(encrypted.getJSONObject("sops").getJSONArray("age").length(), v.recipients)
        assertEquals(sectionsOf(encrypted), v.sections)
    }

    @Test fun `picking the real encrypted file is refused loudly on pick and on Save and writes nothing`() {
        val f = fragment()
        f.loadFromUri(f.serve("profile-secrets.json", encryptedBytes))
        val expected = f.getString(R.string.import_encrypted,
            encLeaves(JSONObject(encrypted.toString()).also { it.remove("sops") }),
            encrypted.getJSONObject("sops").getJSONArray("age").length(),
            sectionsOf(encrypted).joinToString(", "))
        assertEquals(expected, f.statusText())
        f.save()
        assertEquals("Save must say the same thing, not 'saved'", expected, f.statusText())
        assertTrue("the store must not be written: $stored", stored.isEmpty())
        assertNull("the Fleet tab must not receive ciphertext", VaultConnect.Imported.bundle)
    }

    @Test fun `a zero-byte read is reported as zero bytes, not as an import`() {
        val f = fragment()
        f.loadFromUri(f.serve("empty.json", ByteArray(0)))
        assertEquals(f.getString(R.string.import_file_empty, "empty.json"), f.statusText())
        assertTrue(stored.isEmpty())
    }

    // ── the intended path: the decrypted export ──────────────────────────

    private fun decryptedFixture(): JSONObject = JSONObject()
        .put("schema_version", known.minOrNull()!!)
        .put("mesh", JSONObject().put("profiles", JSONObject().put("config-a", "[Interface]\nAddress = 10.0.0.9/32")))
        .put("mail", JSONObject().put("endpoints", JSONObject().put("jmap", "https://example.invalid")))
        .put("_generated", JSONObject().put("emitter", "test"))

    @Test fun `the decrypted export lands on the Fleet tab and never in the paste store`() {
        val f = fragment()
        val bundle = decryptedFixture()
        val sections = VaultConnect.sections(bundle)
        val values = sections.sumOf { it.rows.size }
        f.loadFromUri(f.serve("profile.json", bundle.toString().toByteArray()))
        assertEquals(f.getString(R.string.import_bundle_read, values, sections.size), f.statusText())
        assertNull("nothing lands before Save", VaultConnect.Imported.bundle)
        f.save()
        assertEquals(f.getString(R.string.import_bundle_landed, values, sections.size), f.statusText())
        assertNotNull(VaultConnect.Imported.bundle)
        assertEquals(sectionsOf(bundle), VaultConnect.Imported.last!!.map { it.id })
        assertTrue("the paste store must stay untouched: $stored", stored.isEmpty())
    }

    @Test fun `an export of a schema this build does not know is refused whole`() {
        val f = fragment()
        val unknown = known.maxOrNull()!! + 1
        f.type(decryptedFixture().put("schema_version", unknown).toString())
        f.save()
        assertEquals(f.getString(R.string.vault_connect_schema_unknown, unknown, known.sorted().joinToString(", ")), f.statusText())
        assertNull(VaultConnect.Imported.bundle)
    }

    // ── the paste shape: section by section, keeping what is there ───────

    @Test fun `a paste-shape blob is merged per section and the Fleet tab is untouched`() {
        val f = fragment()
        val section = blobSections.first { it != "auth" }
        f.type(JSONObject().put(section, JSONObject().put("k1", "v1").put("_doc", "x")).put("stranger", JSONObject()).toString())
        f.save()
        assertEquals(f.getString(R.string.import_blob_stored, 1, section, "stranger"), f.statusText())
        assertEquals(listOf(Triple(section, "k1", "v1")), stored)
        assertNull(VaultConnect.Imported.bundle)
    }

    @Test fun `JSON with no known section stores nothing and names what it had and what was expected`() {
        val f = fragment()
        f.type("""{"hello": 1, "world": {}}""")
        f.save()
        assertEquals(f.getString(R.string.import_unrecognised, "hello, world", blobSections.joinToString(", ")), f.statusText())
        assertTrue(stored.isEmpty())
    }

    @Test fun `text that is not JSON is refused with its size and its head`() {
        val f = fragment()
        f.type("not json at all")
        f.save()
        // org.json's own reason text is not this app's to pin: the size and the head are.
        val prefix = f.getString(R.string.import_invalid, 15, "not json at all", "").substringBefore("): ")
        assertTrue(f.statusText(), f.statusText().startsWith(prefix))
        assertTrue(stored.isEmpty())
    }
}
