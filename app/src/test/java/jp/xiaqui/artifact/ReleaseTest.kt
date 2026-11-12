package jp.xiaqui.artifact

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.HttpURLConnection
import java.net.URL

class ReleaseTest {
    @get:Rule val temp = TemporaryFolder()
    private val base = "https://github.com/example/private/releases"
    private val link = requireNotNull(ReleaseLink.parse("$base/tag/v1"))
    private val auth = SessionAuth().apply { use(link.repository, "fixture") }

    private class Reply(val status: Int, val body: String = "", val redirect: String? = null)
    private class Exchange(url: URL, val reply: Reply) : HttpURLConnection(url) {
        override fun getResponseCode() = reply.status
        override fun getInputStream() = reply.body.byteInputStream()
        override fun getHeaderField(name: String) = reply.redirect.takeIf { name == "Location" }
        override fun disconnect() {}
        override fun connect() {}
        override fun usingProxy() = false
    }
    private val requests = mutableListOf<Exchange>()
    private fun service(vararg replies: Reply): GitHubReleases {
        val iterator = replies.iterator()
        return GitHubReleases(auth) { Exchange(it, iterator.next()).also(requests::add) }
    }

    @Test fun parsesSharedFormsAndEncodesTagOnce() {
        assertTrue(ReleaseLink.parse(base)!!.list)
        assertTrue(ReleaseLink.parse("$base/latest")!!.metadata.endsWith("/latest"))
        val target = ReleaseLink.parse("$base/download/test%2Fv+1/app%20debug.apk?download=1")!!
        assertEquals("app debug.apk", target.name)
        assertTrue(target.metadata.endsWith("/tags/test%2Fv%2B1"))
        assertEquals("app.apk", ReleaseLink.parse("$base/latest/download/app.apk")!!.name)
        for (text in listOf(base.replace("github.com","github.com.evil.test"), base.replace("github.com","user@github.com"),
                base.replace("https","http"), base.replace("github.com","github.com:444")))
            assertNull(ReleaseLink.parse(text))
        assertThrows(ImportFailure::class.java) { ReleaseLink.parse("$base/tag/%2E%2E") }
    }

    @Test fun credentialsAreScopedAndClearable() {
        assertNull(auth.tokenFor("other/private"))
        assertEquals("fixture", auth.tokenFor("Example/private"))
        auth.clear()
        assertNull(auth.tokenFor(link.repository))
    }

    @Test fun listsPublishedReleasesAndSelectsApkByExactName() {
        val response = """[{"id":3,"tag_name":"preview","draft":false},{"id":4,"tag_name":"hidden","draft":true}]"""
        val rows = """[{"id":5,"name":"other.apk","size":4},{"id":6,"name":"readme","size":1},{"id":7,"name":"app.apk","size":3}]"""
        val api = service(Reply(200, response), Reply(200, """{"id":3}"""), Reply(200, rows))
        assertEquals(listOf(ReleaseItem(3,"preview")), api.releases(link))
        assertEquals(listOf(ReleaseApk(7,"app.apk",3,null)), api.assets(link.copy(name="app.apk")))
        assertTrue(requests.all { it.getRequestProperty("Authorization") == "Bearer fixture" })
        assertTrue(requests.last().url.path.endsWith("/releases/3/assets"))
    }

    @Test fun handlesExactlyOneThousandAssets() {
        val rows = List(100) { """{"name":"notes"}""" }.joinToString(",", "[", "]")
        val pages = List(10) { Reply(200, rows) } + Reply(200,"[]")
        assertTrue(service(*pages.toTypedArray()).assets(link, 7).isEmpty())
        assertEquals("per_page=100&page=11", requests.last().url.query)
    }

    @Test fun binaryRedirectDropsCredentialAndChecksDigest() {
        val api = service(Reply(302, redirect="https://cdn.example.test/signed"), Reply(200,"abc"))
        val file = temp.newFile()
        val apk = ReleaseApk(1,"app.apk",3,"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        api.download(link, apk, file)
        assertEquals("abc", file.readText())
        assertEquals("application/octet-stream", requests.first().getRequestProperty("Accept"))
        assertEquals("Bearer fixture", requests.first().getRequestProperty("Authorization"))
        assertNull(requests.last().getRequestProperty("Authorization"))
    }

    @Test fun rejectsAuthFailureDowngradeTruncationAndWrongDigest() {
        assertThrows(ImportFailure::class.java) { service(Reply(404)).assets(link) }
        val apk = ReleaseApk(5,"demo.apk",3,null)
        for (reply in listOf(Reply(200,"ab"), Reply(200,"abcd"), Reply(302, redirect="http://example.test/a"))) {
            assertThrows(ImportFailure::class.java) { service(reply).download(link, apk, temp.newFile()) }
        }
        assertThrows(ImportFailure::class.java) { service(Reply(200,"abc")).download(link, apk.copy(digest="bad"), temp.newFile()) }
    }
}
