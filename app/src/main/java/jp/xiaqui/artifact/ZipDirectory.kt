package jp.xiaqui.artifact

import java.io.File
import java.io.RandomAccessFile

/** Bound actual central-directory records before Android's ZipFile allocates per-entry objects. */
internal fun checkDirectory(file: File) {
    fun invalid(): Nothing = throw ImportFailure("ZIPの索引が破損しているか、上限を超えています。")
    fun number(bytes: ByteArray, offset: Int, length: Int): Long {
        var value = 0L
        for (i in 0 until length) value = value or ((bytes[offset + i].toLong() and 255) shl (8 * i))
        return value
    }
    RandomAccessFile(file, "r").use { input ->
        val size = input.length()
        val tail = ByteArray(minOf(size, 65557L).toInt())
        input.seek(size - tail.size)
        input.readFully(tail)
        val end = (tail.size - 22 downTo 0).firstOrNull {
            number(tail, it, 4) == 0x06054b50L && it + 22 + number(tail, it + 20, 2) == tail.size.toLong()
        } ?: invalid()
        val count = number(tail, end + 10, 2)
        val length = number(tail, end + 12, 4)
        val offset = number(tail, end + 16, 4)
        if (number(tail, end + 4, 4) != 0L || number(tail, end + 8, 2) != count ||
            count > 16000 || length > 8L * 1024 * 1024 || offset + length != size - tail.size + end) invalid()
        input.seek(offset)
        val record = ByteArray(46)
        repeat(count.toInt()) {
            if (input.filePointer + record.size > offset + length) invalid()
            input.readFully(record)
            if (number(record, 0, 4) != 0x02014b50L) invalid()
            val name = number(record, 28, 2)
            if (name !in 1..4096) invalid()
            val next = input.filePointer + name + number(record, 30, 2) + number(record, 32, 2)
            if (next > offset + length) invalid()
            input.seek(next)
        }
        if (input.filePointer != offset + length) invalid()
    }
}

/** Limit index allocations and the manifest read by PackageManager without unpacking a release APK. */
internal fun checkReleaseApk(file: File) {
    checkDirectory(file)
    java.util.zip.ZipFile(file).use { zip ->
        val entry = zip.getEntry("AndroidManifest.xml") ?: throw ImportFailure("AndroidManifest.xmlがありません。")
        if (entry.size !in 1..8L * 1024 * 1024) throw ImportFailure("APKのマニフェストがサイズ上限外です。")
        val count = zip.getInputStream(entry).use { transfer(it, null, entry.size) }
        if (count != entry.size) throw ImportFailure("APKのマニフェストが破損しています。")
    }
}
