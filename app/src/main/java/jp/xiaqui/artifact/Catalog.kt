package jp.xiaqui.artifact

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.util.UUID

internal data class StoreRelease(val id: Long, val tag: String, val published: String,
    val preview: Boolean, val assets: List<ReleaseApk>, val commit: String = "", val sourceKey: String = "github")
internal data class ApkInfo(val pkg: String, val label: String, val version: String, val code: Long, val signer: String, val sdk: ApkSdk = ApkSdk(), val sdkChecked: Boolean = false) {
    val summary get() = "$label · $pkg\n$version ($code)"
}
internal data class DistributionSource(
    val key: String,
    val provider: String,
    val server: String,
    val repositoryId: Long,
    val repository: String,
    val priority: Int,
    val authKey: String = "",
    val approved: Boolean = true,
    val fetched: Long = 0,
    val checked: Long = 0,
    val error: String = "",
    val history: List<StoreRelease> = emptyList(),
    val inspected: Map<String, ApkInfo> = emptyMap()
)

internal data class Registration(val repo: String, val previews: Boolean, val revision: String,
    val fetched: Long = 0, val error: String = "", val history: List<StoreRelease> = emptyList(),
    val inspected: Map<String, ApkInfo> = emptyMap(), val checked: Long = 0, val githubId: Long = 0,
    val sources: List<DistributionSource> = emptyList()) {
    val link get() = ReleaseLink(repo, null, null, true)
    val visible get() = history.filter { previews || !it.preview }
}
internal fun ReleaseApk.identity() = "${sourceKey.ifBlank { "github" }}:$id:$size:${digest.orEmpty()}:$updated"

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
    .put("code", code).put("signer", signer).put("sdkChecked",sdkChecked).put("minSdk",sdk.min).put("targetSdk",sdk.target).put("maxSdk",sdk.max)
private fun metadata(j: JSONObject) = ApkInfo(j.getString("package"), j.getString("label"),
    j.getString("version"), j.getLong("code"), j.getString("signer"), ApkSdk(j.optInt("minSdk"),j.optInt("targetSdk"),j.optInt("maxSdk")),j.optBoolean("sdkChecked"))

private fun releaseJson(release: StoreRelease): JSONObject {
    val assets = JSONArray()
    release.assets.forEach { a -> assets.put(JSONObject().put("id", a.id).put("name", a.name)
        .put("size", a.size).put("digest", a.digest).put("updated", a.updated).put("sourceKey", a.sourceKey)
        .put("downloadUrl", a.downloadUrl)) }
    return JSONObject().put("id", release.id).put("tag", release.tag).put("published", release.published)
        .put("commit", release.commit).put("preview", release.preview).put("sourceKey", release.sourceKey).put("assets", assets)
}

private fun release(j: JSONObject): StoreRelease {
    val sourceKey = j.optString("sourceKey", "github")
    return StoreRelease(j.getLong("id"), j.getString("tag"), j.getString("published"), j.getBoolean("preview"),
        j.getJSONArray("assets").mapRows { a -> ReleaseApk(a.getLong("id"), a.getString("name"),
            a.getLong("size"), a.optString("digest").ifEmpty { null }, a.optString("updated"),
            a.optString("sourceKey", sourceKey), a.optString("downloadUrl")) }, j.optString("commit"), sourceKey)
}

internal object CatalogCodec {
    fun encode(r: Registration): String {
        val histories = JSONArray()
        r.history.forEach { histories.put(releaseJson(it)) }
        val inspected = JSONObject()
        r.inspected.forEach { (key, info) -> inspected.put(key, info.json()) }
        val sources = JSONArray()
        r.sources.forEach { source ->
            val sourceHistory = JSONArray(); source.history.forEach { sourceHistory.put(releaseJson(it)) }
            val sourceInspected = JSONObject(); source.inspected.forEach { (key, info) -> sourceInspected.put(key, info.json()) }
            sources.put(JSONObject().put("key", source.key).put("provider", source.provider).put("server", source.server)
                .put("repositoryId", source.repositoryId).put("repository", source.repository).put("priority", source.priority)
                .put("authKey", source.authKey)
                .put("approved", source.approved).put("fetched", source.fetched).put("checked", source.checked)
                .put("error", source.error).put("history", sourceHistory).put("inspected", sourceInspected))
        }
        return JSONObject().put("repo", r.repo).put("previews", r.previews).put("revision", r.revision)
            .put("githubId", r.githubId).put("checked", r.checked).put("fetched", r.fetched).put("error", r.error)
            .put("history", histories).put("inspected", inspected).put("sources", sources).toString()
    }
    fun decode(raw: String): Registration {
        val j = JSONObject(raw)
        val inspected = j.getJSONObject("inspected")
        return Registration(j.getString("repo"), j.getBoolean("previews"), j.getString("revision"),
            j.getLong("fetched"), j.getString("error"), j.getJSONArray("history").mapRows(::release),
            inspected.keys().asSequence().associateWith { metadata(inspected.getJSONObject(it)) }, j.optLong("checked", 0), j.optLong("githubId", 0),
            j.optJSONArray("sources")?.mapRows { s ->
                val sourceInspected = s.optJSONObject("inspected") ?: JSONObject()
                DistributionSource(s.getString("key"), s.getString("provider"), s.getString("server"),
                    s.getLong("repositoryId"), s.getString("repository"), s.getInt("priority"), s.optString("authKey"), s.optBoolean("approved", true),
                    s.optLong("fetched"), s.optLong("checked"), s.optString("error"),
                    s.optJSONArray("history")?.mapRows(::release).orEmpty(),
                    sourceInspected.keys().asSequence().associateWith { metadata(sourceInspected.getJSONObject(it)) })
            }.orEmpty())
    }
}

internal val repoDataLock = Any()

internal class Catalog(context: Context) {
    private val prefs = context.getSharedPreferences("store-v1", Context.MODE_PRIVATE)
    fun get(repo: String) = prefs.getString(repo, null)?.let(CatalogCodec::decode)
    fun names() = prefs.all.keys.filter { !it.startsWith("@") }.sorted()
    fun canonical(repo: String): String = synchronized(lock) {
        val aliases = JSONObject(prefs.getString("@aliases", "{}")!!)
        aliases.optString(repo, repo)
    }
    fun rememberedId(repo: String): Long = JSONObject(prefs.getString("@identities", "{}")!!).optLong(repo, 0)
    private fun legacySource(r: Registration): DistributionSource {
        val key = sourceIdentity(PROVIDER_GITHUB, "https://github.com", r.githubId).key
        val history = r.history.map { release -> release.copy(sourceKey = key,
            assets = release.assets.map { it.copy(sourceKey = key) }) }
        val inspected = mutableMapOf<String, ApkInfo>()
        r.history.flatMap { it.assets }.forEach { old ->
            val info = r.inspected[old.identity()] ?: r.inspected[old.identity().removePrefix("github:")]
            if (info != null) inspected[old.copy(sourceKey = key).identity()] = info
        }
        return DistributionSource(key, PROVIDER_GITHUB, "https://github.com", r.githubId, r.repo, 100,
            fetched = r.fetched, checked = r.checked, error = r.error, history = history, inspected = inspected)
    }

    fun sources(r: Registration): List<DistributionSource> = if (r.sources.isNotEmpty()) r.sources else listOf(legacySource(r))

    fun findApp(pkg: String, signer: String): Registration? = names().mapNotNull(::get).firstOrNull { registration ->
        sources(registration).flatMap { it.inspected.values }.any { it.pkg == pkg && it.signer.isNotBlank() && it.signer == signer }
    }

    fun addSource(existing: Registration?, source: DistributionSource, app: ApkInfo, previews: Boolean = true): Registration = synchronized(lock) {
        requireIdle()
        if (app.signer.isBlank()) throw ImportFailure("APKの署名を確認できない配布元は統合できません。")
        val target = existing ?: findApp(app.pkg, app.signer)
        if (target != null) {
            val current = get(target.repo) ?: throw ImportFailure("統合先が変更されました。候補を再取得してください。")
            val rows = sources(current)
            if (rows.any { it.key == source.key }) return@synchronized current
            if (rows.flatMap { it.inspected.values }.any { it.pkg == app.pkg && it.signer.isNotBlank() && it.signer != app.signer })
                throw ImportFailure("同じpackageNameの署名が一致しないため統合を保留しました。")
            val updated = current.copy(revision = UUID.randomUUID().toString(), sources = rows + source)
            if (!prefs.edit().putString(current.repo, CatalogCodec.encode(updated)).commit())
                throw ImportFailure("配布元を保存できません。既存登録は保持しています。")
            return@synchronized updated
        }
        val key = "app-" + MessageDigest.getInstance("SHA-256")
            .digest((app.pkg + "\u0000" + app.signer).toByteArray()).take(12).joinToString("") { "%02x".format(it) }
        val prior = get(key)
        if (prior != null) return@synchronized prior
        val created = Registration(key, previews, UUID.randomUUID().toString(), sources = listOf(source))
        if (!prefs.edit().putString(key, CatalogCodec.encode(created)).commit())
            throw ImportFailure("Forgejoアプリ登録を保存できません。")
        created
    }

    fun replaceSourceState(r: Registration, source: DistributionSource): Registration = synchronized(lock) {
        val current = get(r.repo) ?: throw ImportFailure("登録が変更されました。再試行してください。")
        val rows = sources(current).map { if (it.key == source.key) source else it }
        val resolved = resolveSources(rows, current.previews)
        val selected = resolved.selected
        val error = when {
            resolved.conflict.isNotEmpty() -> resolved.conflict
            selected == null && rows.all { it.error.isNotEmpty() } -> rows.joinToString("\n") { "${it.repository}: ${it.error}" }
            else -> ""
        }
        val updated = current.copy(
            revision = UUID.randomUUID().toString(), sources = rows,
            history = selected?.let { candidate -> listOf(candidate.release) + candidate.source.history.filter { it.id != candidate.release.id } }.orEmpty(),
            inspected = selected?.source?.inspected.orEmpty(),
            fetched = rows.maxOfOrNull { it.fetched } ?: current.fetched,
            checked = rows.maxOfOrNull { it.checked } ?: current.checked,
            error = error
        )
        if (!prefs.edit().putString(current.repo, CatalogCodec.encode(updated)).commit())
            throw ImportFailure("配布元の確認結果を保存できません。以前の結果は保持しています。")
        updated
    }

    fun reorderSources(r: Registration, orderedKeys: List<String>): Registration = synchronized(lock) {
        val current = get(r.repo) ?: throw ImportFailure("登録が変更されました。")
        val rows = sources(current)
        if (orderedKeys.toSet() != rows.map { it.key }.toSet()) throw ImportFailure("配布元一覧が変わりました。開き直してください。")
        val reordered = orderedKeys.mapIndexed { index, key -> rows.first { it.key == key }.copy(priority = index) }
        val updated = current.copy(revision = UUID.randomUUID().toString(), sources = reordered)
        if (!prefs.edit().putString(current.repo, CatalogCodec.encode(updated)).commit()) throw ImportFailure("優先順位を保存できません。")
        updated
    }

    fun removeSource(r: Registration, key: String): Registration = synchronized(lock) {
        val current = get(r.repo) ?: throw ImportFailure("登録が変更されました。")
        val rows = sources(current)
        if (rows.count { it.approved } <= 1 && rows.firstOrNull { it.key == key }?.approved == true)
            throw ImportFailure("最後の配布元は解除できません。アプリ登録を削除してください。")
        val remaining = rows.map { if (it.key == key) it.copy(approved = false) else it }
        val resolved = resolveSources(remaining, current.previews)
        val selected = resolved.selected
        val updated = current.copy(revision = UUID.randomUUID().toString(), sources = remaining,
            history = selected?.source?.history.orEmpty(), inspected = selected?.source?.inspected.orEmpty(),
            error = resolved.conflict)
        if (!prefs.edit().putString(current.repo, CatalogCodec.encode(updated)).commit())
            throw ImportFailure("配布元を解除できません。既存設定は保持しています。")
        updated
    }

    fun approveSource(r: Registration, key: String): Registration = synchronized(lock) {
        val current = get(r.repo) ?: throw ImportFailure("登録が変更されました。")
        val rows = sources(current).map { if (it.key == key) it.copy(approved = true) else it }
        if (rows.none { it.key == key }) throw ImportFailure("配布元が見つかりません。")
        val updated = current.copy(revision = UUID.randomUUID().toString(), sources = rows)
        if (!prefs.edit().putString(current.repo, CatalogCodec.encode(updated)).commit()) throw ImportFailure("配布元を再承認できません。")
        updated
    }
    fun validateMigration(r: Registration, name: String, id: Long) = synchronized(lock) {
        requireIdle()
        if (get(r.repo) != r) throw ImportFailure("確認中に登録が変わりました。編集を開き直してください。")
        if (name != r.repo && get(name) != null) throw ImportFailure("変更先は登録済みです。双方を保持しました。重複登録を解決して再試行してください。")
        if (listOf(r.repo, name).any { rememberedId(it) > 0 && rememberedId(it) != id })
            throw ImportFailure("以前の改名履歴とGitHub IDが異なります。名前が再利用された可能性があります。双方を保持して移行を保留します。")
        if (canonical(name) != name && canonical(name) != r.repo)
            throw ImportFailure("変更先は他の改名履歴と衝突しています。双方を保持しました。")
    }
    fun pendingAuth(): Pair<String, String>? = prefs.getString("@authCleanup", null)?.let {
        val j = JSONObject(it); j.getString("old") to j.getString("new")
    }
    fun authCleaned() {
        if (!prefs.edit().remove("@authCleanup").commit()) throw ImportFailure("移行の完了記録を保存できません。再試行してください。")
    }
    fun requireIdle() {
        if (pendingAuth() != null) throw ImportFailure("認証移行の完了待ちです。登録編集から同じ名前で保存し、再試行してください。")
    }
    /** Import adapters must use this before adding an enumerated repository. Never replace existing settings. */
    fun registeredIdentity(id: Long, name: String): Registration? = synchronized(lock) {
        val byName = get(canonical(repoName(name)))
        if (byName != null && byName.githubId > 0 && byName.githubId != id)
            throw ImportFailure("同名のGitHub IDが異なります。既存登録を保持します。")
        names().mapNotNull(::get).firstOrNull { id > 0 && it.githubId == id } ?: byName
    }
    /** Observe an enumerated repository without changing its settings or moving its key. */
    fun observeRemote(remote: RemoteRepo): ImportChange? = synchronized(lock) {
        requireIdle()
        val name = remoteRepoName(remote.name)
        val existing = registeredIdentity(remote.id, name) ?: return@synchronized null
        // Enumeration does not prove the historic identity of an ID-less manual entry.
        // Keep it by name without guessing or persisting the newly observed ID.
        if (existing.repo == name) ImportChange.EXISTING else ImportChange.RENAMED
    }
    /** Add only after the caller has proved that a published APK Release exists. */
    fun addRemote(remote: RemoteRepo): ImportChange = synchronized(lock) {
        observeRemote(remote)?.let { return@synchronized it }
        register(null, remoteRepoName(remote.name), true, remote.id)
        ImportChange.ADDED
    }
    fun migrate(r: Registration, name: String, id: Long, previews: Boolean): Registration = synchronized(lock) {
        validateMigration(r, name, id)
        val current = get(r.repo)
        if (current?.revision != r.revision) throw ImportFailure("確認中に登録が変わりました。編集を開き直してください。")
        if (get(name) != null && name != r.repo) throw ImportFailure("変更先は登録済みです。双方を保持しました。保存内容を確認して重複登録を手動で解決してください。")
        if (canonical(name) != name && canonical(name) != r.repo) throw ImportFailure("変更先は他の改名履歴と衝突しています。双方を保持しました。")
        val updated = current.copy(repo = name, githubId = id, previews = previews, revision = UUID.randomUUID().toString())
        val aliases = JSONObject(prefs.getString("@aliases", "{}")!!)
        aliases.keys().asSequence().toList().forEach { key -> if (aliases.getString(key) == r.repo) aliases.put(key, name) }
        aliases.remove(name)
        if (r.repo != name) aliases.put(r.repo, name)
        val identities = JSONObject(prefs.getString("@identities", "{}")!!).put(r.repo, id).put(name, id)
        val edit = prefs.edit().putString("@identities", identities.toString()).remove(r.repo).putString(name, CatalogCodec.encode(updated)).putString("@aliases", aliases.toString())
        if (r.repo != name) edit.putString("@authCleanup", JSONObject().put("old", r.repo).put("new", name).toString())
        if (!edit.commit()) throw ImportFailure("移行を保存できません。再起動して登録を確認してください。旧認証とAPKは保持しています。")
        updated
    }
    fun register(old: String?, raw: String, previews: Boolean, identity: Long = 0): Registration = synchronized(lock) {
        requireIdle()
        val repo = repoName(raw)
        if (old != null && repo != old) throw ImportFailure("改名にはGitHub IDの確認が必要です。登録編集から再試行してください。")
        if (canonical(repo) != repo) throw ImportFailure("この名前は移行済みです。現在の登録「${canonical(repo)}」を開いてください。")
        if (old == null && rememberedId(repo) > 0 && identity != rememberedId(repo))
            throw ImportFailure("過去の改名情報があるためGitHub IDの再確認が必要です。登録追加から再試行してください。別IDなら旧履歴保護のため追加できません。")
        if (identity > 0 && names().mapNotNull(::get).any { it.githubId == identity && it.repo != repo })
            throw ImportFailure("同じGitHub IDの登録があります。既存登録を編集してください。")
        if (repo != old && get(repo) != null) throw ImportFailure("登録済みのリポジトリです。")
        val prior = if (repo == old) get(repo) else null
        val revision = UUID.randomUUID().toString()
        val result = prior?.copy(previews = previews, revision = revision) ?: Registration(repo, previews, revision, githubId = identity)
        val change = prefs.edit()
        if (old != null && old != repo) change.remove(old)
        if (!change.putString(repo, CatalogCodec.encode(result)).commit()) throw ImportFailure("登録を保存できません。")
        result
    }
    fun update(r: Registration, change: (Registration) -> Registration) = synchronized(lock) {
        val current = get(r.repo)
        if (current?.revision == r.revision &&
            !prefs.edit().putString(r.repo, CatalogCodec.encode(change(current))).commit())
            throw ImportFailure("カタログを保存できません。")
    }
    fun remove(repo: String) = synchronized(lock) { requireIdle(); prefs.edit().remove(repo).commit() }
    companion object { private val lock = repoDataLock }
}
