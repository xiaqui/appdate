package jp.xiaqui.artifact

import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class RepoImportTest {
    private class Reply(url: URL, val status: Int, val body: String, val headers: Map<String,String> = emptyMap()) : HttpURLConnection(url) {
        override fun getResponseCode() = status
        override fun getInputStream() = body.byteInputStream()
        override fun getHeaderField(name: String) = headers[name]
        override fun disconnect() {}
        override fun connect() {}
        override fun usingProxy() = false
    }
    @Test fun paginationImportsPublicPrivateArchivedWithoutReleaseSelection() {
        val requests = mutableListOf<Reply>()
        val api = GitHubRepos { url ->
            val page = if (requests.isEmpty()) 1 else 2
            Reply(url,200, if (page == 1) """[{"id":1,"full_name":"Owner/Public","private":false,"archived":true},{"id":2,"full_name":"owner/private","private":true}]"""
                else """[{"id":1,"full_name":"OWNER/Public"},{"id":3,"full_name":"owner/no-release"}]""",
                if (page == 1) mapOf("Link" to "<https://untrusted.example/path>; rel=\"next\"") else emptyMap()).also(requests::add)
        }
        val added = mutableListOf<RemoteRepo>()
        val report = importRepos("fakeSecret",api::page,{ added += it; ImportChange.ADDED })
        assertTrue(report.complete); assertEquals(3,report.added); assertEquals(3, report.fetched)
        assertEquals(listOf("owner/public","owner/private","owner/no-release"),added.map { it.name })
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.url.host == "api.github.com" && it.url.path == "/user/repos" && it.getRequestProperty("Authorization") == "Bearer fakeSecret" && !it.instanceFollowRedirects })
        assertTrue(requests.last().url.query.endsWith("page=2"))
    }
    @Test fun failureAndCancellationRetainCompletedAddsAndCounts() {
        for (status in listOf(401,403,404,429)) {
            var pages = 0
            val api = GitHubRepos { url ->
                pages++
                if (pages == 1) Reply(url,200,"""[{"id":1,"full_name":"o/a"}]""",mapOf("Link" to "<ignored>; rel=\"next\""))
                else Reply(url,status,"fakeSecret")
            }
            val report = importRepos("fakeSecret",api::page,{ ImportChange.ADDED })
            assertFalse(report.complete); assertEquals(1,report.added); assertEquals(1,report.fetched)
            assertTrue(report.error.contains("HTTP $status")); assertFalse(report.summary.contains("fakeSecret"))
        }
        val cancelled = importRepos("fixture",{ _,_ -> RepoPage(listOf(RemoteRepo(1,"o/a")),false) },{ ImportChange.ADDED },current={ false })
        assertTrue(cancelled.error.contains("中断")); assertEquals(0,cancelled.added)
    }
    @Test fun distinguishesEmptyNetworkRateLimitAndMalformedResponse() {
        val empty = importRepos("fixture",{ _,_ -> RepoPage(emptyList(),false) },{ ImportChange.ADDED })
        assertTrue(empty.complete); assertTrue(empty.summary.contains("0件"))
        val network = importRepos("fixture",{ _,_ -> throw java.io.IOException("fixture") },{ ImportChange.ADDED })
        assertTrue(network.error.contains("通信失敗")); assertFalse(network.summary.contains("fixture"))
        for ((status,body,headers) in listOf(Triple(403,"",mapOf("Retry-After" to "10")),Triple(302,"",mapOf("Location" to "https://evil.test")),Triple(200,"secret-body",emptyMap()))) {
            var calls=0
            val api = GitHubRepos { url -> calls++; Reply(url,status,body,headers) }
            val report = importRepos("fakeSecret",api::page,{ ImportChange.ADDED })
            assertFalse(report.complete); assertEquals(1,calls)
            assertFalse(report.summary.contains("secret-body")); assertFalse(report.summary.contains("fakeSecret"))
            if (status == 403) assertTrue(report.error.contains("利用制限"))
        }
    }
    @Test fun apiNamesAreNotFilteredByTokenLikeWords() {
        assertEquals("owner/ghp_demo",remoteRepoName("Owner/ghp_demo"))
        assertEquals("owner/github_pat_examples",remoteRepoName("owner/github_pat_examples"))
        for (name in listOf("https://github.com/o/r", "o/../r", "o/r?token=x", "o/.."))
            assertThrows(ImportFailure::class.java) { remoteRepoName(name) }
    }
    @Test fun changedTokenDuringEmptyResponseDoesNotReportSuccess() {
        var current = true
        val result = importRepos("fixture",{ _,_ -> current=false; RepoPage(emptyList(),false) },
            { ImportChange.ADDED },current={current})
        assertFalse(result.complete); assertTrue(result.error.contains("中断"))
    }
    @Test fun codecReadsLegacyAndPreservesStableIdentity() {
        val r = Registration("o/r",false,"rev",githubId=42,remoteName="o/renamed")
        assertEquals(r,CatalogCodec.decode(CatalogCodec.encode(r)))
        val old = org.json.JSONObject(CatalogCodec.encode(r)).apply { remove("githubId"); remove("remoteName") }.toString()
        assertEquals(0L,CatalogCodec.decode(old).githubId)
    }
    @Test fun noDistributionIsOnlyShownAfterSuccessfulEmptyHistory() {
        val r=Registration("o/r",true,"rev",fetched=1,checked=1)
        assertEquals("配布なし",rowFor(r,true){null}.status)
        assertTrue(rowFor(r.copy(error="HTTP 404"),true){null}.status.contains("確認失敗"))
        assertEquals("未確認",rowFor(r.copy(fetched=0,checked=0),true){null}.status)
    }
}
