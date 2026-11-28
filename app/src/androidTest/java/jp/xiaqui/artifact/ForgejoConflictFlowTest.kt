package jp.xiaqui.artifact

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ForgejoConflictFlowTest {
    private val app = ApkInfo("jp.example.conflict", "Known App", "7", 7, "AA")

    private fun source(key: String, provider: String, priority: Int, digest: String): DistributionSource {
        val asset = ReleaseApk(priority.toLong() + 1, "app.apk", 10, digest, sourceKey = key)
        val release = StoreRelease(priority.toLong() + 10, "v7", "2026", false,
            listOf(asset), sourceKey = key)
        return DistributionSource(key, provider, if (provider == PROVIDER_GITHUB) "https://github.com" else "https://forge.example",
            priority.toLong() + 1, "owner/repo$priority", priority, history = listOf(release),
            inspected = mapOf(asset.identity() to app), checked = 1, fetched = 1)
    }

    @Test fun conflictPreservesKnownDisplayUntilExactCandidateIsChosen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val catalog = Catalog(context)
        val forge = source("forge-conflict", PROVIDER_FORGEJO, 0, "a".repeat(64))
        val github = source("github-conflict", PROVIDER_GITHUB, 1, "b".repeat(64))
        var registration = catalog.addSource(null, forge, app)
        val key = registration.repo
        try {
            registration = catalog.addSource(registration, github, app)
            val priorHistory = registration.history
            registration = catalog.replaceSourceState(registration, forge)
            registration = catalog.replaceSourceState(registration, github)
            assertTrue(registration.error.contains("競合"))
            assertEquals(priorHistory, registration.history)
            assertTrue(registration.inspected.isNotEmpty())

            registration = catalog.chooseSource(registration, github.key)
            assertEquals(github.key, registration.sourceChoice!!.sourceKey)
            assertEquals(github.key, registration.history.first().sourceKey)
            assertTrue(registration.error.isEmpty())
            assertEquals(listOf(forge.key, github.key), catalog.sources(registration)
                .sortedBy { it.priority }.map { it.key })

            val beforeRefresh = registration
            registration = catalog.replaceSourceState(registration, forge.copy(inspected = emptyMap()))
            assertTrue(!registration.sourceSelectionCurrent)
            assertEquals(github.key, registration.sourceChoice!!.sourceKey)
            registration = catalog.replaceSourceState(registration, forge)
            assertTrue(registration.sourceSelectionCurrent)
            assertTrue(unchangedRegistration(beforeRefresh, registration))

            val staleSnapshot = registration
            registration = catalog.reorderSources(registration, listOf(github.key, forge.key))
            registration = catalog.replaceSourceState(staleSnapshot, forge.copy(error = "temporary"))
            assertEquals(listOf(github.key, forge.key), catalog.sources(registration)
                .sortedBy { it.priority }.map { it.key })
            assertEquals(github.key, registration.sourceChoice!!.sourceKey)
            assertTrue(registration.sourceNotice.contains("確認失敗"))
            assertTrue(registration.error.isEmpty())

            assertTrue(!unchangedRegistration(staleSnapshot, registration))
            registration = catalog.replaceSourceState(registration, forge)
            registration = catalog.removeSource(registration, github.key)
            assertEquals(forge.key, registration.history.first().sourceKey)
            assertTrue(registration.inspected.isNotEmpty())
            registration = catalog.approveSource(registration, github.key)
            assertTrue(registration.error.contains("競合"))
            assertTrue(registration.inspected.isNotEmpty())
            assertEquals(null, registration.sourceChoice)

            catalog.sources(registration).forEach { source ->
                registration = catalog.replaceSourceState(registration, source.copy(
                    history = emptyList(), inspected = emptyMap(), error = "", checked = 4))
            }
            assertTrue(registration.sourceNotice.startsWith("全配布元の確認成功"))
            assertTrue(registration.error.isEmpty())
            assertTrue(!registration.sourceSelectionCurrent)
            assertEquals("対象なし（今回の配布なし）", StoreService(context, catalog).prepareUpdate(registration) {})
            assertTrue(AutoUpdates.manualCandidates(context).none { it.repo == key })
        } finally {
            catalog.remove(key)
        }
    }
}
