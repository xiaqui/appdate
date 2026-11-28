package jp.xiaqui.artifact

import android.app.job.JobScheduler
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection

@RunWith(AndroidJUnit4::class)
class AutoUpdateFlowTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun legacyBatchSummaryIsMarkedPreviousAndInterruptedRunKeepsIt() {
        val prefs=context.getSharedPreferences("batch-result",android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().putString("summary","成功 7（更新 1・同一 3）／失敗 1").commit()
        try {
            val results=BatchResults(context)
            assertTrue(results.display()!!.startsWith("前回の確認結果"))
            val run=results.begin(CheckMethod.STARTUP,7)
            run.record(StoreRow("demo","","","同一版"))
            run.fail(InterruptedException())
            val current=results.display()!!
            assertTrue(current.contains("自動確認（起動時） · 未完了"))
            assertTrue(current.contains("前回の確認結果"))
            assertTrue(current.contains("更新 1・同一 3"))
            val retry=results.begin(CheckMethod.STARTUP,7)
            retry.fail(InterruptedException())
            val repeated=results.display()!!
            assertEquals(1,Regex("更新 1・同一 3").findAll(repeated).count())
            assertTrue(repeated.length < current.length + 100)
        } finally { prefs.edit().clear().commit() }
    }
    @Test fun runningBatchFromDeadProcessRecoversAsInterrupted() {
        val prefs=context.getSharedPreferences("batch-result",android.content.Context.MODE_PRIVATE)
        prefs.edit().clear()
            .putInt("schema",2)
            .putString("phase",CheckPhase.RUNNING.name)
            .putString("method",CheckMethod.MANUAL.name)
            .putString("process","dead-process")
            .putInt("total",7).putInt("checked",3).putInt("ok",2)
            .putInt("updates",1).putInt("same",1).putInt("failed",1)
            .putString("last-complete","手動確認 · 完了 · previous")
            .putString("summary","手動確認 · 確認中 3/7").commit()
        try {
            val display=BatchResults(context).display()!!
            assertTrue(display.contains("手動確認 · 未完了"))
            assertTrue(display.contains("成功 2（更新 1・同一 1）／失敗 1"))
            assertTrue(display.contains("プロセス終了により中断"))
            assertTrue(display.contains("前回の確認結果 · 手動確認 · 完了 · previous"))
            assertEquals(CheckPhase.INTERRUPTED.name,prefs.getString("phase",null))
            assertFalse(prefs.contains("process"))
        } finally { prefs.edit().clear().commit() }
    }
    @Test fun archiveSdkAndActualInstalledVersionOnOldAndNewAndroid() {
        val file=File(context.applicationInfo.sourceDir)
        val archive=Installation.describe(context,file)
        val installed=Installation.installed(context,context.packageName)!!
        assertEquals(23,archive.sdk.min); assertEquals(35,archive.sdk.target); assertEquals(0,archive.sdk.max)
        assertEquals(installed.pkg,archive.pkg); assertEquals(installed.code,archive.code)
        assertEquals(installed.signer,archive.signer)
    }
    @Test fun packageVisibilityDistinguishesInstalledAbsentAndUnknownOnNewAndroid() {
        assertTrue("AppDate needs complete package visibility for update comparison", Installation.absenceKnown(context))
        assertNotNull(Installation.installed(context,context.packageName))
        val missing="jp.xiaqui.fixture.definitely.not.installed"
        assertNull(Installation.installed(context,missing))
        val asset=ReleaseApk(990,"missing.apk",1,null)
        val remote=Installation.describe(context,File(context.applicationInfo.sourceDir)).copy(pkg=missing,label="Missing fixture")
        val registration=Registration("fixture/missing",true,"",1,"",
            listOf(StoreRelease(1,"v","2026",false,listOf(asset))),mapOf(asset.identity() to remote))
        val row=rowFor(registration,Installation.absenceKnown(context),android.os.Build.VERSION.SDK_INT) {
            Installation.installed(context,it)
        }
        assertTrue(row.versions.startsWith("未導入 →"))
        assertEquals("未導入",row.status)
    }
    @Test fun metadataOnlyThenSingleTransferPersistentSaveAndNoDuplicate() {
        AutoSettings(context).apply { startupEnabled=false; periodicEnabled=false }
        val cat=Catalog(context); val repo="update-fixture/app"; cat.remove(repo)
        val source=File(context.applicationInfo.sourceDir)
        val info=Installation.describe(context,source)
        var downloads=0
        val asset=ReleaseApk(777,"test.apk",source.length(),null)
        val api=GitHubReleases(SessionAuth()) { url -> object : HttpURLConnection(url) {
            override fun getResponseCode()=200
            override fun getInputStream(): java.io.InputStream {
                if(url.path.endsWith("/assets/777")) { downloads++; return source.inputStream() }
                return """[{"id":1,"tag_name":"fixture","published_at":"2026-01-01T00:00:00Z","assets":[{"id":777,"name":"test.apk","size":${source.length()}}]}]""".byteInputStream()
            }
            override fun disconnect() {}
            override fun connect() {}
            override fun usingProxy()=false
        } }
        val service=StoreService(context,cat,api) { info.copy(code=info.code-1) }
        var saved: File?=null
        try {
            val r=cat.register(null,repo,true)
            service.refresh(r, inspectApks = false); assertEquals(0,downloads)
            assertEquals("取得成功",service.prepareUpdate(cat.get(repo)!!){})
            assertEquals(1,downloads)
            saved=SavedShelf(context).verified(repo,asset)
            assertNotNull(saved)
            assertEquals("保存済み",service.prepareUpdate(cat.get(repo)!!){fail("must use saved APK offline")})
            assertEquals(1,downloads)
            assertEquals(info,cat.get(repo)!!.inspected[asset.identity()])
            assertTrue(SavedShelf(context).describe(saved!!).contains("インストール待ち"))
            SavedShelf(context).pin(saved!!)
            assertFalse(SavedShelf(context).describe(saved!!).contains("自動取得"))
        } finally { saved?.let { SavedShelf(context).delete(it) }; cat.remove(repo) }
    }
    @Test fun blockedTransferLeavesNoCompletedFile() {
        val cat=Catalog(context); val repo="update-fixture/interrupted"; cat.remove(repo)
        val source=File(context.applicationInfo.sourceDir)
        val info=Installation.describe(context,source)
        val a=ReleaseApk(778,"test.apk",source.length(),null)
        val api=GitHubReleases(SessionAuth()) { url -> object : HttpURLConnection(url) {
            override fun getResponseCode()=200
            override fun getInputStream()=source.inputStream()
            override fun disconnect() {}
            override fun connect() {}
            override fun usingProxy()=false
        } }
        try {
            val r=cat.register(null,repo,true)
            cat.update(r) { it.copy(history=listOf(StoreRelease(1,"t","2026",false,listOf(a)))) }
            var guards=0
            try {
                StoreService(context,cat,api){info.copy(code=info.code-1)}.prepareUpdate(cat.get(repo)!!) {
                    if(++guards>4) throw ImportFailure("fixture network switch")
                }; fail("Expected policy abort")
            } catch (_: ImportFailure) {}
            assertNull(SavedShelf(context).candidate(repo,a))
            assertTrue(File(context.cacheDir,"store-imports").listFiles().orEmpty().isEmpty())
        } finally { cat.remove(repo) }
    }
    @Test fun automaticInspectionGuardStopsTransferAndLeavesNoMetadata() {
        val cat=Catalog(context); val repo="update-fixture/inspection-policy"; cat.remove(repo)
        val source=File(context.applicationInfo.sourceDir)
        val asset=ReleaseApk(781,"inspect.apk",source.length(),null)
        var guards=0
        val api=GitHubReleases(SessionAuth()) { url -> object : HttpURLConnection(url) {
            override fun getResponseCode()=200
            override fun getInputStream(): java.io.InputStream {
                if(url.path.endsWith("/assets/781")) return source.inputStream()
                return """[{"id":1,"tag_name":"fixture","published_at":"2026-01-01T00:00:00Z","assets":[{"id":781,"name":"inspect.apk","size":${source.length()}}]}]""".byteInputStream()
            }
            override fun disconnect() {}
            override fun connect() {}
            override fun usingProxy()=false
        } }
        try {
            val r=cat.register(null,repo,true)
            StoreService(context,cat,api).refresh(r,inspectApks=true) {
                if(++guards>3) throw UpdateDeferred("fixture Wi-Fi lost")
            }
            val saved=cat.get(repo)!!
            assertTrue(guards>3)
            assertFalse(saved.inspected.containsKey(asset.identity()))
            assertTrue(saved.error.contains("fixture Wi-Fi lost"))
            assertTrue(File(context.cacheDir,"store-imports").listFiles().orEmpty().isEmpty())
        } finally { cat.remove(repo) }
    }
    @Test fun metadataOnlyDetectionRemainsAvailableForManualDownload() {
        val cat=Catalog(context); val repo="update-fixture/manual-uninspected"; cat.remove(repo)
        val source=File(context.applicationInfo.sourceDir)
        val asset=ReleaseApk(780,"manual.apk",source.length(),null)
        try {
            val r=cat.register(null,repo,true)
            cat.update(r) { it.copy(history=listOf(StoreRelease(1,"v-next","2026",false,listOf(asset))),fetched=1,checked=1) }
            assertEquals("APK要確認",rowFor(cat.get(repo)!!,true,android.os.Build.VERSION.SDK_INT){null}.status)
            assertTrue(AutoUpdates.manualCandidates(context).any { it.repo==repo })
        } finally { cat.remove(repo) }
    }
    @Test fun notificationTapOpensConfirmationAndBootRestoresEligibility() {
        val inst=InstrumentationRegistry.getInstrumentation()
        if (android.os.Build.VERSION.SDK_INT >= 33)
            inst.uiAutomation.grantRuntimePermission(context.packageName,android.Manifest.permission.POST_NOTIFICATIONS)
        AutoSettings(context).apply { startupEnabled=false; periodicEnabled=false }
        val shelf=SavedShelf(context)
        val source=File(context.applicationInfo.sourceDir)
        val info=Installation.describe(context,source)
        val staged=File.createTempFile("notify-",".apk",context.cacheDir)
        source.copyTo(staged,overwrite=true)
        val file=shelf.save(staged,"notice.apk",info,"fixture",automatic=true)
        val manager=context.getSystemService(android.app.NotificationManager::class.java)
        val monitor=inst.addMonitor(SavedActivity::class.java.name,null,false)
        try {
            AutoSettings(context).prefs.edit().remove("notified").commit()
            AutoUpdates.publishReady(context,listOf(file to info))
            // NotificationManager posts asynchronously on some API 29 emulator runs.  Wait for
            // the requested ID instead of racing the system service immediately after notify().
            val deadline=android.os.SystemClock.uptimeMillis()+5_000
            var posted: android.service.notification.StatusBarNotification?=null
            while(posted==null && android.os.SystemClock.uptimeMillis()<deadline) {
                posted=manager.activeNotifications.firstOrNull { it.id==42043 }
                if(posted==null) android.os.SystemClock.sleep(50)
            }
            val notification=requireNotNull(posted) { "Ready notification was not posted within 5 seconds" }.notification
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                val options=android.app.ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                notification.contentIntent.send(context,0,null,null,null,null,options.toBundle())
            } else notification.contentIntent.send()
            val activity=inst.waitForMonitorWithTimeout(monitor,5000)
            assertNotNull("Notification must open confirmation Activity",activity)
            inst.runOnMainSync { activity.finish() }
            inst.waitForIdleSync()
            assertTrue("Opening a notification must not permanently pin an auto download",shelf.describe(file).contains("自動取得"))
            val multiple=AutoUpdates.notificationIntent(context,listOf(file to info,file to info.copy(pkg="other")))
            assertEquals(StoreActivity::class.java.name,multiple.component!!.className)
            assertTrue(multiple.getBooleanExtra("shelf",false))
            AutoSettings(context).prefs.edit().putString("notified","old-boot").commit()
            UpdateEvents().onReceive(context,android.content.Intent(android.content.Intent.ACTION_BOOT_COMPLETED))
            assertNotEquals("old-boot",AutoSettings(context).prefs.getString("notified",null))
        } finally { inst.removeMonitor(monitor); manager.cancel(42043); shelf.delete(file) }
    }
    @Test fun sdkUnknownDoesNotRepeatDownloadAndCorruptSavedFileRemainsRetryable() {
        AutoSettings(context).apply { startupEnabled=false; periodicEnabled=false }
        val cat=Catalog(context); val repo="update-fixture/unknown"; cat.remove(repo)
        val info=Installation.describe(context,File(context.applicationInfo.sourceDir))
        val a=ReleaseApk(779,"x.apk",File(context.applicationInfo.sourceDir).length(),null)
        var saved: File?=null
        try {
            val r=cat.register(null,repo,true)
            cat.update(r) { it.copy(history=listOf(StoreRelease(1,"t","2026",false,listOf(a))),
                inspected=mapOf(a.identity() to info.copy(code=info.code+1,sdk=ApkSdk(),sdkChecked=true))) }
            val api=GitHubReleases(SessionAuth()) { throw AssertionError("Must not redownload inspected-unknown SDK") }
            assertTrue(StoreService(context,cat,api).prepareUpdate(cat.get(repo)!!){fail("no download")}.startsWith("保留"))
            cat.update(r) { it.copy(inspected=mapOf(a.identity() to info.copy(code=info.code+1))) }
            val staged=File.createTempFile("corrupt-",".apk",context.cacheDir)
            File(context.applicationInfo.sourceDir).copyTo(staged,overwrite=true)
            saved=SavedShelf(context).save(staged,"x.apk",info.copy(code=info.code+1),"fixture",repo,"t",a)
            AutoUpdates.reconcile(context)
            assertFalse(AutoUpdates.manualCandidates(context).any { it.repo==repo })
            saved!!.writeBytes(byteArrayOf(1,2,3))
            assertTrue(AutoUpdates.manualCandidates(context).any { it.repo==repo })
        } finally { saved?.let { SavedShelf(context).delete(it) }; cat.remove(repo) }
    }
    @Test fun temporaryProtectionReleasesAndCleanupKeepsHistory() {
        val shelf=SavedShelf(context)
        val source=File(context.applicationInfo.sourceDir)
        val actual=Installation.describe(context,source)
        val created=mutableListOf<File>()
        fun save(code: Long): File {
            val staged=File.createTempFile("retain-",".apk",context.cacheDir)
            source.copyTo(staged,overwrite=true)
            return shelf.save(staged,"retained.apk",actual.copy(code=code),"fixture",automatic=true).also { created+=it }
        }
        try {
            val first=save(actual.code-3); val second=save(actual.code-2); val recovery=save(actual.code-1)
            shelf.ready() // The real installed package supersedes these three fixture generations.
            shelf.protect(first)
            save(actual.code+1)
            assertTrue(first.exists()); assertFalse(second.exists()); assertTrue(recovery.exists())
            assertTrue(shelf.describe(second).contains("自動整理済み"))
            shelf.release(first)
            save(actual.code+2)
            assertFalse(first.exists()); assertTrue(recovery.exists())
        } finally {
            created.forEach { shelf.release(it); if(it.exists()) shelf.delete(it) }
            val ledger=context.getSharedPreferences("saved-release-v1",android.content.Context.MODE_PRIVATE)
            val edit=ledger.edit(); created.forEach { edit.remove(it.name) }; edit.commit()
        }
    }
    @Test fun periodicJobIsPersistedAndIndependentSwitchCancelsIt() {
        val settings=AutoSettings(context)
        settings.periodicEnabled=true; AutoUpdates.schedule(context)
        val job=context.getSystemService(JobScheduler::class.java).allPendingJobs.single { it.id==42042 }
        assertTrue(job.isPersisted); assertEquals(6*60*60*1000L,job.intervalMillis)
        settings.periodicEnabled=false; AutoUpdates.schedule(context)
        assertFalse(context.getSystemService(JobScheduler::class.java).allPendingJobs.any { it.id==42042 })
        settings.startupEnabled=false; settings.downloadEnabled=false
        assertFalse(settings.startupEnabled); assertFalse(settings.periodicEnabled); assertFalse(settings.downloadEnabled)
        val recreated=AutoSettings(context)
        assertFalse(recreated.startupEnabled); assertFalse(recreated.periodicEnabled); assertFalse(recreated.downloadEnabled)
    }
}
