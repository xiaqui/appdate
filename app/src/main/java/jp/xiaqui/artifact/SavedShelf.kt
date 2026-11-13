package jp.xiaqui.artifact

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Keep original filenames and v1 ledger keys; legacy files need no destructive migration. */
internal class SavedShelf(context: Context) {
    val dir = File(context.filesDir, "apks")
    private val ledger = context.getSharedPreferences("saved-release-v1", Context.MODE_PRIVATE)
    fun entries(): List<File> = synchronized(lock) {
        val files = dir.listFiles().orEmpty().filter { it.isFile }
        val missing = ledger.all.keys.filter { it == File(it).name && !File(dir,it).exists() }.map { File(dir,it) }
        (files.sortedByDescending { it.lastModified() } + missing)
    }
    private fun record(file: File) = runCatching { ledger.getString(file.name, null)?.let(::JSONObject) }.getOrNull()

    private fun candidates(repo: String, asset: ReleaseApk) = dir.listFiles().orEmpty().filter { file ->
        val r = record(file)
        val key = r?.optString("asset")
        val oldKey = "${asset.id}:${asset.size}:${asset.digest.orEmpty()}"
        file.isFile && r?.optString("repo") == repo && (key == asset.identity() || key == oldKey)
    }

    fun candidate(repo: String, asset: ReleaseApk) = candidates(repo, asset).firstOrNull()

    private fun hash(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        java.security.DigestInputStream(file.inputStream(), digest).use { transfer(it, null, MAX_INPUT) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun verified(repo: String, asset: ReleaseApk, expected: String? = asset.digest): File? =
        candidates(repo, asset).firstOrNull { file ->
            val sha = expected ?: record(file)?.optString("sha256")?.takeIf { it.length == 64 }
            sha != null && file.length() == asset.size && try { hash(file).equals(sha,true) }
                catch (_: java.io.IOException) { false }
        }

    fun save(staged: File, name: String, info: ApkInfo, source: String, repo: String? = null,
        tag: String? = null, asset: ReleaseApk? = null): File = synchronized(lock) {
        val sha = hash(staged)
        if (repo != null && asset != null) verified(repo,asset,sha)?.let { return@synchronized it }
        val file = ApkStore(dir).save(staged, name, info.pkg)
        val r = JSONObject().put("source",source).put("repo",repo).put("tag",tag).put("asset",asset?.identity())
            .put("name",name).put("package",info.pkg).put("versionCode",info.code).put("versionName",info.version)
            .put("sha256",sha).put("signer",info.signer).put("savedAt",System.currentTimeMillis())
        if (!ledger.edit().putString(file.name,r.toString()).commit())
            throw ImportFailure("APKを保存しました。履歴の書き込みに失敗したため、保存したAPK一覧から再度開いてください。")
        file
    }

    fun describe(file: File): String {
        val r = record(file)
        val origin = r?.optString("source")?.takeIf { it.isNotBlank() }
            ?: if (r?.has("repo") == true) "GitHub Release" else "旧保存分・出所未記録"
        return buildString {
            appendLine(r?.optString("name")?.ifEmpty { null } ?: file.name)
            append(origin)
            if (r?.has("repo") == true) append(" · " + r.optString("repo") + " " + r.optString("tag"))
            append("\n保存 " + StoreTime.local(r?.optLong("savedAt",file.lastModified()) ?: file.lastModified()))
            if (r?.has("package") == true) append("\n" + r.optString("package") + " " + r.optString("versionName") + " (" + r.optLong("versionCode") + ")")
            if (!file.isFile) append("\n実体が見つかりません。履歴を保持しています。")
        }
    }
    fun delete(file: File) = synchronized(lock) {
        if (!file.delete()) throw ImportFailure("ファイルを削除できません。履歴を保持します。")
        ledger.edit().remove(file.name).apply()
    }
    companion object { private val lock = Any() }
}
