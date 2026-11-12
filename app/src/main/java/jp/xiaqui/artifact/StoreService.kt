package jp.xiaqui.artifact

import android.content.Context
import java.io.File
import org.json.JSONObject

internal fun storeError(e: Exception) = when {
    e is ImportFailure -> e.message ?: "APK取得失敗"
    e is java.util.zip.ZipException -> "破損APKです。"
    e is java.net.SocketTimeoutException || e is java.net.UnknownHostException -> "通信失敗。接続を確認し再試行してください。"
    e is java.io.IOException && e.message.orEmpty().contains("ENOSPC") -> "端末の空き容量が不足しています。"
    e is java.io.IOException -> "通信・ファイル読取に失敗しました。再試行してください。"
    else -> "APKまたはRelease情報を読み取れません。"
}

internal class StoreService(private val context: Context, val catalog: Catalog,
    private val api: GitHubReleases = GitHubReleases(processAuth)) {
    val dir = File(context.filesDir, "apks")
    private val ledger = context.getSharedPreferences("saved-release-v1", Context.MODE_PRIVATE)
    private val temp = File(context.cacheDir, "store-imports")
    init {
        synchronized(tempLock) {
            if (!cleaned) { temp.deleteRecursively(); cleaned = true }
            temp.mkdirs()
        }
    }
    private fun <T> fetch(r: Registration, asset: ReleaseApk, apply: (File, ApkInfo) -> T): T {
        if (temp.usableSpace < asset.size + 4L * 1024 * 1024) throw ImportFailure("端末の空き容量が不足しています。")
        val staged = File.createTempFile("release-", ".apk", temp)
        try {
            api.download(r.link, asset, staged)
            checkReleaseApk(staged)
            val info = Installation.describe(context, staged)
            if (info.signer.isEmpty()) throw ImportFailure("APKの署名を取得できません。")
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            return apply(staged, info)
        } finally { staged.delete() }
    }

    fun refresh(r: Registration) {
        try {
            val history = api.history(r.link)
            val known = r.inspected.toMutableMap()
            val latest = history.firstOrNull { r.previews || !it.preview }
            val errors = mutableListOf<String>()
            for (a in latest?.assets.orEmpty().filter { it.identity() !in known }.take(4)) {
                try { known[a.identity()] = fetch(r, a) { _, meta -> meta } }
                catch (e: Exception) {
                    if (e is InterruptedException) throw e
                    errors += "${a.name}: ${storeError(e)}"
                }
            }
            val retained = history.flatMap { it.assets }.map { it.identity() }.toSet()
            catalog.update(r) { it.copy(history = history, fetched = System.currentTimeMillis(),
                error = errors.joinToString("\n"), inspected = (it.inspected + known).filterKeys { k -> k in retained }) }
        } catch (e: Exception) {
            if (e is InterruptedException) throw e
            catalog.update(r) { it.copy(error = storeError(e)) }
        }
    }

    fun inspect(r: Registration, a: ReleaseApk): ApkInfo = fetch(r, a) { _, info ->
        catalog.update(r) { it.copy(inspected = it.inspected + (a.identity() to info)) }
        info
    }

    fun saved(r: Registration, a: ReleaseApk): File? = dir.listFiles().orEmpty().firstOrNull { file ->
        val record = ledger.getString(file.name, null)?.let(::JSONObject)
        record?.optString("repo") == r.repo && record.optString("asset") == a.identity()
    }
    fun save(r: Registration, release: StoreRelease, a: ReleaseApk): File {
        saved(r, a)?.let { return it }
        return fetch(r, a) { staged, info ->
            val expected = r.inspected[a.identity()]
            if (expected != null && (expected.pkg != info.pkg || expected.code != info.code || expected.signer != info.signer))
                throw ImportFailure("解析済みAPKと取得したpackageName・versionCode・署名が異なります。Release Assetを確認してください。")
            catalog.update(r) { it.copy(inspected = it.inspected + (a.identity() to info)) }
            synchronized(tempLock) {
                saved(r, a) ?: run {
                    val file = ApkStore(dir).save(staged, a.name, info.pkg)
                    val record = JSONObject().put("repo", r.repo).put("tag", release.tag).put("asset", a.identity())
                        .put("package", info.pkg).put("versionCode", info.code).put("versionName", info.version)
                        .put("name", a.name).put("savedAt", System.currentTimeMillis())
                    if (!ledger.edit().putString(file.name, record.toString()).commit()) {
                        file.delete()
                        throw ImportFailure("保存履歴を記録できません。")
                    }
                    file
                }
            }
        }
    }
    fun provenance(file: File): String {
        val j = ledger.getString(file.name, null)?.let(::JSONObject) ?: return file.name
        return "${j.getString("name")} · ${j.getString("repo")} / ${j.getString("tag")}\n${j.getString("package")} ${j.getString("versionName")} (${j.getLong("versionCode")})"
    }
    fun delete(file: File) = synchronized(tempLock) {
        if (!file.delete()) throw ImportFailure("ファイルを削除できません。")
        ledger.edit().remove(file.name).apply()
    }
    companion object { private val tempLock = Any(); private var cleaned = false }
}
