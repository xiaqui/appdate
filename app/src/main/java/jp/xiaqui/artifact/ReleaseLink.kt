package jp.xiaqui.artifact

import java.net.URI
import java.net.URLEncoder

internal data class ReleaseLink(val repository: String, val tag: String?, val name: String?, val list: Boolean) {
    val apiBase get() = "https://api.github.com/repos/$repository/releases"
    val metadata get() = if (tag == null) "$apiBase/latest" else "$apiBase/tags/" + encode(tag)

    companion object {
        private val slug = Regex("[A-Za-z0-9_.-]{1,100}")
        private fun encode(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

        fun parse(text: String): ReleaseLink? {
            val uri = runCatching { URI(text) }.getOrNull() ?: return null
            if (uri.scheme != "https" || !uri.host.equals("github.com", true) ||
                uri.port != -1 || uri.rawUserInfo != null) return null
            val parts = uri.rawPath.trimEnd('/').removePrefix("/").split('/')
            if (parts.size < 3 || parts[2] != "releases") return null
            if (parts.take(2).any { !slug.matches(it) || it == "." || it == ".." })
                throw ImportFailure("リポジトリ名を確認してください。")
            val repo = parts.take(2).joinToString("/")
            if (parts.size == 3) return ReleaseLink(repo, null, null, true)
            val values = parts.drop(4).map {
                val value = java.net.URLDecoder.decode(it.replace("+", "%2B"), "UTF-8")
                if (value.split('/').any { part -> part.isEmpty() || part == "." || part == ".." } ||
                    value.any { c -> c == '\\' || Character.isISOControl(c) }) throw ImportFailure("Release URLが不正です。")
                value
            }
            return when {
                parts[3] == "latest" && values.isEmpty() -> ReleaseLink(repo, null, null, false)
                parts[3] == "latest" && values.size == 2 && values[0] == "download" -> ReleaseLink(repo, null, values[1], false)
                parts[3] == "tag" && values.isNotEmpty() -> ReleaseLink(repo, values.joinToString("/"), null, false)
                parts[3] == "download" && values.size >= 2 -> ReleaseLink(repo, values.dropLast(1).joinToString("/"), values.last(), false)
                else -> throw ImportFailure("Releaseの一覧、タグ、またはAPK Assetのリンクを共有してください。")
            }
        }
    }
}

/** Explicitly supplied by the embedding app. Secrets are held only for this process and repository. */
internal fun interface ReleaseAuth { fun tokenFor(repository: String): String? }

internal class SessionAuth : ReleaseAuth {
    private val entries = java.util.concurrent.ConcurrentHashMap<String, String>()
    override fun tokenFor(repository: String) = entries[repository.lowercase()]
    fun use(repository: String, token: String) { entries[repository.lowercase()] = token }
    fun forget(repository: String) { entries.remove(repository.lowercase()) }
    fun clear() { entries.clear() }
}

internal val processAuth = SessionAuth()
