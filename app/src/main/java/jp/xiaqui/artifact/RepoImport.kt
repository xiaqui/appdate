package jp.xiaqui.artifact

import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** API field, not a pasted URL. Do not reject valid repository names containing token-like words. */
internal fun remoteRepoName(value: String): String {
    val parts = value.split('/')
    if (parts.size != 2 || parts.any { !it.matches(Regex("[A-Za-z0-9_.-]{1,100}")) || it in listOf(".", "..") })
        throw ImportFailure("repo識別情報が不正です。")
    return value.lowercase()
}

internal data class RemoteRepo(val id: Long, val name: String)
internal data class RepoPage(val repos: List<RemoteRepo>, val more: Boolean)
internal data class ImportReport(val fetched: Int = 0, val added: Int = 0, val retained: Int = 0,
    val renamed: Int = 0, val error: String = "", val complete: Boolean = false) {
    val summary get() = "repo取得 ${if (complete) "完了" else "未完了"} · 取得 $fetched / 追加 $added / 既存 $retained" +
        (if (renamed > 0) "\n名前変更 $renamed 件。既存設定を保持しました。詳細で現在の名前を確認してください。" else "") +
        (if (error.isNotEmpty()) "\n$error" else if (complete && fetched == 0) "\n列挙結果は0件です。閲覧可能な全repoを列挙できるとは限りません。手動登録も利用できます。" else "")
}
internal enum class ImportChange { ADDED, EXISTING, RENAMED }

/** Only fixed api.github.com URLs receive the common PAT. Never follow redirects or server-provided URLs. */
internal class GitHubRepos(private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {
    fun page(token: String, page: Int): RepoPage {
        val url = URL("https://api.github.com/user/repos?visibility=all&affiliation=owner,collaborator,organization_member&sort=full_name&direction=asc&per_page=100&page=$page")
        val connection = connect(url)
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            connection.useCaches = false
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            val status = connection.responseCode
            if (status != 200) {
                val reason = when {
                    status == 429 || status == 403 && (connection.getHeaderField("X-RateLimit-Remaining") == "0" || connection.getHeaderField("Retry-After") != null) -> "API利用制限。時間をおいて再取得してください。"
                    status == 401 -> "認証が拒否されました。共通PATの期限・失効を確認してください。"
                    status == 403 -> "アクセスが拒否されました。対象repoと組織の承認を確認してください。"
                    status == 404 -> "列挙先にアクセスできません。不存在と権限不足は区別できません。"
                    status in 300..399 -> "安全のため列挙APIの転送を拒否しました。"
                    else -> "repo一覧を取得できません。再試行してください。"
                }
                throw ImportFailure("HTTP $status（repo列挙・共通PAT）。$reason")
            }
            val output = ByteArrayOutputStream()
            connection.inputStream.use { transfer(it, output, 8L * 1024 * 1024) }
            val rows = JSONArray(output.toString("UTF-8"))
            val repos = (0 until rows.length()).map {
                val row = rows.getJSONObject(it)
                val id = row.getLong("id")
                if (id <= 0) throw ImportFailure("repo識別情報が不正です。")
                RemoteRepo(id, remoteRepoName(row.getString("full_name")))
            }
            val more = connection.getHeaderField("Link").orEmpty().split(',').any {
                Regex(";\\s*rel=\"next\"").containsMatchIn(it)
            }
            return RepoPage(repos, more)
        } finally { connection.disconnect() }
    }
}

/** Commit each returned repo before requesting the next page; failures never remove registrations. */
internal fun importRepos(token: String, fetch: (String, Int) -> RepoPage,
    add: (RemoteRepo) -> ImportChange, current: () -> Boolean = { true },
    progress: (ImportReport) -> Unit = {}): ImportReport {
    var report = ImportReport()
    val seen = mutableSetOf<Long>()
    try {
        progress(report)
        var page = 1
        while (true) {
            if (Thread.currentThread().isInterrupted || !current()) throw InterruptedException()
            val result = fetch(token, page)
            if (Thread.currentThread().isInterrupted || !current()) throw InterruptedException()
            for (repo in result.repos) {
                if (Thread.currentThread().isInterrupted || !current()) throw InterruptedException()
                if (!seen.add(repo.id)) continue
                report = report.copy(fetched = report.fetched + 1)
                report = when (add(repo)) {
                    ImportChange.ADDED -> report.copy(added = report.added + 1)
                    ImportChange.EXISTING -> report.copy(retained = report.retained + 1)
                    ImportChange.RENAMED -> report.copy(retained = report.retained + 1, renamed = report.renamed + 1)
                }
                progress(report)
            }
            if (!result.more) break
            if (result.repos.isEmpty() || page == 10000) throw ImportFailure("列挙を完了できませんでした。取得済み登録を保持します。再取得してください。")
            page++
        }
        if (Thread.currentThread().isInterrupted || !current()) throw InterruptedException()
        report = report.copy(complete = true)
    } catch (e: Exception) {
        report = report.copy(error = when (e) {
            is InterruptedException -> "中断しました。追加済み登録は保持されています。"
            is ImportFailure -> e.message.orEmpty()
            is java.io.IOException -> "通信失敗。接続を確認してrepoを再取得してください。"
            else -> "repo一覧の応答を読み取れません。追加済み登録は保持されています。"
        })
    }
    progress(report)
    return report
}
