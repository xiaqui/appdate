package jp.xiaqui.artifact

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

internal object Incoming {
    @Suppress("DEPRECATION")
    fun uri(intent: Intent): Uri? = when (intent.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> (intent.getParcelableExtra<android.os.Parcelable>(Intent.EXTRA_STREAM) as? Uri)
            ?: intent.clipData?.let { if (it.itemCount > 0) it.getItemAt(0).uri else null }
            ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.take(8192)?.let {
                Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE).find(it)?.value?.let(Uri::parse)
            }
        else -> null
    }

    fun diagnostics(intent: Intent, uri: Uri?): String {
        val action = when (intent.action) {
            Intent.ACTION_VIEW -> "VIEW"
            Intent.ACTION_SEND -> "SEND"
            Intent.ACTION_MAIN -> "MAIN"
            else -> "other"
        }
        val scheme = uri?.scheme?.takeIf { it in listOf("content", "https", "http", "file") } ?: "other/none"
        val host = when (uri?.host?.lowercase()) {
            "github.com", "api.github.com" -> uri.host
            null -> "—"
            else -> "非表示"
        }
        val type = intent.type?.takeIf { it in setOf("text/plain", "application/zip", "application/x-zip-compressed",
            "application/vnd.android.package-archive", "application/octet-stream") } ?: "other/none"
        return "action=$action   type=$type\nscheme=$scheme   host=$host\nURI種別=" + when (scheme) {
            "content" -> "コンテンツ"
            "http", "https" -> "Webリンク"
            else -> "その他"
        }
    }
}

internal interface RemoteSource {
    fun download(url: URL, destination: File)
}

internal object GitHubAccess {
    fun needsBrowser(url: URL): Boolean {
        val host = url.host.lowercase()
        return (host == "github.com" && url.path.contains("/actions/")) ||
            (host == "api.github.com" && url.path.contains("/actions/artifacts/"))
    }
}

internal class DirectHttps : RemoteSource {
    override fun download(url: URL, destination: File) {
        rejectCredentialUrl(url.toString())
        var next = url
        repeat(6) { hop ->
            rejectCredentialUrl(next.toString(), signedRedirect = hop > 0)
            if (!next.protocol.equals("https", true) || next.userInfo != null || next.host.isEmpty())
                throw ImportFailure("認証情報を含まないHTTPSリンクを指定してください。")
            if (GitHubAccess.needsBrowser(next)) throw ImportFailure("GitHubにサインインしたブラウザでArtifactを保存し、「ZIP / APK を開く」で選んでください。")
            val connection = TlsCompatibility.open(next)
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10000
                connection.readTimeout = 15000
                when (val status = connection.responseCode) {
                    301, 302, 303, 307, 308 -> next = URL(next, connection.getHeaderField("Location")
                        ?: throw ImportFailure("転送先がありません。"))
                    200 -> {
                        connection.inputStream.use { input -> destination.outputStream().use { transfer(input, it, MAX_INPUT) } }
                        return
                    }
                    else -> throw ImportFailure("取得に失敗しました（HTTP $status）。認証が必要な場合はブラウザから保存してください。")
                }
            } finally { connection.disconnect() }
        }
        throw ImportFailure("転送回数の上限を超えました。")
    }
}

internal class SourceReader(private val resolver: ContentResolver, private val remote: RemoteSource = DirectHttps()) {
    fun read(uri: Uri, to: File, signal: android.os.CancellationSignal = android.os.CancellationSignal()) {
        when (uri.scheme) {
            "content" -> resolver.openAssetFileDescriptor(uri, "r", signal)?.use { descriptor ->
                descriptor.createInputStream().use { input ->
                    signal.setOnCancelListener { runCatching { input.close() } }
                    try { to.outputStream().use { transfer(input, it, MAX_INPUT) } }
                    finally { signal.setOnCancelListener(null) }
                }
            } ?: throw ImportFailure("ファイルを開けませんでした。")
            "http", "https" -> remote.download(URL(uri.toString()), to)
            else -> throw ImportFailure("ファイル選択またはHTTPSリンクを使用してください。")
        }
    }
}
