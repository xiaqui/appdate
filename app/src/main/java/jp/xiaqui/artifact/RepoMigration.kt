package jp.xiaqui.artifact

import android.content.Context

/** Network proof precedes mutation. Only the small local commit holds the shared data lock. */
internal class RepoMigration(context: Context,
    private val catalog: Catalog = Catalog(context),
    private val vault: StoredAuth = StoredAuth(context),
    private val resolve: (String, Long, String) -> RepoIdentity = run {
        val api = RepoIdentityApi(object : ReleaseAuth {
            override fun tokenFor(repository: String) = vault.readToken(repository, true)
            override fun commonToken() = vault.readToken("@common", true)
        })
        val lookup: (String, Long, String) -> RepoIdentity = { name, id, credentials -> api.resolve(name, id, credentials) }
        lookup
    }, private val proveHistory: (Registration, String) -> Unit = { r, name ->
        if (r.githubId == 0L && (r.history.isNotEmpty() || r.inspected.isNotEmpty())) {
            val anchor = r.history.firstOrNull()?.id
                ?: throw ImportFailure("旧GitHub IDと履歴の同一性を証明できません。旧登録を保持しました。")
            val api = GitHubReleases(object : ReleaseAuth {
                override fun tokenFor(repository: String) = vault.readToken(r.repo, true)
                override fun commonToken() = vault.readToken("@common", true)
            })
            try { api.confirmRelease(ReleaseLink(name, null, null, true), anchor) }
            catch (_: Exception) { throw ImportFailure("旧GitHub IDが未記録で、保存履歴のRelease IDを現在repoで確認できません。旧データを保持しました。権限・通信を確認して再試行してください。履歴のReleaseが削除済みなら推測移行しません。") }
        }
    }) {
    fun register(raw: String, previews: Boolean): Registration {
        val name = repoName(raw)
        if (catalog.rememberedId(name) == 0 && catalog.canonical(name) == name)
            return catalog.register(null, name, previews)
        val current = resolve(name, 0, name)
        synchronized(repoDataLock) {
            catalog.registeredIdentity(current.id, current.name)?.let { return it }
            return catalog.register(null, current.name, previews, current.id)
        }
    }
    fun finishAuth() = synchronized(repoDataLock) {
        catalog.pendingAuth()?.let { (old, new) ->
            val prior = vault.readToken(old, true)
            var next = try { vault.readToken(new, true) } catch (_: ImportFailure) { null }
            if (prior != null && next == null) {
                vault.use(new, prior)
                next = vault.readToken(new, true)
            }
            if (prior != null && next != prior)
                throw ImportFailure("登録は移行済みですが個別PATが競合しています。旧認証を保持しました。現在名の個別PAT設定で認証を解除し、登録編集→保存で旧認証の再コピーを実行してください。")
            vault.forget(old)
            catalog.authCleaned()
        }
    }

    fun rename(r: Registration, raw: String, previews: Boolean): Registration {
        finishAuth()
        val requested = repoName(raw)
        if (requested == r.repo) return catalog.register(r.repo, requested, previews)
        val previous = resolve(r.repo, r.githubId, r.repo)
        val target = resolve(requested, 0, r.repo)
        val name = verifiedRename(r, previous, target)
        proveHistory(r, name)
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        return synchronized(repoDataLock) {
            catalog.requireIdle()
            if (catalog.get(r.repo)?.revision != r.revision)
                throw ImportFailure("確認中に登録が変わりました。編集を開き直してください。")
            if (name != r.repo && catalog.get(name) != null)
                throw ImportFailure("変更先は登録済みです。同一IDでも自動統合しません。双方の履歴・認証を確認して重複登録を解決してから再試行してください。")
            catalog.validateMigration(r, name, target.id)
            if (name != r.repo) vault.copyVerified(r.repo, name)
            val updated = catalog.migrate(r, name, target.id, previews)
            // If interrupted here, the atomic catalog also contains a cleanup record.
            // Keep both encrypted entries until recovery verifies the new entry again.
            finishAuth()
            updated
        }
    }
}
