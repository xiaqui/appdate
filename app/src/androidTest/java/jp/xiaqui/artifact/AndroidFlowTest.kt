package jp.xiaqui.artifact

import android.app.Activity
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
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", saved)
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                val installer = Installation.installer(context, saved)
                assertEquals(Intent.ACTION_INSTALL_PACKAGE, installer.action)
                assertTrue(installer.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                assertEquals(uri, installer.data)
                assertNotNull(installer.component)
            } else {
                val id = stageMarshmallowInstall(context, saved)
                val service = context.packageManager.packageInstaller
                try {
                    service.openSession(id).use { session ->
                        assertArrayEquals(arrayOf("base.apk"), session.names)
                        session.openRead("base.apk").use { assertEquals(saved.length(), transfer(it, null, MAX_INPUT)) }
                    }
                } finally { service.abandonSession(id) }
            }
            val shared = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uri)
            assertEquals(uri, Incoming.uri(shared))
            SourceReader(context.contentResolver).read(uri, copied)
            assertTrue(Archive(copied).inspect().standalone)
            assertTrue(Installation.inspect(context, copied).contains(context.packageName))
            assertFalse(Incoming.diagnostics(shared, uri).contains(saved.name))
            assertTrue(saved.isFile)
        } finally { copied.delete(); saved.delete() }
    }

    @Test fun installerReturnKeepsSavedApk() {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 26)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val dir = File(context.filesDir, "apks").apply { mkdirs() }
        val fixture = File.createTempFile("return", ".apk", dir)
        File(context.applicationInfo.sourceDir).copyTo(fixture, true)
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", fixture)
        val view = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(view) as MainActivity
        var imported: File? = null
        try {
            awaitView(instrumentation, activity, "標準インストーラで開く")
            val next = Installation.permissionSettings(context) ?: Installation.installer(context, fixture)
            val filter = android.content.IntentFilter(next.action).apply { addDataScheme(next.data?.scheme); next.resolveType(context)?.let { addDataType(it) } }
            val monitor = instrumentation.addMonitor(filter,
                android.app.Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true)
            try {
                instrumentation.runOnMainSync {
                    val filename = visibleTexts(activity.window.decorView).first { it.text.endsWith(".apk") }.text.toString()
                    imported = File(dir, filename)
                    visibleTexts(activity.window.decorView).first { it.text == "標準インストーラで開く" }.performClick()
                }
                awaitView(instrumentation, activity, "保存済みAPKは再度開けます。")
                assertTrue(monitor.hits > 0)
                assertTrue(imported?.isFile == true)
            } finally { instrumentation.removeMonitor(monitor) }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            fixture.delete()
            imported?.delete()
        }
    }

    private fun visibleTexts(view: android.view.View): List<android.widget.TextView> = when (view) {
        is android.widget.TextView -> listOf(view)
        is android.view.ViewGroup -> (0 until view.childCount).flatMap { visibleTexts(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun awaitView(instrumentation: android.app.Instrumentation, activity: Activity, expected: String) {
        repeat(200) {
            var found = false
            instrumentation.runOnMainSync { found = visibleTexts(activity.window.decorView).any { it.text == expected } }
            if (found) return
            Thread.sleep(50)
        }
        fail("Missing UI text: $expected")
    }

    @Test fun resolvesArtifactAndVersionedApkLinks() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (link in listOf("https://github.com/example/repo/actions/runs/1/artifacts/2",
            "https://example.org/app-1.2.3.apk")) {
            val view = Intent(Intent.ACTION_VIEW, Uri.parse(link)).addCategory(Intent.CATEGORY_BROWSABLE)
                .setPackage(context.packageName)
            assertNotNull(context.packageManager.resolveActivity(view, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY))
        }
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
