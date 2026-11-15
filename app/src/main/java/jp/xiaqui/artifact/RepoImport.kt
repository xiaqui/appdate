package jp.xiaqui.artifact

import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

internal fun remoteRepoName(value: String): String {
    val parts = value.split('/')
    if (parts.size != 2 || parts.any { !it.matches(Regex("[A-Za-z0-9_.-]{1,100}")) || it in listOf(".", "..") })
        throw ImportFailure("repo識別情報が不正です。")
    return value.lowercase()
}

internal data class RemoteRepo(val id: Long, val name: String)
internal data class RepoPage(val repos: List<RemoteRepo>, val more: Boolean)
internal data class ApkReleasePage(val found: Boolean, val more: Boolean, val items: Int = if (found) 1 else 0)
internal data class ImportReport(
    val fetched: Int = 0, val checked: Int = 0, val added: Int = 0,
    val retained: Int = 0, val renamed: Int = 0, val skipped: Int = 0,
    val deferred: Int = 0, val error: String = "", val listingComplete: Boolean = false,
    val complete: Boolean = false
) {
    val summary get() = buildString {
        append("repo取得 ${if (listingComplete) "完了" else "未完了"} · 取得 $fetched / 追加 $added / 既存 $retained")
        append("\nAPK履歴確認 $checked / 配布なし $skipped / 判定保留 $deferred")
        if (renamed > 0) append("\n名前変更 $renamed 件。GitHub IDで既存登録を保持しました。登録編集で現在名を確認してください。")
        if (error.isNotEmpty()) append("\n$error")
        else if (listingComplete && fetched == 0) append("\n列挙結果は0件です。閲覧可能な全repoを列挙できるとは限りません。手動登録も利用できます。")
    }
}
internal enum class ImportChange { ADDED, EXISTING, RENAMED }

internal class GitHubRepos(private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {
    fun page(token: String, page: Int): RepoPage {
        val url = URL("https://api.github.com/user/repos?visibility=all&affiliation=owner,collaborator,organization_member&sort=full_name&direction=asc&per_page=100&page=$page")
        val connection = connect(url)
        try {
            prepare(connection, token)
            val status = connection.responseCode
            if (status != 200) throw apiFailure(status, "repo列挙・共通PAT", connection)
            val rows = readArray(connection)
            val repos = (0 until rows.length()).map {
                val row = rows.getJSONObject(it)
                val id = row.getLong("id")
                if (id <= 0) throw ImportFailure("repo識別情報が不正です。")
                RemoteRepo(id, remoteRepoName(row.getString("full_name")))
            }
            return RepoPage(repos, hasNext(connection))
        } finally { connection.disconnect() }
    }
}

internal class GitHubApkReleases(private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {
    fun page(token: String, repo: RemoteRepo, page: Int): ApkReleasePage {
        val name = remoteRepoName(repo.name)
        val connection = connect(URL("https://api.github.com/repos/$name/releases?per_page=100&page=$page"))
        try {
            prepare(connection, token)
            val status = connection.responseCode
            if (status != 200) throw apiFailure(status, "Release履歴確認 $name", connection)
            val rows = readArray(connection)
            val found = (0 until rows.length()).any { index ->
                val release = rows.getJSONObject(index)
                !release.optBoolean("draft", false) && release.optJSONArray("assets")?.let { assets ->
                    (0 until assets.length()).any { assetIndex ->
                        val asset = assets.getJSONObject(assetIndex)
                        asset.optString("state") == "uploaded" && asset.optLong("size") > 0 &&
                            asset.optString("name").endsWith(".apk", ignoreCase = true)
                    }
                } == true
            }
            return ApkReleasePage(found, hasNext(connection), rows.length())
        } finally { connection.disconnect() }
    }
}

private fun prepare(connection: HttpURLConnection, token: String) {
    connection.instanceFollowRedirects = false
    connection.connectTimeout = 10000
    connection.readTimeout = 15000
    connection.useCaches = false
    connection.setRequestProperty("Authorization", "Bearer $token")
    connection.setRequestProperty("Accept", "application/vnd.github+json")
    connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
}

private fun readArray(connection: HttpURLConnection): JSONArray {
    val output = ByteArrayOutputStream()
    connection.inputStream.use { transfer(it, output, 8L * 1024 * 1024) }
    return JSONArray(output.toString("UTF-8"))
}

private fun hasNext(connection: HttpURLConnection) = connection.getHeaderField("Link").orEmpty().split(',').any {
    Regex(";\\s*rel=\"next\"").containsMatchIn(it)
}

private fun apiFailure(status: Int, operation: String, connection: HttpURLConnection): ImportFailure {
    val reason = when {
        status == 429 || status == 403 && (connection.getHeaderField("X-RateLimit-Remaining") == "0" || connection.getHeaderField("Retry-After") != null) -> "API利用制限。時間をおいて再取得してください。"
        status == 401 -> "認証が拒否されました。共通PATの期限・失効を確認してください。"
        status == 403 -> "アクセスが拒否されました。対象repoと組織の承認を確認してください。"
        status == 404 -> "アクセスできません。不存在と権限不足は区別できません。"
        status in 300..399 -> "安全のためGitHub APIの転送を拒否しました。"
        else -> "GitHub APIから取得できません。再試行してください。"
    }
    return ImportFailure("HTTP $status（$operation）。$reason")
}

internal fun hasApkHistory(token: String, repo: RemoteRepo,
    fetch: (String, RemoteRepo, Int) -> ApkReleasePage,
    current: () -> Boolean = { true }): Boolean {
    var page = 1
    while (true) {
        if (Thread.currentThread().isInterrupted || !current()) throw InterruptedException()
        val result = fetch(token, repo, page)
        if (Thread.currentThread().isInterrupted || !current()) throw InterruptedException()
        if (result.found) return true
        if (!result.more) return false
        if (result.items == 0) throw ImportFailure("Release履歴のページング応答が空です。判定を保留して再取得してください。")
        if (page == 10000) throw ImportFailure("Release履歴の全ページを確認できません。再取得してください。")
        page++
    }
}

internal fun importRepos(
    token: String,
    fetch: (String, Int) -> RepoPage,
    releasePage: (String, RemoteRepo, Int) -> ApkReleasePage,
    observe: (RemoteRepo) -> ImportChange?,
    add: (RemoteRepo) -> ImportChange,
    current: () -> Boolean = { true },
    progress: (ImportReport) -> Unit = {}
): ImportReport {
    var report = ImportReport()
    val seen = mutableSetOf<Long>()
    val errors = mutableListOf<String>()
    fun publish() {
        val details = errors.take(3).joinToString("\n") + if (errors.size > 3) "\nほか ${errors.size - 3} 件は再取得してください。" else ""
        report = report.copy(error = details)
        progress(report)
    }
    try {
        publish()
        var page = 1
        while (true) {
            if (Thread.currentThread().isInterrupted || !current()) throw InterruptedException()
            val result = fetch(token, page)
            if (Thread.currentThread().isInterrupted || !current()) throw InterruptedException()
            for (repo in result.repos) {
                if (Thread.currentThread().isInterrupted || !current()) throw InterruptedException()
                if (!seen.add(repo.id)) continue
                report = report.copy(fetched = report.fetched + 1)
                try {
                    val known = observe(repo)
                    if (known != null) {
                        report = when (known) {
                            ImportChange.ADDED -> throw IllegalStateException("observe must not add")
                            ImportChange.EXISTING -> report.copy(retained = report.retained + 1)
                            ImportChange.RENAMED -> report.copy(retained = report.retained + 1, renamed = report.renamed + 1)
                        }
                    } else {
                        report = report.copy(checked = report.checked + 1)
                        if (hasApkHistory(token, repo, releasePage, current)) {
                            if (Thread.currentThread().isInterrupted || !current()) throw InterruptedException()
                            report = when (add(repo)) {
                                ImportChange.ADDED -> report.copy(added = report.added + 1)
                                ImportChange.EXISTING -> report.copy(retained = report.retained + 1)
                                ImportChange.RENAMED -> report.copy(retained = report.retained + 1, renamed = report.renamed + 1)
                            }
                        } else report = report.copy(skipped = report.skipped + 1)
                    }
                } catch (e: Exception) {
                    if (e is InterruptedException) throw e
                    report = report.copy(deferred = report.deferred + 1)
                    errors += "${repo.name}: " + when (e) {
                        is ImportFailure -> e.message.orEmpty()
                        is java.io.IOException -> "通信失敗。接続を確認して再取得してください。"
                        else -> "Release履歴を確認できません。再取得してください。"
                    }
                }
                publish()
            }
            if (!result.more) break
            if (result.repos.isEmpty() || page == 10000) throw ImportFailure("repo列挙を完了できませんでした。取得済み登録を保持します。再取得してください。")
            page++
        }
        if (Thread.currentThread().isInterrupted || !current()) throw InterruptedException()
        report = report.copy(listingComplete = true, complete = report.deferred == 0)
    } catch (e: Exception) {
        errors += when (e) {
            is InterruptedException -> "中断しました。追加済み登録は保持されています。"
            is ImportFailure -> e.message.orEmpty()
            is java.io.IOException -> "通信失敗。接続を確認してrepoを再取得してください。"
            else -> "repo一覧の応答を読み取れません。追加済み登録は保持されています。"
        }
    }
    publish()
    return report
}
