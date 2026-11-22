package jp.xiaqui.artifact

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipFile

class ImportFailure(message: String) : IOException(message)

internal const val MAX_INPUT = 128L * 1024 * 1024
private const val MAX_EXPANDED = 384L * 1024 * 1024

internal fun transfer(input: InputStream, out: OutputStream?, limit: Long, crc: CRC32? = null): Long {
    val buffer = ByteArray(32768)
    var count = 0L
    while (true) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        val n = input.read(buffer)
        if (n < 0) return count
        count += n
        if (count > limit) throw ImportFailure("ファイルのサイズ上限を超えました。")
        crc?.update(buffer, 0, n)
        out?.write(buffer, 0, n)
    }
}

internal class Archive(private val file: File) {
    init { checkDirectory(file) }
    data class Index(val standalone: Boolean, val choices: List<String>)

    fun inspect(): Index = ZipFile(file).use { zip ->
        if (zip.size() > 16000) throw ImportFailure("ZIP内の項目が多すぎます。")
        val names = HashSet<String>()
        val choices = mutableListOf<String>()
        var expanded = 0L
        var manifest = false
        for (entry in zip.entries()) {
            val name = entry.name
            val parts = name.removeSuffix("/").split('/')
            if (name.length > 1024 || parts.any { it.isBlank() || it == "." || it == ".." } ||
                name.any { it == '\\' || it == ':' || Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() } ||
                !names.add(name)) throw ImportFailure("ZIP内の項目名が不正です。")
            if (entry.size !in 0..(MAX_EXPANDED - expanded)) throw ImportFailure("展開サイズが大きすぎます。")
            val crc = CRC32()
            val count = zip.getInputStream(entry).use { transfer(it, null, entry.size, crc) }
            if (count != entry.size || crc.value != entry.crc) throw ImportFailure("ZIPが破損しています。")
            expanded += count
            if (!entry.isDirectory) {
                if (name == "AndroidManifest.xml") manifest = true
                if (name.endsWith(".apk", true)) {
                    if (choices.size >= 256) throw ImportFailure("APK候補が多すぎます。")
                    choices += name
                }
            }
        }
        if (!manifest && choices.isEmpty()) throw ImportFailure("ZIPにAPKがありません。")
        Index(manifest, choices)
    }

    fun extract(name: String, to: File) = ZipFile(file).use { zip ->
        val entry = zip.getEntry(name) ?: throw ImportFailure("選択したAPKがありません。")
        zip.getInputStream(entry).use { input -> to.outputStream().use { transfer(input, it, MAX_INPUT) } }
    }
}

internal class ApkStore(private val dir: File) {
    fun save(staged: File, name: String, pkg: String? = null, enforceFilenamePackageLimit: Boolean = true): File = synchronized(lock) {
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException()
        val stem = name.substringAfterLast('/').dropLast(4).replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(60).trim('.').ifEmpty { "artifact" }
        val prefix = pkg?.replace(Regex("[^A-Za-z0-9_.]"), "_")?.take(90)?.plus("~").orEmpty()
        val files = dir.listFiles().orEmpty().filter { it.isFile }
        if (files.size >= 24 || files.sumOf { it.length() } + staged.length() > 512L * 1024 * 1024 ||
            (enforceFilenamePackageLimit && prefix.isNotEmpty() && files.count { it.name.startsWith(prefix) } >= 3))
            throw ImportFailure("保存上限です。「保存したAPK」で不要な世代を削除して再試行してください。")
        val target = File(dir, "$prefix$stem-${UUID.randomUUID()}.apk")
        if (!staged.renameTo(target)) throw ImportFailure("APKの保存に失敗しました。")
        target
    }
    companion object { private val lock = Any() }
}
