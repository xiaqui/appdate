package jp.xiaqui.artifact

import android.security.NetworkSecurityPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

@RunWith(AndroidJUnit4::class)
class TlsTrustAnchorTest {
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
}
