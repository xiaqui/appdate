package jp.xiaqui.artifact

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RepoImportFlowTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun additiveImportKeepsSettingsAuthHistoryAndSupportsReadditionAndRename() {
        val catalog = Catalog(context)
        val vault = StoredAuth(context)
        val names = listOf("import-test/existing", "import-test/new", "import-test/renamed", "import-test/legacy")
        names.forEach(catalog::remove)
        val oldCommon = vault.commonToken()
        try {
            val old = catalog.register(null,names[0],false,991)
            val legacy = catalog.register(null,names[3],false)
            catalog.update(old) { it.copy(fetched=42, history=listOf(StoreRelease(1,"v1","2026-01-01",false,emptyList()))) }
            vault.use(names[0],"individualFixture")
            vault.useCommon("commonFirst")
            val first=importRepos(vault.commonToken()!!,{ token,_ ->
                assertEquals("commonFirst",token)
                RepoPage(listOf(RemoteRepo(991,names[0].uppercase()),RemoteRepo(992,names[1]),RemoteRepo(993,"import-test/no-apk"),RemoteRepo(994,names[3])),false)
            }, { _,repo,_ -> ApkReleasePage(repo.id != 993L,false) }, catalog::observeRemote, catalog::addRemote)
            assertEquals(1,first.added); assertEquals(2,first.retained)
            assertEquals(1,first.skipped); assertNull(catalog.get("import-test/no-apk"))
            val reread=Catalog(context).get(names[0])!!
            assertFalse(reread.previews); assertEquals(42L,reread.fetched); assertEquals(old.revision,reread.revision)
            assertEquals(991L,reread.githubId)
            val legacyRead=Catalog(context).get(names[3])!!
            assertEquals(0L,legacyRead.githubId); assertEquals(legacy.revision,legacyRead.revision)
            assertEquals("individualFixture",StoredAuth(context).tokenFor(names[0]))
            vault.useCommon("commonChanged")
            val second=importRepos(vault.commonToken()!!,{ token,_ ->
                assertEquals("commonChanged",token)
                RepoPage(listOf(RemoteRepo(992,names[1])),false)
            }, { _,_,_ -> ApkReleasePage(false,false) }, catalog::observeRemote, catalog::addRemote)
            assertEquals(0,second.added); assertNotNull(catalog.get(names[0]))
            catalog.remove(names[1]); assertEquals(ImportChange.ADDED,catalog.addRemote(RemoteRepo(992,names[1])))
            assertEquals(ImportChange.RENAMED,catalog.addRemote(RemoteRepo(991,names[2])))
            assertNull(catalog.get(names[2])); assertNotNull(catalog.get(names[0]))
            assertEquals("individualFixture",vault.tokenFor(names[0]))
            try { catalog.addRemote(RemoteRepo(999,names[0])); fail("ID collision") } catch (_: ImportFailure) {}
            assertEquals(991L,catalog.get(names[0])!!.githubId)
        } finally {
            names.forEach(catalog::remove); vault.forget(names[0])
            if (oldCommon == null) vault.forgetCommon() else vault.useCommon(oldCommon)
        }
    }
    // Separate instrumentation processes, also run across adb reboot in CI.
    @Test fun seed() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("restartCheck") == "true")
        Catalog(context).addRemote(RemoteRepo(990,"import-test/restart"))
    }
    @Test fun reopened() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("restartCheck") == "true")
        val catalog=Catalog(context)
        assertEquals(990L,catalog.get("import-test/restart")!!.githubId)
        catalog.remove("import-test/restart")
    }
}
