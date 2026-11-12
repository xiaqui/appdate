package jp.xiaqui.artifact

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.DigestInputStream
import java.security.MessageDigest

internal data class ReleaseItem(val id: Long, val tag: String)
internal data class ReleaseApk(val id: Long, val name: String, val size: Long, val digest: String?)

internal class GitHubReleases(private val auth: ReleaseAuth,
    private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {

    private fun <T> read(link: ReleaseLink, endpoint: String, binary: Boolean, consume: (InputStream) -> T): T {
        var url = URL(endpoint)
        check(url.protocol == "https" && url.host == "api.github.com" && url.port == -1 && url.userInfo == null)
        repeat(6) { hop ->
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val connection = connect(url)
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10000
                connection.readTimeout = 15000
                connection.useCaches = false
                if (hop == 0) {
                    auth.tokenFor(link.repository)?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
                    connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                }
                connection.setRequestProperty("Accept", if (binary) "application/octet-stream" else "application/vnd.github+json")
                when (val status = connection.responseCode) {
                    200 -> return connection.inputStream.use(consume)
                    301, 302, 303, 307, 308 -> {
                        if (!binary) throw ImportFailure("Releaseの移転先URLを共有し直してください。")
                        val location = connection.getHeaderField("Location") ?: throw ImportFailure("転送先がありません。")
                        url = URL(url, location)
                        if (url.protocol != "https" || url.userInfo != null || url.host.isEmpty() || url.port !in listOf(-1, 443))
                            throw ImportFailure("安全なHTTPS転送先を取得できません。")
                    }
                    401, 403, 404 -> throw ImportFailure("GitHub HTTP $status。トークンの期限・Contents読取権限、利用制限、Releaseの存在を確認してください。")
                    else -> throw ImportFailure("GitHubから取得できませんでした（HTTP $status）。")
                }
            } finally { connection.disconnect() }
        }
        throw ImportFailure("転送回数の上限を超えました。")
    }

    private fun json(link: ReleaseLink, endpoint: String) = read(link, endpoint, false) {
        val buffer = ByteArrayOutputStream()
        transfer(it, buffer, 4L * 1024 * 1024)
        buffer.toString("UTF-8")
    }

    fun releases(link: ReleaseLink): List<ReleaseItem> {
        val rows = JSONArray(json(link, "${link.apiBase}?per_page=30"))
        return (0 until rows.length()).map { rows.getJSONObject(it) }.filterNot { it.optBoolean("draft") }
            .map { ReleaseItem(it.getLong("id"), it.getString("tag_name")) }
    }

    fun assets(link: ReleaseLink, releaseId: Long? = null): List<ReleaseApk> {
        val id = releaseId ?: JSONObject(json(link, link.metadata)).getLong("id")
        val result = mutableListOf<ReleaseApk>()
        for (page in 1..10) {
            val rows = JSONArray(json(link, "${link.apiBase}/$id/assets?per_page=100&page=$page"))
            for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index)
                val name = row.getString("name")
                if (name.endsWith(".apk", true) && (link.name == null || name == link.name)) {
                    val digest = row.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")
                    result += ReleaseApk(row.getLong("id"), name, row.getLong("size"), digest)
                }
            }
            if (rows.length() < 100 || (link.name != null && result.isNotEmpty())) return result
        }
        val overflow = JSONArray(json(link, "${link.apiBase}/$id/assets?per_page=100&page=11"))
        if (overflow.length() == 0) return result
        throw ImportFailure("このReleaseのAsset数が上限（1,000件）を超えています。")
    }

    fun download(link: ReleaseLink, apk: ReleaseApk, target: File) {
        if (apk.size !in 1..MAX_INPUT) throw ImportFailure("APKがサイズ上限外です。")
        val sha = MessageDigest.getInstance("SHA-256")
        read(link, "${link.apiBase}/assets/${apk.id}", true) { stream ->
            val count = target.outputStream().use { transfer(DigestInputStream(stream, sha), it, apk.size) }
            if (count != apk.size) throw ImportFailure("APKの取得が途中で終了しました。")
        }
        val digest = sha.digest().joinToString("") { "%02x".format(it) }
        if (apk.digest != null && !digest.equals(apk.digest, true)) throw ImportFailure("APKのSHA-256がReleaseと一致しません。")
    }
}
