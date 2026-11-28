package jp.xiaqui.artifact

import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class ReleaseProvidersTest {
    private val app = ApkInfo("jp.example.app", "Example", "1", 1, "AA")

    private fun source(key: String, priority: Int, code: Long = 1, digest: String? = "a".repeat(64),
        error: String = "", signer: String = "AA"): DistributionSource {
        val asset = ReleaseApk(priority.toLong() + 1, "app.apk", 10, digest, sourceKey = key)
        val release = StoreRelease(priority.toLong() + 10, "v$code", "2026", false, listOf(asset), sourceKey = key)
        // Forgejo repository identity is numeric and must remain independent from the mutable owner/name.
        return DistributionSource(key, if (key.startsWith("gh")) PROVIDER_GITHUB else PROVIDER_FORGEJO,
            if (key.startsWith("gh")) "https://github.com" else "https://forge.example/sub", priority.toLong() + 1,
            "owner/repo$priority", priority, history = listOf(release), inspected = mapOf(asset.identity() to app.copy(code = code, signer = signer)), error = error)
    }

    @Test fun normalizedServerRetainsSafeSubpathAndRejectsCredentialOrPlaintextUrls() {
        assertEquals("https://forge.example/sub", normalizeServer("https://FORGE.example/sub/"))
        listOf("http://forge.example", "https://user@forge.example", "https://forge.example/sub?token=x",
            "https://forge.example/a/../b", "https://forge.example:444")
            .forEach { value -> assertThrows(ImportFailure::class.java) { normalizeServer(value) } }
    }

    @Test fun maximumVersionWinsAndPriorityOnlyBreaksVerifiedEqualVersionTies() {
        val primaryOld = source("forge", 0, code = 1)
        val backupNew = source("gh", 1, code = 2, error = "temporary")
        assertEquals("gh", resolveSources(listOf(primaryOld, backupNew), false).selected!!.source.key)
        val same = source("gh", 1, code = 1)
        assertEquals("forge", resolveSources(listOf(primaryOld, same), false).selected!!.source.key)
        val restored = primaryOld.copy(error = "")
        assertEquals("forge", resolveSources(listOf(restored, same), false).selected!!.source.key)
    }

    @Test fun equalVersionUnknownOrDifferentBytesAndSignerMismatchStopAutomaticSelection() {
        assertTrue(resolveSources(listOf(source("forge",0,digest=null), source("gh",1)), false).conflict.isNotEmpty())
        assertTrue(resolveSources(listOf(source("forge",0), source("gh",1,digest="b".repeat(64))), false).conflict.isNotEmpty())
        assertTrue(resolveSources(listOf(source("forge",0), source("gh",1,signer="BB")), false).conflict.isNotEmpty())
    }

    @Test fun unapprovedSourceCannotAffectSelection() {
        val approved = source("gh", 1, code = 1)
        val unapproved = source("forge", 0, code = 9).copy(approved = false)
        assertEquals("gh", resolveSources(listOf(unapproved, approved), false).selected!!.source.key)
    }

    @Test fun sourceCodecKeepsProviderNamespaceHistoryAndAuthScope() {
        val forge = source("forge-key", 0).copy(authKey = "connection-key")
        val registration = Registration("o/r", true, "rev", sources = listOf(forge))
        val restored = CatalogCodec.decode(CatalogCodec.encode(registration))
        assertEquals(registration, restored)
        assertTrue(restored.sources.single().history.single().assets.single().identity().startsWith("forge-key:"))
    }

    private class Reply(url: URL, private val status: Int, private val body: String) : HttpURLConnection(url) {
        override fun getResponseCode() = status
        override fun getInputStream() = body.byteInputStream()
        override fun disconnect() {}
        override fun connect() {}
        override fun usingProxy() = false
    }

    @Test fun forgejoDiscoveryUsesOnlyConfiguredOriginTokenAndExactOwner() {
        val requests = mutableListOf<Reply>()
        val api = ForgejoDiscovery("https://forge.example/sub", "sugi", "secret-token") { url ->
            Reply(url, 200, """[{"id":1,"full_name":"sugi/app"},{"id":2,"full_name":"other/app"}]""").also(requests::add)
        }
        val page = api.page(1)
        assertEquals(listOf(RemoteRepo(1,"sugi/app")), page.repos)
        assertFalse(page.more)
        assertEquals("forge.example", requests.single().url.host)
        assertEquals("/sub/api/v1/user/repos", requests.single().url.path)
        assertEquals("token secret-token", requests.single().getRequestProperty("Authorization"))
        assertFalse(requests.single().instanceFollowRedirects)
    }

    @Test fun manualForgejoRepoStaysOnConfiguredServerAndOwner() {
        val requests = mutableListOf<Reply>()
        val api = ForgejoDiscovery("https://forge.example/sub", "sugi", "secret-token") { url ->
            Reply(url, 200, """{"id":9,"full_name":"sugi/private"}""").also(requests::add)
        }
        assertEquals(RemoteRepo(9,"sugi/private"), api.repository("https://forge.example/sub/sugi/private"))
        assertEquals("/sub/api/v1/repos/sugi/private", requests.single().url.path)
        assertEquals("token secret-token", requests.single().getRequestProperty("Authorization"))
        assertThrows(ImportFailure::class.java) { api.repository("other/private") }
        assertThrows(ImportFailure::class.java) { api.repository("https://evil.example/sugi/private") }
    }
}
