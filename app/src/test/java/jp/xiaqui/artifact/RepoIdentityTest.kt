package jp.xiaqui.artifact

import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class RepoIdentityTest {
    private val auth = object : ReleaseAuth {
        override fun tokenFor(repository: String): String? = null
        override fun commonToken(): String = "fixture"
    }
    private fun response(url: URL, status: Int, data: String = "", location: String? = null) = object : HttpURLConnection(url) {
        override fun getResponseCode() = status
        override fun getInputStream() = data.byteInputStream()
        override fun getHeaderField(name: String) = if (name == "Location") location else null
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
    }
    @Test fun followsOnlyPinnedApiMetadataRedirectAndResolvesCanonicalName() {
        val urls = mutableListOf<URL>()
        val api = RepoIdentityApi(auth) { url ->
            urls += url
            if (urls.size == 1) response(url,301,location="https://api.github.com/repositories/42")
            else response(url,200,"""{"id":42,"full_name":"xiaqui/release-store"}""")
        }
        assertEquals(RepoIdentity(42,"xiaqui/release-store"),api.resolve("xiaqui/artifact"))
        assertEquals("/repositories/42",urls.last().path)
        for (bad in listOf("https://evil.test/repos/a/b", "https://api.github.com/repos/a/b?token=x",
            "http://api.github.com/repos/a/b", "https://user@api.github.com/repos/a/b", "https://api.github.com/user")) {
            assertFalse(safeIdentityUrl(URL(bad)))
        }
    }
    @Test fun rejectsUnsafeRedirectBeforeRequestingIt() {
        var calls = 0
        val api = RepoIdentityApi(auth) { url -> calls++; response(url,302,location="https://evil.test/repositories/42") }
        assertThrows(ImportFailure::class.java) { api.resolve("old/repo") }
        assertEquals(1,calls)
    }
    @Test fun statusFailuresNeverBecomeIdentityAndMessagesContainNoCredentials() {
        for (status in listOf(401,403,404,429)) {
            var calls = 0
            val api = RepoIdentityApi(auth) { url -> calls++; response(url,status) }
            val error = assertThrows(ImportFailure::class.java) { api.resolve("old/repo") }
            assertTrue(error.message!!.contains("HTTP $status"))
            assertFalse(error.message!!.contains("fixture"))
            assertEquals(2,calls)
        }
        val api = RepoIdentityApi(auth) { throw java.net.UnknownHostException() }
        assertThrows(java.net.UnknownHostException::class.java) { api.resolve("old/repo") }
    }
    @Test fun knownIdUsesStableEndpointAndDifferentOrChangingIdentityIsRejected() {
        val api = RepoIdentityApi(auth) { url ->
            assertEquals("/repositories/42",url.path)
            response(url,200,"""{"id":42,"full_name":"new/repo"}""")
        }
        val id = api.resolve("old/repo",42)
        val old = Registration("old/repo",true,"revision",githubId=42)
        assertEquals("new/repo",verifiedRename(old,id,id))
        assertThrows(ImportFailure::class.java) { verifiedRename(old,id,id.copy(id=43)) }
        assertThrows(ImportFailure::class.java) { verifiedRename(old,id,id.copy(name="other/repo")) }
        assertThrows(ImportFailure::class.java) { verifiedRename(old.copy(githubId=43),id,id) }
        assertEquals("new/repo",verifiedRename(old.copy(githubId=0),id,id))
    }
    @Test fun idCodecIsBackwardsCompatible() {
        val r = Registration("a/b",true,"r",githubId=42)
        assertEquals(r,CatalogCodec.decode(CatalogCodec.encode(r)))
        val legacy = org.json.JSONObject(CatalogCodec.encode(r)).apply { remove("githubId") }
        assertEquals(0L,CatalogCodec.decode(legacy.toString()).githubId)
    }
}
