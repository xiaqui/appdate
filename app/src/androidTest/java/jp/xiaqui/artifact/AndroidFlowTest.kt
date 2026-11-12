package jp.xiaqui.artifact

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AndroidFlowTest {
    @Test fun contentImportAndInstallerHandoff() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val installed = File(context.applicationInfo.sourceDir)
        val saved = ApkStore(File(context.filesDir, "apks")).save(installed.copyTo(File(context.cacheDir, "fixture.apk"), true), "fixture.apk")
        val copied = File(context.cacheDir, "incoming.apk")
        try {
            val installer = Installation.installer(context, saved)
            assertEquals(Intent.ACTION_INSTALL_PACKAGE, installer.action)
            assertTrue(installer.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            val uri = requireNotNull(installer.data)
            val shared = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uri)
            assertEquals(uri, Incoming.uri(shared))
            SourceReader(context.contentResolver).read(uri, copied)
            assertTrue(Archive(copied).inspect().standalone)
            assertTrue(Installation.inspect(context, copied).contains(context.packageName))
            assertFalse(Incoming.diagnostics(shared, uri).contains(saved.name))
            assertTrue(saved.isFile)
        } finally { copied.delete(); saved.delete() }
    }

    @Test fun diagnosticsHideTokensAndUnknownHosts() {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "APK https://secret.example/app.apk?token=private")
        val uri = Incoming.uri(intent)
        assertEquals("secret.example", uri?.host)
        val report = Incoming.diagnostics(intent, uri)
        assertFalse(report.contains("private"))
        assertFalse(report.contains("secret.example"))
    }
}
