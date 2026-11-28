package jp.xiaqui.artifact

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

internal data class RepoIdentity(val id: Long, val name: String)

/** Metadata redirects never leave the GitHub API or accept query credentials. */
internal class RepoIdentityApi(private val auth: ReleaseAuth,
    private val connect: (URL) -> HttpURLConnection = TlsCompatibility::open) {
    fun resolve(name: String, knownId: Long = 0, credentialRepo: String = name): RepoIdentity {
        var url = URL(if (knownId > 0) "https://api.github.com/repositories/$knownId" else "https://api.github.com/repos/${repoName(name)}")
        val individual = auth.tokenFor(credentialRepo)
        var token = individual
        var retried = false
        var redirects = 0
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val c = connect(url)
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = 10000; c.readTimeout = 15000; c.useCaches = false
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
                when (val code = c.responseCode) {
                    200 -> {
                        val b = ByteArrayOutputStream()
                        c.inputStream.use { transfer(it, b, 256 * 1024L) }
                        val j = JSONObject(b.toString("UTF-8"))
                        val id = j.getLong("id")
                        if (id <= 0) throw ImportFailure("GitHub IDを確認できません。旧登録を保持しました。")
                        return RepoIdentity(id, repoName(j.getString("full_name")))
                    }
                    301, 302, 307, 308 -> {
                        if (++redirects > 5) throw ImportFailure("改名の転送回数が上限を超えました。旧登録を保持しました。")
                        val next = URL(url, c.getHeaderField("Location") ?: throw ImportFailure("改名先を確認できません。"))
                        if (!safeIdentityUrl(next)) throw ImportFailure("安全なGitHub API転送先ではありません。旧登録を保持しました。")
                        url = next
                    }
                    else -> {
                        if (token == null && !retried && code in listOf(401, 403, 404, 429)) {
                            retried = true
                            token = auth.commonToken()
                            if (token != null) continue
                        }
                        throw ImportFailure("HTTP $code（repo同一性確認）。旧登録を保持しました。repo名・PAT対象とMetadata読み取り権限・組織承認を確認し、通信/API制限の解消後に登録編集から再試行してください。404だけでは不存在と権限不足を区別できません。")
                    }
                }
            } finally { c.disconnect() }
        }
    }
}

internal fun safeIdentityUrl(url: URL) = url.protocol == "https" && url.host == "api.github.com" &&
    url.port == -1 && url.userInfo == null && url.query == null && url.ref == null &&
    (url.path.matches(Regex("/repositories/[0-9]+")) || url.path.matches(Regex("/repos/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")))

internal fun verifiedRename(old: Registration, previous: RepoIdentity, target: RepoIdentity): String {
    if (previous.id != target.id || old.githubId > 0 && old.githubId != previous.id)
        throw ImportFailure("GitHub IDが異なるため移行できません。既存の登録・認証・APKを保持しました。別repoは登録追加を使ってください。")
    if (previous.name != target.name) throw ImportFailure("GitHubの現在名が確認中に変わりました。再試行してください。")
    return target.name
}
