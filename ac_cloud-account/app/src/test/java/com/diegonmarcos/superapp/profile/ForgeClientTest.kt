package com.diegonmarcos.superapp.profile

import android.app.Application
import com.diegonmarcos.superapp.profile.ForgeClient.Auth
import com.diegonmarcos.superapp.profile.ForgeClient.Forge
import com.diegonmarcos.superapp.profile.ForgeClient.Result
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Cloud Account redesign 5.3: ForgeClient against a fake forge. Each forge answers ONLY its own
 * declared auth header (GitHub `Bearer`, Gitea `token`), so a client sending the wrong scheme is
 * refused with 401 — the guard that a GitHub header can never reach Gitea. The stale-sha path
 * (409/422) refetches once and retries once. (Robolectric: org.json is the real one there.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ForgeClientTest {

    private val token = "test-credential-123"
    private val github = Forge("github", "https://gh.test", "owner/vault", Auth.BEARER)
    private val gitea = Forge("gitea", "https://gitea.test/api/v1", "owner/vault", Auth.TOKEN)

    /** A contents API: files by path (text, sha); accepts only [scheme] + token; [staleOnce] answers the first PUT 409. */
    private class FakeForge(val scheme: String, val token: String, var staleOnce: Boolean = false, val alwaysStale: Boolean = false) : ForgeClient.Http {
        val files = HashMap<String, Pair<String, String>>()
        val requests = mutableListOf<Triple<String, String, Map<String, String>>>()
        private var n = 0
        override fun call(method: String, url: String, headers: Map<String, String>, body: String?): Pair<Int, String> {
            requests += Triple(method, url, headers)
            if (headers["Authorization"] != "$scheme $token") return 401 to """{"message":"Bad credentials"}"""
            val path = url.substringAfter("/contents/").substringBefore("?")
            return when (method) {
                "GET" -> files[path]?.let { (t, sha) ->
                    200 to JSONObject().put("sha", sha).put("content", java.util.Base64.getMimeEncoder().encodeToString(t.toByteArray())).toString()
                } ?: (404 to """{"message":"Not Found"}""")
                "PUT", "POST" -> {
                    val b = JSONObject(body!!)
                    val cur = files[path]?.second
                    if (alwaysStale || staleOnce || b.optString("sha").ifBlank { null } != cur) { staleOnce = false; return 409 to """{"message":"sha mismatch"}""" }
                    val sha = "sha${++n}"
                    files[path] = String(java.util.Base64.getDecoder().decode(b.getString("content"))) to sha
                    201 to JSONObject().put("content", JSONObject().put("sha", sha)).toString()
                }
                else -> 405 to "{}"
            }
        }
    }

    @Test fun github_sends_bearer_and_round_trips() {
        val f = FakeForge("Bearer", token)
        val c = ForgeClient(github, token, f)
        val put = c.put("Shared/configs/devices/galaxy.json", "{\"a\":1}\n", null, "m", "main")
        assertTrue(put is Result.Ok)
        val got = c.get("Shared/configs/devices/galaxy.json", "main") as Result.Ok
        assertEquals("{\"a\":1}\n", got.value.text)
        assertEquals((put as Result.Ok).value, got.value.sha)
        assertTrue(f.requests.all { it.second.startsWith("https://gh.test/repos/owner/vault/contents/") })
    }

    @Test fun gitea_sends_token_scheme_and_posts_a_new_file() {
        val f = FakeForge("token", token)
        val c = ForgeClient(gitea, token, f)
        assertTrue(c.put("Shared/configs/devices/galaxy.json", "x", null, "m", "main") is Result.Ok)
        assertEquals("POST", f.requests.last().first)
        assertEquals("token $token", f.requests.last().third["Authorization"])
        assertTrue(f.requests.last().second.startsWith("https://gitea.test/api/v1/repos/owner/vault/contents/"))
    }

    /** GUARD: the GitHub header against the Gitea forge is refused (and the reverse). */
    @Test fun wrong_scheme_is_refused() {
        val giteaRemote = FakeForge("token", token)
        val r = ForgeClient(github.copy(api = "https://gitea.test/api/v1"), token, giteaRemote).put("p", "x", null, "m", "main")
        assertTrue(r is Result.Failed && r.status == 401)
        val githubRemote = FakeForge("Bearer", token)
        val r2 = ForgeClient(gitea, token, githubRemote).get("p", "main")
        assertTrue(r2 is Result.Failed && r2.status == 401)
    }

    @Test fun stale_sha_refetches_and_retries_once() {
        val f = FakeForge("Bearer", token)
        f.files["p"] = "old" to "sha-server"
        val r = ForgeClient(github, token, f).put("p", "new", "sha-stale", "m", "main")
        assertTrue("retry with the refetched sha commits", r is Result.Ok)
        assertEquals("new", f.files["p"]!!.first)
        assertEquals(listOf("PUT", "GET", "PUT"), f.requests.map { it.first })
    }

    @Test fun stale_twice_fails_named_after_one_retry() {
        val f = FakeForge("Bearer", token, alwaysStale = true)
        f.files["p"] = "old" to "s1"
        val r = ForgeClient(github, token, f).put("p", "new", "s1", "m", "main")
        assertTrue(r is Result.Failed && r.reason.contains("changed on the server"))
        assertEquals(3, f.requests.size)
    }

    @Test fun token_never_in_a_url_or_a_reason() {
        val f = FakeForge("Bearer", "another-token")
        val r = ForgeClient(github, token, f).put("p", "x", null, "m", "main")
        assertTrue(r is Result.Failed)
        assertFalse((r as Result.Failed).reason.contains(token))
        assertTrue(f.requests.none { it.second.contains(token) })
    }

    @Test fun no_repo_and_no_token_are_refused_without_a_request() {
        val f = FakeForge("token", token)
        assertTrue(ForgeClient(gitea.copy(repo = null), token, f).get("p", "main") is Result.Failed)
        assertTrue(ForgeClient(gitea, "", f).put("p", "x", null, "m", "main") is Result.Failed)
        assertTrue(f.requests.isEmpty())
    }

    @Test fun decl_parses_null_repo_and_schemes() {
        val d = ForgeClient.Decl.parse(JSONObject("""{"forges":[
            {"id":"github","api":"https://api.github.com","repo":"o/v","auth":"bearer"},
            {"id":"gitea","api":"https://git.test/api/v1","repo":null,"auth":"token"}],
            "vault":{"branch":"main","devices_dir":"Shared/configs/devices"}}"""))
        assertEquals(Auth.BEARER, d.forge("github")!!.auth)
        assertEquals(Auth.TOKEN, d.forge("gitea")!!.auth)
        assertEquals(null, d.forge("gitea")!!.repo)
        assertEquals("github", d.firstUsable()!!.id)
        assertEquals("Shared/configs/devices/galaxy.json", d.devicePath("galaxy"))
    }

    /** Over 1 MB the contents API omits content: the raw route must be taken, the sha kept. */
    private fun bigFile(scheme: String, calls: MutableList<Pair<String, Map<String, String>>>) = ForgeClient.Http { _, url, h, _ ->
        calls += url to h
        val raw = (url.contains("/raw/")) || h["Accept"] == "application/vnd.github.raw+json"
        if (h["Authorization"] != "$scheme $token") 401 to "{}"
        else if (raw) 200 to "{\"big\":true}"
        else 200 to """{"sha":"sha-big","size":2169130,"content":"","encoding":"none"}"""
    }

    @Test fun large_file_falls_back_to_raw_on_github() {
        val calls = mutableListOf<Pair<String, Map<String, String>>>()
        val r = ForgeClient(github, token, bigFile("Bearer", calls)).get("Shared/configs/profile-secrets.json", "main") as Result.Ok
        assertEquals("{\"big\":true}", r.value.text)
        assertEquals("sha-big", r.value.sha)
        assertEquals(2, calls.size)
        assertEquals("application/vnd.github.raw+json", calls[1].second["Accept"])
    }

    @Test fun large_file_falls_back_to_raw_on_gitea() {
        val calls = mutableListOf<Pair<String, Map<String, String>>>()
        val r = ForgeClient(gitea, token, bigFile("token", calls)).get("Shared/configs/profile-secrets.json", "main") as Result.Ok
        assertEquals("{\"big\":true}", r.value.text)
        assertEquals("sha-big", r.value.sha)
        assertTrue(calls[1].first.startsWith("https://gitea.test/api/v1/repos/owner/vault/raw/Shared/configs/profile-secrets.json?ref=main"))
    }

    @Test fun put_dry_shape_is_the_same_body_on_both_bases() {
        val a = ForgeClient(github, "-").putShape("Shared/configs/devices/galaxy.json", "s", "m", "main")
        val b = ForgeClient(gitea, "-").putShape("Shared/configs/devices/galaxy.json", "s", "m", "main")
        assertEquals(a.getJSONArray("body_keys").toString(), b.getJSONArray("body_keys").toString())
        assertTrue(a.getString("url").startsWith("https://gh.test/"))
        assertTrue(b.getString("url").startsWith("https://gitea.test/api/v1/"))
    }
}
