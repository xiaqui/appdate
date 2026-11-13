package jp.xiaqui.artifact

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal object StoreTime {
    fun local(epoch: Long, zone: TimeZone = TimeZone.getDefault()): String {
        if (epoch <= 0) return "—"
        return SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).apply { timeZone = zone }.format(Date(epoch))
    }
    fun published(utc: String, zone: TimeZone = TimeZone.getDefault()): String {
        val match = Regex("""(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.\d+)?Z""").matchEntire(utc) ?: return "—"
        val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC"); isLenient = false
        }
        return runCatching { local(requireNotNull(parser.parse(match.groupValues[1])).time, zone) }.getOrDefault("—")
    }
}
