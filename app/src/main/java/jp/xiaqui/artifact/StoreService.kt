package jp.xiaqui.artifact

import android.content.Context
import java.io.File

internal fun storeError(e: Exception) = when {
    e is UpdateDeferred -> e.message ?: "取得を保留しました。"
    e is AssetConnectionFailure -> e.message.orEmpty()
    e is ImportFailure -> e.message ?: "APK取得失敗"
    e is java.util.zip.ZipException -> "破損APKです。"
    e is java.net.SocketTimeoutException || e is java.net.UnknownHostException -> "通信失敗。接続を確認し再試行してください。"
    e is java.io.IOException && e.message.orEmpty().contains("ENOSPC") -> "端末の空き容量が不足しています。"
    e is java.io.IOException -> "通信・ファイル読取に失敗しました。再試行してください。"
    else -> "APKまたはRelease情報を読み取れません。"
}
internal data class ForgejoCandidate(val source: DistributionSource, val info: ApkInfo, val existingRepo: String?)
internal data class ForgejoDiscoveryReport(val candidates: List<ForgejoCandidate>, val held: List<String>, val fetched: Int) {
    val summary get() = "Forgejo repo取得 $fetched / 登録候補 ${candidates.size} / 保留 ${held.size}" +
        held.take(3).joinToString("") { "\n$it" }
}
internal data class ForgejoApprovalReport(val approved: List<Registration>, val failed: List<String>) {
    val summary get() = "Forgejo配布元 ${approved.size} 件を承認・初回確認 / ${failed.size} 件を保留" +
        failed.take(3).joinToString("") { "\n$it" }
}
internal class StoreService(private val context: Context, val catalog: Catalog,
    private val api: GitHubReleases = GitHubReleases(StoredAuth(context)),
    private val installed: (String) -> ApkInfo? = { Installation.installed(context,it) }) {
    private val vault = StoredAuth(context)
    private val githubProvider by lazy { GitHubProvider(api) }
    private val forgejoProvider by lazy { ForgejoProvider(vault::tokenForSource) }
    val shelf = SavedShelf(context)
    private val temp = File(context.cacheDir, "store-imports")
    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        java.security.DigestInputStream(file.inputStream(), digest).use { transfer(it, null, MAX_INPUT) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    init {
        synchronized(tempLock) {
            if (!cleaned) { temp.deleteRecursively(); cleaned = true }
            temp.mkdirs()
        }
    }
    private fun provider(source: DistributionSource): ReleaseProvider = when (source.provider) {
        PROVIDER_GITHUB -> githubProvider
        PROVIDER_FORGEJO -> forgejoProvider
        else -> throw ImportFailure("未対応の配布元です。")
    }
    private fun source(r: Registration, asset: ReleaseApk): DistributionSource = catalog.sources(r)
        .firstOrNull { it.key == asset.sourceKey || r.sources.isEmpty() && asset.sourceKey == "github" && it.provider == PROVIDER_GITHUB }
        ?: throw ImportFailure("APKの配布元を確認できません。更新確認をやり直してください。")

    private fun <T> fetch(r: Registration, asset: ReleaseApk, allowed: () -> Unit = {}, apply: (File, ApkInfo) -> T): T {
        if (temp.usableSpace < asset.size + 4L * 1024 * 1024) throw ImportFailure("端末の空き容量が不足しています。")
        val staged = File.createTempFile("release-", ".apk", temp)
        try {
            val primary = source(r, asset)
            try { provider(primary).download(primary, asset, staged, allowed) }
            catch (first: Exception) {
                if (first is InterruptedException) throw first
                val expected = asset.digest?.takeIf { it.length == 64 }
                val fallback = expected?.let { digest -> catalog.sources(r).asSequence()
                    .filter { it.approved && it.key != primary.key }
                    .sortedBy { it.priority }
                    .flatMap { candidate -> candidate.history.asSequence().flatMap { release -> release.assets.asSequence().map { candidate to it } } }
                    .firstOrNull { (_, other) -> other.digest?.equals(digest, true) == true }
                }
                if (fallback == null) throw first
                staged.delete(); staged.createNewFile()
                provider(fallback.first).download(fallback.first, fallback.second, staged, allowed)
            }
            checkReleaseApk(staged)
            val info = Installation.describe(context, staged)
            if (info.signer.isEmpty()) throw ImportFailure("APKの署名を取得できません。")
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            return apply(staged, info)
        } finally { staged.delete() }
    }

    fun refresh(r: Registration, inspectApks: Boolean = true, inspectAllowed: () -> Unit = {}) {
        if (r.sources.isNotEmpty()) { refreshSources(r, inspectApks, inspectAllowed); return }
        try {
            val history = api.history(r.link)
            val known = r.inspected.toMutableMap()
            val latest = history.firstOrNull { r.previews || !it.preview }
            val errors = mutableListOf<String>()
            if (inspectApks) for (a in latest?.assets.orEmpty().filter { it.identity() !in known }.take(4)) {
                try { known[a.identity()] = fetch(r, a, inspectAllowed) { _, meta -> meta } }
                catch (e: Exception) {
                    if (e is InterruptedException) throw e
                    errors += "${a.name}: ${storeError(e)}"
                }
            }
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val now = System.currentTimeMillis()
            catalog.update(r) {
                val snapshot = if (errors.isEmpty() || it.fetched == 0L) history else it.history
                val retained = snapshot.flatMap { release -> release.assets }.map { asset -> asset.identity() }.toSet()
                it.copy(history = snapshot, checked = now, fetched = if (errors.isEmpty()) now else it.fetched,
                    error = errors.joinToString("\n"), inspected = (it.inspected + known).filterKeys(retained::contains))
            }
        } catch (e: Exception) {
            if (e is InterruptedException) throw e
            catalog.update(r) { it.copy(error = storeError(e), checked = System.currentTimeMillis()) }
        }
    }

    fun discoverForgejo(connection: SourceConnection, token: String): ForgejoDiscoveryReport {
        if (connection.provider != PROVIDER_FORGEJO) throw ImportFailure("Forgejo接続先を確認してください。")
        val discovery = ForgejoDiscovery(connection.server, connection.owner, token)
        val repos = mutableListOf<RemoteRepo>()
        var page = 1
        while (true) {
            if (Thread.currentThread().isInterrupted || vault.tokenForSource(connection.key) != token) throw InterruptedException()
            val result = discovery.page(page)
            repos += result.repos
            if (!result.more) break
            if (page++ >= 10000) throw ImportFailure("Forgejo repo列挙を完了できませんでした。既存登録は保持しています。")
        }
        val candidates = mutableListOf<ForgejoCandidate>()
        val held = mutableListOf<String>()
        for (repo in repos.distinctBy { it.id }) {
            if (Thread.currentThread().isInterrupted || vault.tokenForSource(connection.key) != token) throw InterruptedException()
            try {
                candidateFor(connection, repo)?.let(candidates::add)
            } catch (e: Exception) {
                if (e is InterruptedException) throw e
                held += "${repo.name}: ${storeError(e)}"
            }
        }
        return ForgejoDiscoveryReport(candidates, held, repos.distinctBy { it.id }.size)
    }

    fun discoverForgejoRepo(connection: SourceConnection, token: String, raw: String): ForgejoDiscoveryReport {
        if (vault.tokenForSource(connection.key) != token) throw InterruptedException()
        val repo = ForgejoDiscovery(connection.server, connection.owner, token).repository(raw)
        return try {
            val candidate = candidateFor(connection, repo)
            ForgejoDiscoveryReport(candidate?.let(::listOf).orEmpty(), emptyList(), 1)
        } catch (e: Exception) {
            if (e is InterruptedException) throw e
            ForgejoDiscoveryReport(emptyList(), listOf("${repo.name}: ${storeError(e)}"), 1)
        }
    }

    private fun candidateFor(connection: SourceConnection, repo: RemoteRepo): ForgejoCandidate? {
        val identity = sourceIdentity(PROVIDER_FORGEJO, connection.server, repo.id)
        var source = DistributionSource(identity.key, PROVIDER_FORGEJO, connection.server, repo.id, repo.name,
            priority = 0, authKey = connection.key)
        var history = forgejoProvider.history(source)
        val release = history.firstOrNull() ?: throw ImportFailure("APK配布履歴なし")
        val asset = release.assets.singleOrNull()
            ?: throw ImportFailure(if (release.assets.isEmpty()) "APK配布履歴なし" else "最新Releaseに複数APK")
        source = source.copy(history = history)
        val shadow = Registration("candidate", true, "candidate", sources = listOf(source))
        val parsed = fetch(shadow, asset) { file, info -> info to sha256(file) }
        val info = parsed.first
        if (info.signer.isBlank()) throw ImportFailure("APK署名を確認できません")
        val verified = asset.copy(digest = parsed.second)
        history = history.map { row -> row.copy(assets = row.assets.map { if (it.id == asset.id) verified else it }) }
        source = source.copy(inspected = mapOf(verified.identity() to info), checked = System.currentTimeMillis(),
            fetched = System.currentTimeMillis(), history = history)
        val existing = catalog.findSource(source.key) ?: catalog.findApp(info.pkg, info.signer)
        val knownSource = existing?.let { catalog.sources(it).firstOrNull { known -> known.key == source.key } }
        if (knownSource != null && knownSource.repository == source.repository && knownSource.authKey == source.authKey) return null
        return ForgejoCandidate(source, info, existing?.repo)
    }

    fun approveForgejo(candidates: List<ForgejoCandidate>): ForgejoApprovalReport {
        val approved = mutableListOf<Registration>()
        val failed = mutableListOf<String>()
        candidates.forEach { candidate ->
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            try {
                val existing = candidate.existingRepo?.let(catalog::get)
                approved += catalog.addSource(existing, candidate.source.copy(priority = 0, approved = true), candidate.info)
            } catch (e: ImportFailure) {
                failed += "${candidate.source.repository}: ${storeError(e)}"
            }
        }
        return ForgejoApprovalReport(approved, failed)
    }

    private fun refreshSources(r: Registration, inspectApks: Boolean, inspectAllowed: () -> Unit) {
        var current = catalog.get(r.repo) ?: r
        val keys = catalog.sources(current).filter { it.approved }.sortedBy { it.priority }.map { it.key }
        for (key in keys) {
            val original = catalog.sources(current).firstOrNull { it.key == key && it.approved } ?: continue
            var updated = original
            try {
                var history = provider(original).history(original)
                val known = original.inspected.toMutableMap()
                val errors = mutableListOf<String>()
                val latest = history.firstOrNull { current.previews || !it.preview }
                if (inspectApks) for (asset in latest?.assets.orEmpty().filter { it.identity() !in known }.take(4)) {
                    try {
                        val shadow = current.copy(sources = catalog.sources(current).map { if (it.key == original.key) original.copy(history = history) else it })
                        val parsed = fetch(shadow, asset, inspectAllowed) { file, info -> info to sha256(file) }
                        val verified = asset.copy(digest = parsed.second)
                        history = history.map { release -> release.copy(assets = release.assets.map { if (it.id == asset.id) verified else it }) }
                        known.remove(asset.identity())
                        known[verified.identity()] = parsed.first
                    } catch (e: Exception) {
                        if (e is InterruptedException) throw e
                        errors += "${asset.name}: ${storeError(e)}"
                    }
                }
                val now = System.currentTimeMillis()
                updated = original.copy(history = history, inspected = known, checked = now,
                    fetched = if (errors.isEmpty()) now else original.fetched, error = errors.joinToString("\n"))
            } catch (e: Exception) {
                if (e is InterruptedException) throw e
                updated = original.copy(checked = System.currentTimeMillis(), error = storeError(e))
            }
            current = catalog.replaceSourceState(current, updated)
        }
    }

    fun inspect(r: Registration, a: ReleaseApk): ApkInfo = synchronized(transferLock) { fetch(r, a) { file, info ->
        if (r.sources.isEmpty()) catalog.update(r) { it.copy(inspected = it.inspected + (a.identity() to info)) }
        else {
            val current = catalog.get(r.repo) ?: r
            val source = catalog.sources(current).first { it.key == a.sourceKey }
            val verified = a.copy(digest = sha256(file))
            val history = source.history.map { release -> release.copy(assets = release.assets.map { if (it.id == a.id) verified else it }) }
            catalog.replaceSourceState(current, source.copy(history = history, inspected = source.inspected + (verified.identity() to info)))
        }
        info
    } }

    fun saved(r: Registration, a: ReleaseApk) = shelf.candidate(r.repo,a)
    fun save(r: Registration, release: StoreRelease, a: ReleaseApk): File = synchronized(transferLock) {
        shelf.verified(r.repo,a)?.let { shelf.pin(it); return@synchronized it }
        r.inspected[a.identity()]?.let { info -> shelf.verifiedAcrossSources(info, a.digest)?.let { shelf.pin(it); return@synchronized it } }
        fetch(r, a) { staged, info ->
            val expected = r.inspected[a.identity()]
            if (expected != null && (expected.pkg != info.pkg || expected.code != info.code || expected.signer != info.signer))
                throw ImportFailure("解析済みAPKと取得したpackageName・versionCode・署名が異なります。Release Assetを確認してください。")
            catalog.update(r) { it.copy(inspected = it.inspected + (a.identity() to info)) }
            synchronized(repoDataLock) {
                if (catalog.get(r.repo)?.revision != r.revision)
                    throw ImportFailure("登録が変更されたため保存を中止しました。現在の登録から再試行してください。")
                shelf.verifiedAcrossSources(info, sha256(staged))?.let { shelf.pin(it); return@synchronized it }
                val providerName = if (source(r,a).provider == PROVIDER_GITHUB) "GitHub Release" else "Forgejo Release"
                shelf.save(staged, a.name, info, providerName, r.repo, release.tag, a)
            }
        }
    }
    fun prepareUpdate(r: Registration, allowed: () -> Unit): String = synchronized(transferLock) {
        if (r.error.isNotEmpty()) return@synchronized "保留（更新確認失敗）"
        if (r.sources.isNotEmpty() && !r.sourceSelectionCurrent) {
            return@synchronized if (r.sourceNotice.startsWith("全配布元の確認成功")) {
                "対象なし（今回の配布なし）"
            } else {
                "保留（今回の配布元候補を選択できません）"
            }
        }
        val release=r.visible.firstOrNull() ?: return@synchronized "対象なし"
        val asset=release.assets.singleOrNull() ?: return@synchronized "保留（複数APK・手動選択が必要）"
        val known=r.inspected[asset.identity()]
        fun local(info: ApkInfo) = runCatching { installed(info.pkg) }.getOrNull()
        if (known?.sdkChecked == true && known.sdk.min == 0) return@synchronized "保留（対応OS不明・APK詳細で再解析可能）"
        if (known != null && known.sdk.min > 0 && !updateEligible(known,local(known),android.os.Build.VERSION.SDK_INT))
            return@synchronized "対象なし（導入状態・署名・対応OS）"
        fun validate(info: ApkInfo) {
            if (known != null && (known.pkg!=info.pkg || known.code!=info.code || known.signer!=info.signer))
                throw ImportFailure("解析済みAPKと取得したパッケージ・版・署名が異なります。")
            if (catalog.get(r.repo)?.revision != r.revision) throw ImportFailure("登録設定が変更されたため取得を保留しました。")
            catalog.update(r) { it.copy(inspected=it.inspected+(asset.identity() to info)) }
        }
        shelf.verified(r.repo,asset)?.let { file ->
            val info=Installation.describe(context,file); validate(info)
            if (updateEligible(info,local(info),android.os.Build.VERSION.SDK_INT)) {
                shelf.markPending(file)
                return@synchronized "保存済み"
            }
            return@synchronized "対象なし（保存済みAPKを照合）"
        }
        allowed()
        fetch(r,asset,allowed) { staged, info ->
            validate(info)
            if (!updateEligible(info,local(info),android.os.Build.VERSION.SDK_INT)) "対象なし（導入状態・署名・対応OS）"
            else {
                allowed()
                shelf.verifiedAcrossSources(info, sha256(staged))?.let { shelf.markPending(it); return@fetch "保存済み" }
                val providerName = if (source(r,asset).provider == PROVIDER_GITHUB) "GitHub Release" else "Forgejo Release"
                shelf.save(staged,asset.name,info,providerName,r.repo,release.tag,asset,automatic=true)
                "取得成功"
            }
        }
    }
    fun delete(file: File) = shelf.delete(file)
    companion object { private val transferLock = Any(); private val tempLock = Any(); private var cleaned = false }
}
