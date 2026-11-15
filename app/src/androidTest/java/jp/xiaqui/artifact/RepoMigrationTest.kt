package jp.xiaqui.artifact

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore

class RepoMigrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val old = "xiaqui/artifact"
    private val new = "xiaqui/release-store"
    private fun reset() {
        context.getSharedPreferences("store-v1",Context.MODE_PRIVATE).edit().clear().commit()
        StoredAuth(context).apply { forget(old); forget(new) }
    }
    private fun populated(c: Catalog): Registration {
        val r = c.register(null,old,false)
        val a = ReleaseApk(3,"fixture.apk",4,null)
        c.update(r) { it.copy(fetched=123,checked=456,error="HTTP 404",history=listOf(StoreRelease(2,"v1","UTC",false,listOf(a))),
            inspected=mapOf(a.identity() to ApkInfo("fixture.pkg","Fixture","1",1,"signer"))) }
        return c.get(old)!!
    }
    private fun migration(c: Catalog, id: Long = 42) = RepoMigration(context,c,StoredAuth(context), resolve = { _, _, _ -> RepoIdentity(id,new) }, proveHistory = { _, _ -> })
    @Test fun preservesAllMetadataAuthAndApkAndInvalidatesStaleWriters() {
        reset()
        val c = Catalog(context); val r = populated(c); val vault = StoredAuth(context)
        vault.useCommon("commonFixture"); vault.use(old,"repoFixture")
        val shelf = SavedShelf(context); shelf.dir.mkdirs()
        val file = File(shelf.dir,"rename-fixture.apk").apply { writeText("data") }
        val ledger = context.getSharedPreferences("saved-release-v1",Context.MODE_PRIVATE)
        ledger.edit().putString(file.name,"""{"repo":"$old","asset":"3:4::","sha256":"${java.security.MessageDigest.getInstance("SHA-256").digest("data".toByteArray()).joinToString("") { "%02x".format(it) }}"}""").commit()
        try {
            val result = migration(c).rename(r,new,true)
            assertNull(c.get(old)); assertEquals(listOf(new),c.names())
            assertEquals(r.copy(repo=new,previews=true,revision=result.revision,githubId=42),result)
            assertNotEquals(r.revision,result.revision)
            c.update(r) { it.copy(error="stale") }
            assertEquals("HTTP 404",c.get(new)!!.error)
            assertEquals("repoFixture",vault.tokenFor(new)); assertNull(vault.tokenFor(old))
            assertEquals("commonFixture",vault.commonToken())
            assertEquals(file,shelf.verified(new,r.history[0].assets[0]))
            assertEquals("data",file.readText()); assertTrue(shelf.describe(file).contains(new))
            assertEquals(new,c.registeredIdentity(42,old)!!.repo)
            assertEquals(new,c.registeredIdentity(42,new)!!.repo)
            try { c.registeredIdentity(43,old); fail() } catch (_: ImportFailure) {}
            try { c.register(null,old,true); fail() } catch (_: ImportFailure) {}
        } finally { file.delete(); ledger.edit().remove(file.name).commit(); vault.forgetCommon(); reset() }
    }
    @Test fun collisionsAndConcurrentEditsKeepBothRegistrationsAndCredentials() {
        reset(); val c=Catalog(context); val r=populated(c)
        c.register(null,new,true)
        try { migration(c).rename(r,new,true); fail() } catch (_: ImportFailure) {}
        assertEquals(r,c.get(old)); assertNotNull(c.get(new))
        c.remove(new)
        val m=RepoMigration(context,c,StoredAuth(context), resolve = { _, _, _ -> c.register(old,old,true); RepoIdentity(42,new) }, proveHistory = { _, _ -> })
        try { m.rename(r,new,true); fail() } catch (_: ImportFailure) {}
        assertNotNull(c.get(old)); assertNull(c.get(new)); reset()
    }
    @Test fun unreadableOldKeyDoesNotDeleteCiphertextOrRegistration() {
        reset(); val c=Catalog(context); val r=populated(c); val v=StoredAuth(context)
        v.use(old,"fixture")
        val cipher=File(context.noBackupFilesDir,"release-auth/"+v.alias(old)); val before=cipher.readBytes()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(v.alias(old)) }
        try { migration(c).rename(r,new,true); fail() } catch (_: ImportFailure) {}
        assertArrayEquals(before,cipher.readBytes()); assertEquals(r,c.get(old)); assertNull(c.get(new)); reset()
    }
    @Test fun copyBeforeCommitAndCommitBeforeCleanupAreRecoverable() {
        reset(); val c=Catalog(context); val r=populated(c); val v=StoredAuth(context)
        v.use(old,"fixture")
        v.copyVerified(old,new) // simulated termination before catalog commit
        assertEquals(r,Catalog(context).get(old)); assertEquals("fixture",StoredAuth(context).tokenFor(old))
        c.migrate(r,new,42,false) // simulated termination after atomic catalog commit
        assertNotNull(Catalog(context).pendingAuth()); assertEquals("fixture",StoredAuth(context).tokenFor(new))
        v.forget(new) // lost destination before recovery must be recopied from retained old auth
        RepoMigration(context).finishAuth()
        assertNull(c.pendingAuth()); assertNull(v.tokenFor(old)); assertEquals("fixture",v.tokenFor(new)); reset()
    }
    @Test fun reusedNameCannotReassignOldSavedOrigins() {
        reset(); val c = Catalog(context); val r = populated(c)
        migration(c).rename(r,new,false)
        c.remove(new)
        try { c.register(null,new,false,43); fail() } catch (_: ImportFailure) {}
        assertEquals(new,c.canonical(old)); assertEquals(42L,c.rememberedId(new))
        assertNotNull(c.register(null,new,false,42)); reset()
    }
    @Test fun missingLegacyHistoryProofLeavesEverythingUntouched() {
        reset(); val c=Catalog(context); val r=populated(c)
        val m=RepoMigration(context,c,StoredAuth(context), resolve = { _, _, _ -> RepoIdentity(42,new) },
            proveHistory = { _, _ -> throw ImportFailure("unverified fixture") })
        try { m.rename(r,new,true); fail() } catch (_: ImportFailure) {}
        assertEquals(r,c.get(old)); assertNull(c.get(new)); reset()
    }
    @Test fun emptyCatalogWithSavedOriginCannotSkipLegacyProof() {
        reset(); val c=Catalog(context); val r=c.register(null,old,true)
        val ledger=context.getSharedPreferences("saved-release-v1",Context.MODE_PRIVATE)
        ledger.edit().putString("proof-fixture.apk","""{"repo":"$old"}""").commit()
        try {
            val m=RepoMigration(context,c,StoredAuth(context), resolve = { _, _, _ -> RepoIdentity(42,new) })
            try { m.rename(r,new,true); fail() } catch (_: ImportFailure) {}
            assertEquals(r,c.get(old)); assertNull(c.get(new))
        } finally { ledger.edit().remove("proof-fixture.apk").commit(); reset() }
    }
    @Test fun authConflictPreservesBothAndOfflineDoesNotMutate() {
        reset(); val c=Catalog(context); val r=populated(c); val v=StoredAuth(context)
        v.use(old,"oldFixture"); v.use(new,"otherFixture")
        try { migration(c).rename(r,new,true); fail() } catch (_: ImportFailure) {}
        assertEquals("oldFixture",v.tokenFor(old)); assertEquals("otherFixture",v.tokenFor(new)); assertEquals(r,c.get(old))
        val offline=RepoMigration(context,c,v, resolve = { _, _, _ -> throw java.net.UnknownHostException() })
        try { offline.rename(r,new,true); fail() } catch (_: java.net.UnknownHostException) {}
        assertEquals(r,c.get(old)); reset()
    }
}
