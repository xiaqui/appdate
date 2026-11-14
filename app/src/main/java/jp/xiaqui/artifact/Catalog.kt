package jp.xiaqui.artifact

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

internal data class StoreRelease(val id: Long, val tag: String, val published: String,
    val preview: Boolean, val assets: List<ReleaseApk>, val commit: String = "")
internal data class ApkInfo(val pkg: String, val label: String, val version: String, val code: Long, val signer: String) {
    val summary get() = "$label · $pkg\n$version ($code)"
}
internal data class Registration(val repo: String, val previews: Boolean, val revision: String,
    val fetched: Long = 0, val error: String = "", val history: List<StoreRelease> = emptyList(),
    val inspected: Map<String, ApkInfo> = emptyMap(), val checked: Long = 0,
    val githubId: Long = 0, val remoteName: String = "") {
    val link get() = ReleaseLink(repo, null, null, true)
    val visible get() = history.filter { previews || !it.preview }
}

internal fun ReleaseApk.identity() = "$id:$size:${digest.orEmpty()}:$updated"

internal fun repoName(raw: String): String {
    rejectCredentialUrl(raw)
    val value = raw.trim()
    val slug = if (value.startsWith("https://")) {
        val uri = runCatching { URI(value) }.getOrNull() ?: throw ImportFailure("GitHub URLを確認してください。")
        if (!uri.host.equals("github.com", true) || uri.rawUserInfo != null || uri.port != -1)
            throw ImportFailure("github.comのURLを指定してください。")
        uri.rawPath.trim('/').split('/').take(2).joinToString("/")
    } else value
    val parts = slug.split('/')
    if (parts.size != 2 || parts.any { !it.matches(Regex("[A-Za-z0-9_.-]{1,100}")) || it in listOf(".", "..") })
        throw ImportFailure("owner/repo または GitHub URLを指定してください。")
    return slug.lowercase()
}

internal fun compareInstalled(candidate: ApkInfo, installed: ApkInfo?, absenceKnown: Boolean): String = when {
    installed == null -> if (absenceKnown) "未導入" else "判定不能"
    candidate.pkg != installed.pkg -> "判定不能"
    candidate.signer.isBlank() || installed.signer.isBlank() -> "判定不能（署名を取得できません）"
    candidate.signer != installed.signer -> "署名不一致の可能性"
    candidate.code > installed.code -> "更新候補"
    candidate.code == installed.code -> "同一版"
    else -> "旧版（ダウングレード制限）"
}

private fun <T> JSONArray.mapRows(read: (JSONObject) -> T) = (0 until length()).map { read(getJSONObject(it)) }
private fun ApkInfo.json() = JSONObject().put("package", pkg).put("label", label).put("version", version)
    .put("code", code).put("signer", signer)
private fun metadata(j: JSONObject) = ApkInfo(j.getString("package"), j.getString("label"),
    j.getString("version"), j.getLong("code"), j.getString("signer"))

internal object CatalogCodec {
    fun encode(r: Registration): String {
        val histories = JSONArray()
        r.history.forEach { release ->
            val assets = JSONArray()
            release.assets.forEach { a -> assets.put(JSONObject().put("id", a.id).put("name", a.name)
                .put("size", a.size).put("digest", a.digest).put("updated", a.updated)) }
            histories.put(JSONObject().put("id", release.id).put("tag", release.tag).put("published", release.published)
                .put("commit", release.commit).put("preview", release.preview).put("assets", assets))
        }
        val inspected = JSONObject()
        r.inspected.forEach { (key, info) -> inspected.put(key, info.json()) }
        return JSONObject().put("repo", r.repo).put("previews", r.previews).put("revision", r.revision)
            .put("githubId", r.githubId).put("remoteName", r.remoteName).put("checked", r.checked).put("fetched", r.fetched).put("error", r.error).put("history", histories).put("inspected", inspected).toString()
    }
    fun decode(raw: String): Registration {
        val j = JSONObject(raw)
        val inspected = j.getJSONObject("inspected")
        return Registration(j.getString("repo"), j.getBoolean("previews"), j.getString("revision"),
            j.getLong("fetched"), j.getString("error"), j.getJSONArray("history").mapRows { h ->
                StoreRelease(h.getLong("id"), h.getString("tag"), h.getString("published"), h.getBoolean("preview"),
                    h.getJSONArray("assets").mapRows { a -> ReleaseApk(a.getLong("id"), a.getString("name"),
                        a.getLong("size"), a.optString("digest").ifEmpty { null }, a.optString("updated")) }, h.optString("commit"))
            }, inspected.keys().asSequence().associateWith { metadata(inspected.getJSONObject(it)) }, j.optLong("checked", 0), j.optLong("githubId", 0), j.optString("remoteName"))
    }
}

internal class Catalog(context: Context) {
    private val prefs = context.getSharedPreferences("store-v1", Context.MODE_PRIVATE)
    fun get(repo: String) = prefs.getString(repo, null)?.let(CatalogCodec::decode)
    fun names() = prefs.all.keys.sorted()
    fun register(old: String?, raw: String, previews: Boolean): Registration = synchronized(lock) {
        val repo = repoName(raw)
        if (repo != old && get(repo) != null) throw ImportFailure("登録済みのリポジトリです。")
        val prior = if (repo == old) get(repo) else null
        val revision = UUID.randomUUID().toString()
        val result = prior?.copy(previews = previews, revision = revision) ?: Registration(repo, previews, revision)
        val change = prefs.edit()
        if (old != null && old != repo) change.remove(old)
        if (!change.putString(repo, CatalogCodec.encode(result)).commit()) throw ImportFailure("登録を保存できません。")
        result
    }
    fun addRemote(remote: RemoteRepo): ImportChange = synchronized(lock) {
        val name = repoName(remote.name)
        val byId = names().mapNotNull(::get).firstOrNull { it.githubId == remote.id }
        val existing = byId ?: get(name)
        if (existing != null && existing.githubId != 0L && existing.githubId != remote.id)
            throw ImportFailure("同じrepo名の識別IDが変わっています。既存設定を保持しました。登録を確認してください。")
        val result = existing?.copy(githubId = remote.id, remoteName = name)
            ?: Registration(name, true, UUID.randomUUID().toString(), githubId = remote.id, remoteName = name)
        if (!prefs.edit().putString(result.repo, CatalogCodec.encode(result)).commit())
            throw ImportFailure("登録を保存できません。")
        when {
            existing == null -> ImportChange.ADDED
            existing.repo != name -> ImportChange.RENAMED
            else -> ImportChange.EXISTING
        }
    }
    fun update(r: Registration, change: (Registration) -> Registration) = synchronized(lock) {
        val current = get(r.repo)
        if (current?.revision == r.revision &&
            !prefs.edit().putString(r.repo, CatalogCodec.encode(change(current))).commit())
            throw ImportFailure("カタログを保存できません。")
    }
    fun remove(repo: String) = synchronized(lock) { prefs.edit().remove(repo).commit() }
    companion object { private val lock = Any() }
}

