package jp.xiaqui.artifact

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchiveTest {
    @get:Rule val temp = TemporaryFolder()
    private fun zip(vararg names: String): File = temp.newFile().also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            for (name in names) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(name.toByteArray())
                zip.closeEntry()
            }
        }
    }

    @Test fun standalone() = assertTrue(Archive(zip("AndroidManifest.xml", "classes.dex")).inspect().standalone)
    @Test fun singleNested() = assertEquals(listOf("a/demo.APK"), Archive(zip("a/demo.APK")).inspect().choices)
    @Test fun multipleAndSelectiveExtraction() {
        val archive = Archive(zip("a/demo.apk", "b/demo.apk", "notes"))
        assertEquals(listOf("a/demo.apk", "b/demo.apk"), archive.inspect().choices)
        val out = temp.newFile()
        archive.extract("b/demo.apk", out)
        assertEquals("b/demo.apk", out.readText())
    }
    @Test fun unsafeNames() {
        for (name in listOf("../x.apk", "/x.apk", "a\\b.apk", "a/./b.apk", "evil\u202E.apk"))
            assertThrows(ImportFailure::class.java) { Archive(zip(name)).inspect() }
    }
    @Test fun noCandidates() { assertThrows(ImportFailure::class.java) { Archive(zip("notes")).inspect() } }
    @Test fun corrupt() { assertThrows(IOException::class.java) { Archive(temp.newFile()).inspect() } }
    @Test fun sizeLimit() {
        assertThrows(ImportFailure::class.java) { transfer(ByteArray(128).inputStream(), null, 32) }
    }
    @Test fun cancellation() {
        Thread.currentThread().interrupt()
        try { assertThrows(InterruptedException::class.java) { transfer(byteArrayOf(1).inputStream(), null, 32) } }
        finally { Thread.interrupted() }
    }
    @Test fun sameNamePersistsBoth() {
        val store = ApkStore(temp.newFolder())
        val first = store.save(zip("a"), "demo.apk")
        val second = store.save(zip("b"), "demo.apk")
        assertNotEquals(first, second)
        assertTrue(first.exists() && second.exists())
    }
    @Test fun githubPageClassification() {
        assertTrue(GitHubAccess.needsBrowser(URL("https://github.com/o/r/actions/runs/1/artifacts/2")))
        assertTrue(GitHubAccess.needsBrowser(URL("https://api.github.com/repos/o/r/actions/artifacts/2/zip")))
        assertFalse(GitHubAccess.needsBrowser(URL("https://github.com/o/r/releases/download/v1/app.apk")))
    }
    @Test fun rejectsHttpAndCredentialsBeforeConnecting() {
        for (link in listOf("http://example.test/app.apk", "https://user@example.test/app.apk")) {
            assertThrows(ImportFailure::class.java) { DirectHttps().download(URL(link), temp.newFile()) }
        }
    }
}
