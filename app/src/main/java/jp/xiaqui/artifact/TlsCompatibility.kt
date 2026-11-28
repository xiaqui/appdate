package jp.xiaqui.artifact

import android.content.Context
import android.os.Build
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

internal const val ISRG_ROOT_X1_SHA256 =
    "96bcec06264976f37460779acf28c5a7cfe8a3c0aae11a8ffcee05c0bddf08c6"

/**
 * Android 6 predates ISRG Root X1 in the system trust store. Android 7+ uses the
 * Network Security Config in the manifest; API 23 needs the equivalent socket
 * factory explicitly. Hostname verification remains HttpsURLConnection's
 * platform default and neither path trusts arbitrary user-installed roots.
 */
internal object TlsCompatibility {
    @Volatile private var api23Factory: SSLSocketFactory? = null

    fun initialize(context: Context) {
        if (Build.VERSION.SDK_INT != Build.VERSION_CODES.M || api23Factory != null) return
        synchronized(this) {
            if (api23Factory == null) api23Factory = buildApi23Factory(context)
        }
    }

    fun open(url: URL): HttpURLConnection {
        val connection = url.openConnection() as HttpURLConnection
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.M && connection is HttpsURLConnection) {
            connection.sslSocketFactory = checkNotNull(api23Factory) {
                "TLS compatibility was not initialized"
            }
        }
        return connection
    }

    private fun buildApi23Factory(context: Context): SSLSocketFactory {
        val system = trustManager(null)
        val certificate = context.resources.openRawResource(R.raw.isrg_root_x1).use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString("") { "%02x".format(it) }
        check(digest == ISRG_ROOT_X1_SHA256) { "Unexpected bundled TLS trust anchor" }

        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("isrg-root-x1", certificate)
        }
        val contextWithAnchor = SSLContext.getInstance("TLS")
        contextWithAnchor.init(null, arrayOf(CombinedX509TrustManager(system, trustManager(store))), null)
        return contextWithAnchor.socketFactory
    }

    private fun trustManager(store: KeyStore?): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(store)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().single()
    }
}

internal class CombinedX509TrustManager(
    private val system: X509TrustManager,
    private val bundled: X509TrustManager
) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        system.checkClientTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        try {
            system.checkServerTrusted(chain, authType)
        } catch (systemFailure: CertificateException) {
            try {
                bundled.checkServerTrusted(chain, authType)
            } catch (bundledFailure: CertificateException) {
                bundledFailure.addSuppressed(systemFailure)
                throw bundledFailure
            }
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> =
        (system.acceptedIssuers.asList() + bundled.acceptedIssuers.asList())
            .distinctBy { it.encoded.contentHashCode() }.toTypedArray()
}
