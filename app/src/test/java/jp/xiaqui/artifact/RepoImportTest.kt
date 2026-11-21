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

    @Test fun publicAndPrivateCandidatesRequireApkAcrossAllReleasePages() {
        val requests = mutableListOf<Reply>()
        val repos = GitHubRepos { url ->
            val page = if (requests.isEmpty()) 1 else 2
            Reply(url, 200, if (page == 1)
                """[{"id":1,"full_name":"Owner/Public"},{"id":2,"full_name":"owner/private"}]"""
            else """[{"id":1,"full_name":"OWNER/Public"},{"id":3,"full_name":"owner/no-apk"}]""",
                if (page == 1) mapOf("Link" to "<https://ignored.example/page>; rel=\"next\"") else emptyMap()).also(requests::add)
        }
        val added = mutableListOf<RemoteRepo>()
        val releasePages = mutableMapOf<String,Int>()
        val report = importRepos("fakeSecret", repos::page, { token, repo, page ->
            assertEquals("fakeSecret", token)
            releasePages[repo.name] = page
            when (repo.name) {
                "owner/public" -> ApkReleasePage(page == 2, page == 1, 100)
                "owner/private" -> ApkReleasePage(true, false)
                else -> ApkReleasePage(false, false)
            }
        }, { null }, { added += it; ImportChange.ADDED })
        assertTrue(report.complete)
        assertEquals(3, report.fetched); assertEquals(3, report.checked)
        assertEquals(2, report.added); assertEquals(1, report.skipped); assertEquals(0, report.deferred)
        assertEquals(listOf("owner/public", "owner/private"), added.map { it.name })
        assertEquals(listOf("owner/public", "owner/private"), report.addedRepos)
        assertEquals(2, releasePages["owner/public"])
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.url.host == "api.github.com" && it.url.path == "/user/repos" &&
            it.getRequestProperty("Authorization") == "Bearer fakeSecret" && !it.instanceFollowRedirects })
    }

    @Test fun releaseApiAcceptsOnlyUploadedNonDraftApkAssetsAndUsesFixedHost() {
        val requests = mutableListOf<Reply>()
        val api = GitHubApkReleases { url ->
            val page = requests.size + 1
            val body = if (page == 1)
                """[{"draft":false,"assets":[{"name":"source.zip","state":"uploaded","size":3},{"name":"bad.apk","state":"new","size":3}]},{"draft":true,"assets":[{"name":"draft.apk","state":"uploaded","size":3}]}]"""
            else """[{"draft":false,"assets":[{"name":"App.APK","state":"uploaded","size":4}]}]"""
            Reply(url, 200, body, if (page == 1) mapOf("Link" to "<ignored>; rel=\"next\"") else emptyMap()).also(requests::add)
        }
        assertTrue(hasApkHistory("fixture", RemoteRepo(1,"Owner/Repo"), api::page))
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.url.host == "api.github.com" && it.url.path == "/repos/owner/repo/releases" &&
            it.getRequestProperty("Authorization") == "Bearer fixture" && !it.instanceFollowRedirects })
    }

    @Test fun releaseFailureIsDeferredWhileOtherReposContinueAndExistingIsNeverFiltered() {
        val rows = listOf(RemoteRepo(1,"o/has"), RemoteRepo(2,"o/private"), RemoteRepo(3,"o/none"), RemoteRepo(4,"o/existing"))
        var probes = 0
        val added = mutableListOf<String>()
        val report = importRepos("secret", { _,_ -> RepoPage(rows,false) }, { _,repo,_ ->
            probes++
            when (repo.id) {
                1L -> ApkReleasePage(true,false)
                2L -> throw ImportFailure("HTTP 403（Release履歴確認）。権限を確認してください。")
                else -> ApkReleasePage(false,false)
            }
        }, { if (it.id == 4L) ImportChange.EXISTING else null }, { added += it.name; ImportChange.ADDED })
        assertTrue(report.listingComplete); assertFalse(report.complete)
        assertEquals(1, report.added); assertEquals(1, report.retained)
        assertEquals(1, report.skipped); assertEquals(1, report.deferred); assertEquals(3, probes)
        assertEquals(listOf("o/has"), added)
        assertEquals(listOf("o/has"), report.addedRepos)
        assertTrue(report.summary.contains("HTTP 403")); assertFalse(report.summary.contains("secret"))
    }

    @Test fun realReleaseClientDefersAuthRateLimitNotFoundAndNetworkFailuresWithoutLeaks() {
        val repo = RemoteRepo(7,"owner/private")
        val cases = listOf(
            Triple(401, emptyMap<String,String>(), "HTTP 401"),
            Triple(403, emptyMap(), "HTTP 403"),
            Triple(403, mapOf("X-RateLimit-Remaining" to "0"), "利用制限"),
            Triple(404, emptyMap(), "HTTP 404"),
            Triple(429, emptyMap(), "利用制限")
        )
        for ((status, headers, expected) in cases) {
            var request: Reply? = null
            val api = GitHubApkReleases { url -> Reply(url,status,"fakeSecret",headers).also { request=it } }
            val report = importRepos("fakeSecret", { _,_ -> RepoPage(listOf(repo),false) }, api::page,
                { null }, { ImportChange.ADDED })
            assertTrue(report.listingComplete); assertFalse(report.complete)
            assertEquals(1, report.deferred); assertEquals(0, report.added)
            assertTrue(report.error.contains(expected)); assertFalse(report.summary.contains("fakeSecret"))
            assertEquals("api.github.com",request!!.url.host)
            assertEquals("/repos/owner/private/releases",request!!.url.path)
            assertEquals("Bearer fakeSecret",request!!.getRequestProperty("Authorization"))
            assertFalse(request!!.instanceFollowRedirects)
        }
        val network = GitHubApkReleases { throw java.io.IOException("fakeSecret") }
        val failed = importRepos("fakeSecret", { _,_ -> RepoPage(listOf(repo),false) }, network::page,
            { null }, { ImportChange.ADDED })
        assertEquals(1, failed.deferred); assertTrue(failed.error.contains("通信失敗"))
        assertFalse(failed.summary.contains("fakeSecret"))
    }

    @Test fun futureApkPublicationIsAddedOnNextRunWithoutDeletingExisting() {
        val repo = RemoteRepo(9,"o/future")
        var published = false
        val added = mutableSetOf<Long>()
        fun run() = importRepos("fixture", { _,_ -> RepoPage(listOf(repo),false) },
            { _,_,_ -> ApkReleasePage(published,false) },
            { if (it.id in added) ImportChange.EXISTING else null },
            { added += it.id; ImportChange.ADDED })
        val first = run(); assertEquals(1, first.skipped); assertTrue(added.isEmpty())
        published = true
        val second = run(); assertEquals(1, second.added); assertTrue(9L in added)
        published = false
        val third = run(); assertEquals(1, third.retained); assertEquals(0, third.checked); assertTrue(9L in added)
        assertTrue(third.addedRepos.isEmpty())
    }

    @Test fun initialRefreshTargetsOnlyNewReposAndContinuesAfterPerRepoFailure() {
        val calls = mutableListOf<String>()
        val result = refreshAddedRepos(listOf("o/added", "o/failed", "o/added", "o/throws")) { repo ->
            calls += repo
            when (repo) {
                "o/failed" -> false
                "o/throws" -> throw ImportFailure("fixture")
                else -> true
            }
        }
        assertEquals(listOf("o/added", "o/failed", "o/throws"), calls)
        assertEquals(InitialRefreshReport(3, 1, 2), result)
        assertTrue(result.summary.contains("再試行 2"))
        assertEquals(InitialRefreshReport(0, 0, 0), refreshAddedRepos(emptyList()) { fail(); false })
    }

    @Test fun importCompletionKeepsNavigationAndRefreshScopeForPatAndManualFlows() {
        val called = mutableListOf<String>()
        val imported = ImportReport(
            added = 2, retained = 3,
            addedRepos = listOf("o/new", "o/fails")
        )
        val pat = completeRepoImport(imported, returnHome = true) { repo ->
            called += repo
            repo != "o/fails"
        }
        assertEquals(listOf("o/new", "o/fails"), called)
        assertEquals(InitialRefreshReport(2, 1, 1), pat.refresh)
        assertTrue(pat.returnHome)

        called.clear()
        val manual = completeRepoImport(imported, returnHome = false) { repo -> called += repo; true }
        assertEquals(listOf("o/new", "o/fails"), called)
        assertFalse(manual.returnHome)
        val zero = completeRepoImport(ImportReport(retained = 7), returnHome = true) { fail(); false }
        assertEquals(InitialRefreshReport(0, 0, 0), zero.refresh)
        assertTrue(zero.returnHome)
    }

    @Test fun listFailuresCancellationAndEmptyResponsesRemainDistinct() {
        for (status in listOf(401,403,404,429)) {
            val api = GitHubRepos { url -> Reply(url,status,"fakeSecret") }
            val report = importRepos("fakeSecret",api::page,{ _,_,_ -> fail(); ApkReleasePage(false,false) },{null},{ImportChange.ADDED})
            assertFalse(report.complete); assertFalse(report.listingComplete)
            assertTrue(report.error.contains("HTTP $status")); assertFalse(report.summary.contains("fakeSecret"))
        }
        val cancelled = importRepos("fixture",{ _,_ -> RepoPage(listOf(RemoteRepo(1,"o/a")),false) },
            { _,_,_ -> ApkReleasePage(true,false) },{null},{ImportChange.ADDED},current={ false })
        assertTrue(cancelled.error.contains("中断")); assertEquals(0,cancelled.added)
        val empty = importRepos("fixture",{ _,_ -> RepoPage(emptyList(),false) },
            { _,_,_ -> ApkReleasePage(false,false) },{null},{ImportChange.ADDED})
        assertTrue(empty.complete); assertTrue(empty.summary.contains("0件"))
        assertThrows(ImportFailure::class.java) {
            hasApkHistory("fixture", RemoteRepo(1,"o/a"), { _,_,_ -> ApkReleasePage(false,true,0) })
        }
        var current = true
        var adds = 0
        val changed = importRepos("old", { _,_ -> RepoPage(listOf(RemoteRepo(2,"o/b")),false) },
            { _,_,_ -> current = false; ApkReleasePage(true,false) }, { null },
            { adds++; ImportChange.ADDED }, current = { current })
        assertEquals(0, adds); assertEquals(0, changed.added)
        assertTrue(changed.error.contains("中断"))
    }

    @Test fun apiNamesCodecAndNoDistributionStatusRemainCompatible() {
        assertEquals("owner/ghp_demo",remoteRepoName("Owner/ghp_demo"))
        assertEquals("owner/github_pat_examples",remoteRepoName("owner/github_pat_examples"))
        for (name in listOf("https://github.com/o/r", "o/../r", "o/r?token=x", "o/.."))
            assertThrows(ImportFailure::class.java) { remoteRepoName(name) }
        val r = Registration("o/r",false,"rev",githubId=42)
        assertEquals(r,CatalogCodec.decode(CatalogCodec.encode(r)))
        val old = org.json.JSONObject(CatalogCodec.encode(r)).apply { remove("githubId") }.toString()
        assertEquals(0L,CatalogCodec.decode(old).githubId)
        val checked=Registration("o/r",true,"rev",fetched=1,checked=1)
        assertEquals("配布なし",rowFor(checked,true){null}.status)
        assertTrue(rowFor(checked.copy(error="HTTP 404"),true){null}.status.contains("確認失敗"))
        assertEquals("未確認",rowFor(checked.copy(fetched=0,checked=0),true){null}.status)
    }
}
