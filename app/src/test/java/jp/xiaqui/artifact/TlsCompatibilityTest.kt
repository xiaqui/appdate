package jp.xiaqui.artifact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

class TlsCompatibilityTest {
    private class RecordingTrustManager(private val acceptServer: Boolean) : X509TrustManager {
        var serverChecks = 0
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            serverChecks++
            if (!acceptServer) throw CertificateException("rejected")
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    @Test fun `system trust remains first choice`() {
        val system = RecordingTrustManager(true)
        val bundled = RecordingTrustManager(true)
        CombinedX509TrustManager(system, bundled).checkServerTrusted(emptyArray(), "RSA")
        assertEquals(1, system.serverChecks)
        assertEquals(0, bundled.serverChecks)
    }

    @Test fun `bundled anchor is a narrow fallback`() {
        val system = RecordingTrustManager(false)
        val bundled = RecordingTrustManager(true)
        CombinedX509TrustManager(system, bundled).checkServerTrusted(emptyArray(), "RSA")
        assertEquals(1, system.serverChecks)
        assertEquals(1, bundled.serverChecks)
    }

    @Test fun `unknown or otherwise invalid chains stay rejected`() {
        val combined = CombinedX509TrustManager(
            RecordingTrustManager(false), RecordingTrustManager(false))
        assertThrows(CertificateException::class.java) {
            combined.checkServerTrusted(emptyArray(), "RSA")
        }
    }
}
