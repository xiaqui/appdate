package jp.xiaqui.artifact

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ExternalFlowTest {
    @Test fun clipTextAndUriGrantDiagnostics() {
        val share = Intent(Intent.ACTION_SEND).setType("text/plain")
        share.clipData = ClipData.newPlainText("private","https://github.com/o/r/releases/tag/t")
        assertEquals("github.com",Incoming.uri(share)?.host)
        val content = Uri.parse("content://fixtures/secret-path")
        share.clipData = ClipData.newRawUri("file",content)
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertEquals(content,Incoming.uri(share))
        val diagnostic = Incoming.diagnostics(share,content)
        assertFalse(diagnostic.contains("secret-path"))
        assertTrue(diagnostic.contains("読取grant=あり"))
    }
    @Test fun externalHistoryRequiresExplicitAssociationAndPreservesOrigin() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val shelf = SavedShelf(context)
        val src = File.createTempFile("input",".apk",context.cacheDir)
        File(context.applicationInfo.sourceDir).copyTo(src,true)
        val meta = Installation.describe(context,src)
        val file = shelf.save(src,"imported.apk",meta,"端末ファイル")
        try {
            val repo = "fixtures/ext"
            val sha = shelf.hash(file)
            val asset = ReleaseApk(1,"imported.apk",file.length(),sha)
            val r = Registration(repo,true,"key",history=listOf(StoreRelease(1,"v1","",false,listOf(asset))))
            val option = requireNotNull(matchExternal(r,meta,sha,null))
            assertFalse(file in shelf.related(repo))
            shelf.associate(file,sha,option,"同一版")
            assertTrue(file in SavedShelf(context).related(repo))
            assertTrue(shelf.describe(file).contains("端末ファイル"))
            try { shelf.associate(file,"mismatch",option,"旧版"); fail() } catch (_: ImportFailure) {}
            assertTrue(file.exists())
        } finally { shelf.delete(file); src.delete() }
    }
}
