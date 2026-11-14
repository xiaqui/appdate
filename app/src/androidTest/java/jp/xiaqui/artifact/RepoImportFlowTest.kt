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
        val names = listOf("import-test/existing", "import-test/new", "import-test/renamed")
        names.forEach(catalog::remove)
        val oldCommon = vault.commonToken()
        try {
            val old = catalog.register(null,names[0],false)
            catalog.update(old) { it.copy(fetched=42, history=listOf(StoreRelease(1,"v1","2026-01-01",false,emptyList()))) }
            vault.use(names[0],"individualFixture")
            vault.useCommon("commonFirst")
            val first=importRepos(vault.commonToken()!!,{ token,_ ->
                assertEquals("commonFirst",token)
                RepoPage(listOf(RemoteRepo(991,names[0].uppercase()),RemoteRepo(992,names[1])),false)
            },catalog::addRemote)
            assertEquals(1,first.added); assertEquals(1,first.retained)
            val reread=Catalog(context).get(names[0])!!
            assertFalse(reread.previews); assertEquals(42L,reread.fetched); assertEquals(old.revision,reread.revision)
            assertEquals("individualFixture",StoredAuth(context).tokenFor(names[0]))
            vault.useCommon("commonChanged")
            val second=importRepos(vault.commonToken()!!,{ token,_ ->
                assertEquals("commonChanged",token)
                RepoPage(listOf(RemoteRepo(992,names[1])),false)
            },catalog::addRemote)
            assertEquals(0,second.added); assertNotNull(catalog.get(names[0]))
            catalog.remove(names[1]); assertEquals(ImportChange.ADDED,catalog.addRemote(RemoteRepo(992,names[1])))
            assertEquals(ImportChange.RENAMED,catalog.addRemote(RemoteRepo(991,names[2])))
            assertNull(catalog.get(names[2])); assertEquals(names[2],catalog.get(names[0])!!.remoteName)
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
        Catalog(context).addRemote(RemoteRepo(990,"import-test/restart"))
    }
    @Test fun reopened() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("restartCheck") == "true")
        val catalog=Catalog(context)
        assertEquals(990L,catalog.get("import-test/restart")!!.githubId)
        catalog.remove("import-test/restart")
    }
}
