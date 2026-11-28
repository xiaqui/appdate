package jp.xiaqui.artifact

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.security.DigestInputStream
import java.security.MessageDigest

internal const val PROVIDER_GITHUB = "github"
internal const val PROVIDER_FORGEJO = "forgejo"

internal data class ProviderIdentity(val provider: String, val server: String, val repositoryId: Long) {
    val key: String get() = "$provider|$server|$repositoryId"
}

internal fun normalizeServer(raw: String): String {
    rejectCredentialUrl(raw)
    val uri = runCatching { URI(raw.trim()) }.getOrNull()
        ?: throw ImportFailure("ForgejoのサーバーURLを確認してください。")
    if (!uri.scheme.equals("https", true) || uri.host.isNullOrBlank() || uri.rawUserInfo != null ||
        uri.rawQuery != null || uri.rawFragment != null || uri.port !in listOf(-1, 443))
        throw ImportFailure("認証情報を含まないHTTPSのForgejoサーバーURLを指定してください。")
    val path = uri.rawPath.orEmpty().trimEnd('/').also { value ->
        if (value.split('/').any { it == "." || it == ".." } || value.contains('\\'))
            throw ImportFailure("Forgejoのサブパスを確認してください。")
    }
    return "https://${uri.host.lowercase()}$path"
}

internal fun sourceIdentity(provider: String, server: String, repositoryId: Long) =
    ProviderIdentity(provider, if (provider == PROVIDER_GITHUB) "https://github.com" else normalizeServer(server), repositoryId)

internal interface ReleaseProvider {
    fun history(source: DistributionSource): List<StoreRelease>
    fun download(source: DistributionSource, apk: ReleaseApk, target: File, allowed: () -> Unit = {})
}

internal class GitHubProvider(private val api: GitHubReleases) : ReleaseProvider {
    override fun history(source: DistributionSource): List<StoreRelease> =
        api.history(ReleaseLink(source.repository, null, null, true)).map { release ->
            release.copy(sourceKey = source.key, assets = release.assets.map { it.copy(sourceKey = source.key) })
        }
    override fun download(source: DistributionSource, apk: ReleaseApk, target: File, allowed: () -> Unit) =
        api.download(ReleaseLink(source.repository, null, null, true), apk, target, allowed)
}

/** Forgejo v1 API adapter. Tokens are scoped to one normalized server and are never sent to redirects. */
internal class ForgejoProvider(
    private val token: (String) -> String?,
    private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
) : ReleaseProvider {
    private fun api(source: DistributionSource) = normalizeServer(source.server) + "/api/v1/repos/" + source.repository

    private fun <T> read(source: DistributionSource, endpoint: String, binary: Boolean, consume: (InputStream) -> T): T {
        val expectedOrigin = URI(normalizeServer(source.server))
        val url = URL(endpoint)
        if (url.protocol != "https" || !url.host.equals(expectedOrigin.host, true) || url.port !in listOf(-1, 443) ||
            url.userInfo != null || !url.path.startsWith(expectedOrigin.path.trimEnd('/') + "/api/v1/"))
            throw ImportFailure("Forgejo APIの送信先を検証できません。")
        val connection = connect(url)
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            connection.useCaches = false
            token(source.authKey.ifBlank { source.key })?.let { connection.setRequestProperty("Authorization", "token $it") }
            connection.setRequestProperty("Accept", if (binary) "application/octet-stream" else "application/json")
            return when (val status = connection.responseCode) {
                200 -> connection.inputStream.use(consume)
                in 300..399 -> throw ImportFailure("Forgejo APIの転送を拒否しました。認証情報は別originへ転送しません。")
                401 -> throw ImportFailure("Forgejo認証が拒否されました。PATの期限とread権限を確認してください。")
                403 -> throw ImportFailure("Forgejoへのアクセスが拒否されました。read:repository権限と対象repoを確認してください。")
                404 -> throw ImportFailure("ForgejoのrepoまたはRelease Assetを確認できません。不存在と権限不足は区別できません。")
                429 -> throw ImportFailure("Forgejo API利用制限です。時間をおいて再試行してください。")
                else -> throw ImportFailure("Forgejoから取得できません（HTTP $status）。")
            }
        } finally { connection.disconnect() }
    }

    private fun json(source: DistributionSource, endpoint: String): String = read(source, endpoint, false) {
        val out = ByteArrayOutputStream(); transfer(it, out, 8L * 1024 * 1024); out.toString("UTF-8")
    }

    override fun history(source: DistributionSource): List<StoreRelease> = buildList {
        var page = 1
        while (true) {
            val rows = JSONArray(json(source, "${api(source)}/releases?limit=50&page=$page"))
            for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index)
                if (row.optBoolean("draft")) continue
                val assets = row.optJSONArray("assets") ?: JSONArray()
                val apks = (0 until assets.length()).mapNotNull { assetIndex ->
                    val asset = assets.getJSONObject(assetIndex)
                    val name = asset.optString("name")
                    val size = asset.optLong("size")
                    if (!name.endsWith(".apk", true) || size !in 1..MAX_INPUT) null else
                        ReleaseApk(asset.getLong("id"), name, size, null, asset.optString("created_at"), source.key,
                            asset.optString("browser_download_url"))
                }
                if (apks.isNotEmpty()) add(StoreRelease(row.getLong("id"), row.getString("tag_name"),
                    row.optString("published_at", row.optString("created_at")), row.optBoolean("prerelease"), apks,
                    row.optString("target_commitish"), source.key))
            }
            if (rows.length() < 50) break
            if (page++ >= 200) throw ImportFailure("Forgejo Release履歴が上限を超えています。")
        }
    }.sortedByDescending { it.published }

    override fun download(source: DistributionSource, apk: ReleaseApk, target: File, allowed: () -> Unit) {
        if (apk.size !in 1..MAX_INPUT) throw ImportFailure("APKがサイズ上限外です。")
        val sha = MessageDigest.getInstance("SHA-256")
        allowed()
        val url = URL(apk.downloadUrl)
        val origin = URI(normalizeServer(source.server))
        val basePath = origin.path.trimEnd('/')
        val repositoryPath = source.repository.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        val rawPath = runCatching { url.toURI().rawPath }.getOrNull().orEmpty()
        val allowedPaths = listOf(
            "$basePath/attachments/",
            "$basePath/$repositoryPath/releases/download/",
            "$basePath/api/v1/repos/$repositoryPath/releases/assets/"
        )
        if (url.protocol != "https" || !url.host.equals(origin.host, true) || url.port !in listOf(-1,443) ||
            url.userInfo != null || rawPath.contains("\\") || rawPath.split('/').any { it in listOf(".", "..") } ||
            allowedPaths.none(rawPath::startsWith))
            throw ImportFailure("Forgejo APKの取得URLを検証できません。")
        val connection = connect(url)
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            connection.useCaches = false
            token(source.authKey.ifBlank { source.key })?.let { connection.setRequestProperty("Authorization", "token $it") }
            connection.setRequestProperty("Accept", "application/octet-stream")
            if (connection.responseCode != 200) throw ImportFailure("Forgejo APKを取得できません（HTTP ${connection.responseCode}）。")
            connection.inputStream.use { input ->
            val guarded = object : java.io.FilterInputStream(input) {
                override fun read(b: ByteArray, off: Int, len: Int): Int { allowed(); return super.read(b, off, len) }
                override fun read(): Int { allowed(); return super.read() }
            }
            val count = target.outputStream().use { transfer(DigestInputStream(guarded, sha), it, apk.size) }
            if (count != apk.size) throw ImportFailure("Forgejo APKの取得が途中で終了しました。")
            val digest = sha.digest().joinToString("") { "%02x".format(it) }
            if (apk.digest != null && !digest.equals(apk.digest, true))
                throw ImportFailure("Forgejo APKのSHA-256が確認済み内容と一致しません。")
            }
        } finally { connection.disconnect() }
    }

}

internal data class SourceCandidate(
    val source: DistributionSource,
    val release: StoreRelease,
    val asset: ReleaseApk,
    val info: ApkInfo
)

internal data class SourceResolution(val selected: SourceCandidate?, val conflict: String = "", val partial: Boolean = false)

/** Version is authoritative; priority only breaks ties. Equal code with unequal/unknown bytes stops automatically. */
internal fun resolveSources(sources: List<DistributionSource>, previews: Boolean): SourceResolution {
    val candidates = sources.filter { it.approved }.mapNotNull { source ->
        val release = source.history.firstOrNull { previews || !it.preview } ?: return@mapNotNull null
        val asset = release.assets.singleOrNull() ?: return@mapNotNull null
        val info = source.inspected[asset.identity()] ?: return@mapNotNull null
        SourceCandidate(source, release, asset, info)
    }
    if (candidates.isEmpty()) return SourceResolution(null, partial = sources.any { it.error.isNotEmpty() })
    val appIdentities = candidates.map { it.info.pkg to it.info.signer }.distinct()
    if (appIdentities.size != 1 || appIdentities.single().second.isBlank())
        return SourceResolution(null, "配布元間のpackageNameまたは署名が一致しません。")
    val maxCode = candidates.maxOf { it.info.code }
    val latest = candidates.filter { it.info.code == maxCode }
    val digests = latest.map { it.asset.digest?.lowercase() }.distinct()
    if (latest.size > 1 && (digests.any { it.isNullOrBlank() } || digests.size != 1))
        return SourceResolution(null, "同じversionCodeのAPK内容を配布元間で確認できません。自動取得を停止しました。")
    return SourceResolution(latest.minBy { it.source.priority }, partial = sources.any { it.error.isNotEmpty() })
}

internal data class ForgejoRepoPage(val repos: List<RemoteRepo>, val more: Boolean)

internal class ForgejoDiscovery(
    private val server: String,
    private val owner: String,
    private val token: String,
    private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
) {
    private val base = normalizeServer(server)
    init {
        if (!owner.matches(Regex("[A-Za-z0-9_.-]{1,100}")) || owner in listOf(".", ".."))
            throw ImportFailure("Forgejoのowner名を確認してください。")
    }

    fun page(page: Int): RepoPage {
        val url = URL("$base/api/v1/user/repos?limit=50&page=$page")
        val connection = connect(url)
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            connection.useCaches = false
            connection.setRequestProperty("Authorization", "token $token")
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            if (status != 200) throw ImportFailure("Forgejo repo列挙に失敗しました（HTTP $status）。read:userとread:repository権限を確認してください。")
            val rows = JSONArray(connection.inputStream.use { input ->
                val out = ByteArrayOutputStream(); transfer(input, out, 8L * 1024 * 1024); out.toString("UTF-8")
            })
            val repos = (0 until rows.length()).mapNotNull { index ->
                val row = rows.getJSONObject(index)
                val full = remoteRepoName(row.getString("full_name"))
                if (!full.substringBefore('/').equals(owner, true)) null else RemoteRepo(row.getLong("id"), full)
            }
            val more = rows.length() == 50 || connection.getHeaderField("Link").orEmpty().contains("rel=\"next\"")
            return RepoPage(repos, more)
        } finally { connection.disconnect() }
    }

    fun repository(raw: String): RemoteRepo {
        rejectCredentialUrl(raw)
        val name = if (raw.trim().startsWith("https://")) {
            val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: throw ImportFailure("Forgejo repo URLを確認してください。")
            val root = URI(base)
            if (!uri.host.equals(root.host, true) || uri.port !in listOf(-1,443) || uri.rawUserInfo != null ||
                uri.rawQuery != null || uri.rawFragment != null || !uri.rawPath.startsWith(root.rawPath.trimEnd('/') + "/"))
                throw ImportFailure("登録済みForgejo接続先のrepo URLを指定してください。")
            remoteRepoName(uri.rawPath.removePrefix(root.rawPath.trimEnd('/')).trim('/').split('/').take(2).joinToString("/"))
        } else remoteRepoName(raw.trim())
        if (!name.substringBefore('/').equals(owner, true)) throw ImportFailure("設定したowner配下のrepoを指定してください。")
        val connection = connect(URL("$base/api/v1/repos/$name"))
        try {
            connection.instanceFollowRedirects = false; connection.connectTimeout = 10000; connection.readTimeout = 15000
            connection.useCaches = false; connection.setRequestProperty("Authorization", "token $token")
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            if (status != 200) throw ImportFailure("Forgejo repoを確認できません（HTTP $status）。read:repository権限を確認してください。")
            val out = ByteArrayOutputStream(); connection.inputStream.use { transfer(it, out, 2L * 1024 * 1024) }
            val row = JSONObject(out.toString("UTF-8"))
            val actual = remoteRepoName(row.getString("full_name"))
            if (actual != name) throw ImportFailure("Forgejo repoの同一性を確認できません。")
            return RemoteRepo(row.getLong("id"), actual)
        } finally { connection.disconnect() }
    }
}
