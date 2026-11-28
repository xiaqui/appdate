package jp.xiaqui.artifact

import android.security.NetworkSecurityPolicy
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy
import java.net.URL
import java.security.Principal
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSession

@RunWith(AndroidJUnit4::class)
class TlsTrustAnchorTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun fixture(name: String): X509Certificate = instrumentation.context.assets
        .open("tls/$name.pem").use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }

    private fun session(certificate: X509Certificate): SSLSession = Proxy.newProxyInstance(
        SSLSession::class.java.classLoader,
        arrayOf(SSLSession::class.java)
    ) { _, method, _ ->
        when (method.name) {
            "getPeerCertificates" -> arrayOf(certificate)
            "getPeerCertificateChain" -> arrayOf(javax.security.cert.X509Certificate.getInstance(certificate.encoded))
            "getPeerPrincipal" -> certificate.subjectX500Principal as Principal
            "getCipherSuite" -> "TLS_RSA_WITH_AES_128_CBC_SHA"
            "getProtocol" -> "TLSv1.2"
            "getPeerHost" -> "valid.fixture"
            "getPeerPort" -> 443
            "isValid" -> true
            "getValueNames" -> emptyArray<String>()
            else -> when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                else -> null
            }
        }
    } as SSLSession

    @Test fun officialIsrgRootX1IsBundledExactly() {
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        val certificate = resources.openRawResource(R.raw.isrg_root_x1).use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString("") { "%02x".format(it) }
        assertEquals(ISRG_ROOT_X1_SHA256, digest)
        assertEquals("CN=ISRG Root X1,O=Internet Security Research Group,C=US",
            certificate.subjectX500Principal.name)
        certificate.checkValidity()
        assertFalse(NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted)
    }

    @Test fun realTrustManagerAcceptsValidChainAndRejectsUnknownAndExpired() {
        val root = fixture("root")
        val manager = TlsCompatibility.trustManagerFor(root)
        manager.checkServerTrusted(arrayOf(fixture("valid"), root), "RSA")
        assertThrows(CertificateException::class.java) {
            manager.checkServerTrusted(arrayOf(fixture("unknown")), "RSA")
        }
        assertThrows(CertificateException::class.java) {
            manager.checkServerTrusted(arrayOf(fixture("expired"), root), "RSA")
        }
    }

    @Test fun platformHostnameVerifierRejectsMismatchedHost() {
        val verifier = HttpsURLConnection.getDefaultHostnameVerifier()
        val sslSession = session(fixture("valid"))
        assertTrue(verifier.verify("valid.fixture", sslSession))
        assertFalse(verifier.verify("other.fixture", sslSession))
    }

    @Test fun api23FactoryAndApi24NetworkConfigUseTheIntendedPaths() {
        val target = instrumentation.targetContext
        TlsCompatibility.initialize(target)
        val connection = TlsCompatibility.open(URL("https://valid.fixture")) as HttpsURLConnection
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.M) {
            assertNotSame(HttpsURLConnection.getDefaultSSLSocketFactory(), connection.sslSocketFactory)
        } else {
            @Suppress("DEPRECATION")
            val info = target.packageManager.getApplicationInfo(target.packageName, 0)
            assertEquals(R.xml.network_security_config, info.networkSecurityConfigRes)
        }
        connection.disconnect()
    }
}
