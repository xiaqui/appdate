package jp.xiaqui.artifact

import org.junit.Assert.*
import org.junit.Test

class ExternalMatchTest {
    private val asset = ReleaseApk(7,"sample.apk",20,"bytes")
    private val app = ApkInfo("example.app","Demo","v2",2,"signer")
    private val reg = Registration("owner/demo",true,"epoch",history=listOf(
        StoreRelease(1,"tag","2026-01-01T00:00:00Z",false,listOf(asset))),inspected=mapOf(asset.identity() to app))
    @Test fun evidenceAndAmbiguity() {
        assertNull(matchExternal(reg,app.copy(pkg="other"),"other",null))
        assertTrue(matchExternal(reg,app.copy(code=1),"other",null)!!.eligible)
        assertTrue(matchExternal(reg,app,"bytes","Owner/Demo")!!.evidence.contains("取得元"))
        assertTrue(matchExternal(reg,app,"bytes",null)!!.evidence.contains("SHA-256"))
        val different = matchExternal(reg,app.copy(signer="changed"),"other",null)!!
        assertFalse(different.eligible)
        assertFalse(matchExternal(reg,app.copy(signer=""),"bytes",null)!!.eligible)
        val second = reg.copy(repo="owner/fork")
        assertEquals(2,listOf(reg,second).mapNotNull { matchExternal(it,app,"bytes",null) }.size)
    }
}
