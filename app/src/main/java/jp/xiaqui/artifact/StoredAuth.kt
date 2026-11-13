package jp.xiaqui.artifact

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Each repo has its own revocable key. AtomicFile lives outside both backup and device transfer. */
internal class StoredAuth(context: Context) : ReleaseAuth {
    private val root = File(context.noBackupFilesDir, "release-auth")
    internal fun alias(repo: String) = "release-pat-" + MessageDigest.getInstance("SHA-256")
        .digest(repo.lowercase().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    override fun commonToken() = tokenFor(COMMON)
    fun useCommon(token: String) = use(COMMON, token)
    fun forgetCommon() = forget(COMMON)
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun file(id: String) = AtomicFile(File(root, id))
    private fun create(id: String): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(id, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return generator.generateKey()
    }

    override fun tokenFor(repository: String): String? = synchronized(lock) {
        val id = alias(repository)
        val record = try { file(id).openRead().use { it.readBytes() } }
        catch (_: java.io.FileNotFoundException) { return@synchronized null }
        catch (_: Exception) { throw ImportFailure(REENTER) }
        try {
            require(record.size in 29..1024)
            val key = store().getKey(id, null) as? SecretKey ?: throw IllegalStateException()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, record, 0, 12))
            cipher.updateAAD(id.toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(record, 12, record.size - 12), Charsets.UTF_8)
        } catch (_: Exception) {
            forget(repository)
            throw ImportFailure(REENTER)
        }
    }

    fun use(repo: String, token: String) = synchronized(lock) {
        if (!Regex("[A-Za-z0-9_]{1,500}").matches(token)) throw ImportFailure("トークンを確認してください。")
        val id = alias(repo)
        try {
            if (!root.isDirectory && !root.mkdirs()) throw java.io.IOException()
            val key = store().getKey(id, null) as? SecretKey ?: create(id)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(id.toByteArray(Charsets.UTF_8))
            val bytes = cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
            val dest = file(id)
            val out = dest.startWrite()
            try { out.write(bytes); dest.finishWrite(out) }
            catch (e: Exception) { dest.failWrite(out); throw e }
        } catch (_: Exception) { throw ImportFailure("端末の認証を保存できません。認証を解除して再入力してください。") }
    }

    fun forget(repo: String) = synchronized(lock) {
        val id = alias(repo)
        file(id).delete()
        try { store().deleteEntry(id) }
        catch (_: Exception) {
            if (File(root, id).exists() || File(root, "$id.bak").exists()) throw ImportFailure("認証解除に失敗しました。再試行してください。")
        }
    }

    fun clear() = synchronized(lock) {
        try {
            val keys = store()
            keys.aliases().toList().filter { it.startsWith("release-pat-") }.forEach { keys.deleteEntry(it) }
            root.deleteRecursively()
            Unit
        } catch (_: Exception) { throw ImportFailure("認証解除に失敗しました。再試行してください。") }
    }

    companion object {
        private const val COMMON = "@common" // Outside the owner/repo namespace.
        private val lock = Any()
        private const val REENTER = "保存した認証を読み出せません。認証なしに戻りました。PATを再入力してください。"
    }
}
