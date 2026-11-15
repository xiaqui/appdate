package jp.xiaqui.artifact

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.*
import java.io.File
import java.util.concurrent.Executors

class StoreActivity : Activity() {
    private lateinit var catalog: Catalog
    private lateinit var service: StoreService
    private lateinit var panel: LinearLayout
    private val executor = Executors.newSingleThreadExecutor()
    private var selected: String? = null
    private var releaseId: Long? = null
    private val vault by lazy { StoredAuth(this) }
    private val batch by lazy { getSharedPreferences("batch-result", MODE_PRIVATE) }
    private var busy = false
    private var settings = false
    private var shelf = false
    private var offset = 0
    private val pad get() = (16 * resources.displayMetrics.density).toInt()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        catalog = Catalog(this)
        service = StoreService(this, catalog)
        selected = state?.getString("repo")
        releaseId = state?.takeIf { it.containsKey("release") }?.getLong("release")
        offset = state?.getInt("offset") ?: 0
        shelf = state?.getBoolean("shelf") ?: intent.getBooleanExtra("shelf", false)
        settings = state?.getBoolean("settings") ?: intent.getBooleanExtra("settings", false)
    }
    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt("offset", offset)
        out.putString("repo", selected)
        releaseId?.let { out.putLong("release", it) }
        out.putBoolean("settings", settings)
        out.putBoolean("shelf", shelf)
    }
    override fun onResume() { super.onResume(); if (!busy) render() }
    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }

    private fun page(heading: String) {
        title = "Release Store"
        panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad,pad,pad,pad) }
        setContentView(ScrollView(this).apply { addView(panel) })
        label(heading, true)
    }
    private fun label(value: String, heading: Boolean = false) {
        panel.addView(TextView(this).apply {
            text = value
            textSize = if (heading) 21f else 15f
            if (heading) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, pad / 3, 0, pad / 3)
        })
    }
    private fun button(title: String, doIt: () -> Unit) {
        panel.addView(Button(this).apply { text = title; isAllCaps = false; setOnClickListener { doIt() } })
    }
    private fun notice(value: String) = AlertDialog.Builder(this).setMessage(value).setPositiveButton("閉じる", null).show()
    private fun <T> work(title: String, run: () -> T, done: (T) -> Unit = { render() }) {
        if (busy) return
        busy = true
        page(title)
        label("戻ると処理を中止します。")
        executor.execute {
            val result = runCatching(run)
            runOnUiThread {
                if (!isDestroyed && !isFinishing) {
                    busy = false
                    result.fold(done) { failure ->
                        render()
                        notice(if (failure is Exception) storeError(failure) else "処理に失敗しました。")
                    }
                }
            }
        }
    }

    private fun latestSummary(r: Registration): String {
        val latest = r.visible.firstOrNull()
        val assets = latest?.assets.orEmpty()
        val infos = assets.mapNotNull { r.inspected[it.identity()] }
        val names = infos.map { it.label }.distinct().joinToString(" / ")
        val packages = infos.map { it.pkg }.distinct()
        return buildString {
            appendLine(names.ifBlank { "アプリ名未確認" } + " · " + r.repo)
            if (packages.size > 1) appendLine("複数パッケージ")
            if (latest != null) {
                appendLine((if (r.error.isEmpty()) "最新APK" else "保存済み取得情報") + " · ${latest.tag} ${if (latest.preview) "（プレリリース）" else "（正式版）"}")
                assets.take(4).forEach { a ->
                    val info = r.inspected[a.identity()]
                    appendLine(a.name + " · " + (info?.let { "${it.pkg} ${it.version} (${it.code}) / ${Installation.status(this@StoreActivity, it)}" } ?: "未解析・判定不能"))
                }
                if (assets.size > 4) appendLine("ほか ${assets.size - 4} 件")
            } else appendLine(if (r.fetched == 0L) "更新未確認" else "対象APKなし")
            appendLine("最終成功 " + stamp(r.fetched))
            append("今回の確認 " + stamp(r.checked) + " · " + when {
                r.error.isNotEmpty() -> "失敗"
                r.checked > 0 -> "成功"
                else -> "未記録"
            })
            if (r.error.isNotEmpty()) append("\n取得失敗 · ${r.error}")
        }
    }

    private fun stamp(time: Long) = StoreTime.local(time)
    private fun row(r: Registration) = rowFor(r, android.os.Build.VERSION.SDK_INT <= 29) { Installation.installed(this, it) }
    private fun addRow(r: Registration) {
        val info = row(r)
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = pad * 3
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(pad / 2, pad / 4, pad / 2, pad / 4)
            isFocusable = true
            val color = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, color, true)
            setBackgroundResource(color.resourceId)
            setOnClickListener { selected = r.repo; offset = 0; render() }
        }
        item.addView(TextView(this).apply {
            text = info.name; textSize = 16f; setTypeface(typeface, Typeface.BOLD)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        item.addView(TextView(this).apply {
            text = if (info.status == "未導入") info.versions else "${info.versions}　${info.status}"
            textSize = 14f
        })
        item.contentDescription = "${info.name}、${info.versions}、${info.status}"
        panel.addView(item)
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menu.add("設定").setOnMenuItemClickListener { if (!busy) { settings = true; shelf = false; render() }; true }
        menu.add("保存したAPK").setOnMenuItemClickListener { if (!busy) { settings = false; shelf = true; render() }; true }
        menu.add("端末のZIP / APKを開く").setOnMenuItemClickListener {
            if (!busy) startActivity(Intent(this, MainActivity::class.java)); true
        }
        return true
    }

    private fun checkAll(repos: List<String>) {
        // Only finished results are durable. A restart during this run must not imply completion.
        batch.edit().putString("summary", "前回の一括確認は未完了").commit()
        work("一括更新を確認中…", {
            val summary = batchSummary(repos) { repo ->
                catalog.get(repo)?.let(service::refresh)
                catalog.get(repo)?.let(::row)
            }
            batch.edit().putString("summary", summary).commit()
        })
    }

    private fun <T> itemsPage(items: List<T>, draw: (T) -> Unit) {
        val pageSize = 20
        val start = offset.coerceIn(0, (items.size - 1).coerceAtLeast(0) / pageSize * pageSize)
        if (start > 0) button("前の20件") { offset = start - pageSize; render() }
        items.drop(start).take(pageSize).forEach(draw)
        if (start + pageSize < items.size) button("次の20件") { offset = start + pageSize; render() }
    }
    private fun render() {
        if (settings) {
            page("認証設定")
            label("共通PATは権限のある全repoで利用します。公開Releaseは原則匿名です。新しいprivate repoはGitHub側でトークンの対象に追加し、必要なら組織の承認を確認してください。期限切れ・失効時は新しいPATを登録してください。")
            button("共通PATの登録・変更・解除") { auth(null) }
            return
        }
        if (shelf) { library(); return }
        val r = selected?.let(catalog::get)
        if (r != null) {
            val release = r.visible.firstOrNull { it.id == releaseId }
            if (release != null) { assets(r, release); return }
            releaseId = null
            page(r.repo)
            if (catalog.pendingAuth() != null) label("認証移行の完了待ち。登録を編集→保存で再試行してください。旧認証は保持しています。")
            label(if (r.previews) "最新の対象：正式版＋プレリリース" else "最新の対象：正式版のみ")
            label(latestSummary(r))
            button("更新を確認 / 再試行") { refresh(listOf(r)) }
            button("個別PATの設定・再認証") { auth(r) }
            button("登録を編集") { edit(r) }
            label("Release履歴", true)
            itemsPage(r.visible) { h ->
                button("${h.tag} · ${if (h.preview) "プレリリース" else "正式版"}\n${StoreTime.published(h.published)} · APK ${h.assets.size} 件") {
                    releaseId = h.id; offset = 0; render()
                }
            }
            button("登録を削除") {
                AlertDialog.Builder(this).setMessage("${r.repo}の登録と取得キャッシュを削除します。保存したAPKは残ります。")
                    .setNegativeButton("戻る", null).setPositiveButton("登録を削除") { _, _ ->
                        try { synchronized(repoDataLock) { RepoMigration(this, catalog, vault).finishAuth(); catalog.requireIdle(); vault.forget(r.repo); if (catalog.remove(r.repo)) selected = null } }
                        catch (e: ImportFailure) { notice(e.message.orEmpty()) }
                        render()
                    }.show()
            }
            return
        }
        selected = null
        page("アプリ一覧")
        val registrations = catalog.names()
        val actions = LinearLayout(this).apply {
            orientation = if (resources.configuration.fontScale > 1.4f ||
                resources.configuration.screenWidthDp < 320) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        }
        fun action(name: String, click: () -> Unit) {
            val control = Button(this).apply { text = name; isAllCaps = false; setOnClickListener { click() } }
            if (actions.orientation == LinearLayout.HORIZONTAL)
                actions.addView(control, LinearLayout.LayoutParams(0, -2, 1f))
            else actions.addView(control)
        }
        action("登録追加") { edit(null) }
        if (registrations.isNotEmpty()) action("一括更新確認") { checkAll(registrations) }
        panel.addView(actions)
        batch.getString("summary", null)?.let { label(it) }
        itemsPage(registrations) { repo -> catalog.get(repo)?.let(::addRow) }
    }

    private fun edit(r: Registration?) {
        val field = EditText(this).apply { hint = "owner/repo または GitHub URL"; setSingleLine(); setText(r?.repo.orEmpty()) }
        val preview = CheckBox(this).apply { text = "最新の対象にプレリリースも含める"; isChecked = r?.previews ?: true }
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad,0,pad,0); addView(field); addView(preview)
        }
        val dialog = AlertDialog.Builder(this).setTitle(if (r == null) "リポジトリ登録" else "登録を編集（改名はGitHub IDを確認）").setView(fields)
            .setNegativeButton("戻る", null).setPositiveButton("保存", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val name = repoName(field.text.toString())
                    val previews = preview.isChecked
                    if (r != null) {
                        dialog.dismiss()
                        work("GitHub ID・認証を確認して登録を保存中…", {
                            RepoMigration(this, catalog, vault).rename(r, name, previews)
                        }) { updated ->
                            selected = updated.repo; releaseId = null; render()
                            notice(if (r.repo == updated.repo) "設定を保存しました。" else
                                "現在名：${updated.repo}\nRelease履歴・APK解析・取得日時・状態・個別認証・保存APK参照を保持して移行しました。")
                        }
                    } else {
                        dialog.dismiss()
                        work("登録を保存中…", { RepoMigration(this, catalog, vault).register(name, previews) }) { updated ->
                            selected = updated.repo; releaseId = null; render()
                        }
                    }
                } catch (e: ImportFailure) { field.error = e.message }
            }
        }
        dialog.show()
    }

    private fun auth(r: Registration?) {
        val field = EditText(this).apply {
            hint = "fine-grained PAT"; setSingleLine(); isSaveEnabled = false
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            if (android.os.Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        val dialog = AlertDialog.Builder(this).setTitle(r?.repo ?: "共通PAT")
            .setMessage("Contents読み取り権限。端末内に暗号化保存します。個別PATを解除すると共通PATに戻ります。")
            .setView(field).setNegativeButton("戻る", null).setNeutralButton("認証を解除") { _,_ -> try { if (r == null) vault.forgetCommon() else vault.forget(r.repo) } catch (e: ImportFailure) { notice(e.message.orEmpty()) } }
            .setPositiveButton(if (r == null) "保存" else "認証して確認", null).create()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.setOnShowListener {
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val token = field.text.toString().trim()
                if (!Regex("[A-Za-z0-9_]{1,500}").matches(token)) field.error = "トークンを確認してください。"
                else try {
                    if (r == null) vault.useCommon(token) else vault.use(r.repo, token)
                    field.text.clear(); dialog.dismiss()
                    if (r != null) refresh(listOf(r)) else render()
                }
                catch (e: ImportFailure) { field.error = e.message }
            }
        }
        dialog.show()
    }

    private fun refresh(rows: List<Registration>) = work("更新を確認中…", {
        for (r in rows) { if (Thread.currentThread().isInterrupted) throw InterruptedException(); service.refresh(r) }
    })
    private fun assets(r: Registration, h: StoreRelease) {
        page(h.tag)
        label(r.repo + " · " + StoreTime.published(h.published))
        label("Release対象コミット " + h.commit.ifBlank { "未提供" })
        label("ABIの適合は未判定。分割APKの一括導入は対象外です。")
        itemsPage(h.assets) { a ->
            label(a.name, true)
            label("Asset ID ${a.id} · SHA-256 ${a.digest ?: "未提供"}")
            label("同一版はversionCodeの比較です。バイナリの一致はSHA-256で確認してください。")
            val meta = r.inspected[a.identity()]
            label(meta?.let { "${it.summary}\n${Installation.status(this, it)}" } ?: "未解析 · packageName / versionCode 不明")
            if (meta == null) button("APK情報を確認") { work("APKを解析中…", { service.inspect(r, a) }) }
            button(if (service.saved(r, a) != null) "保存済みAPKを開く" else "保存してインストール確認へ") {
                work("APKを準備中…", { service.save(r, h, a) }) { file -> render(); open(file) }
            }
        }
    }
    private fun open(file: File) = startActivity(Intent(this, SavedActivity::class.java).putExtra("saved", file.name))
    private fun library() {
        page("保存したAPK")
        label("1パッケージ3世代、全24件・512 MiBまで。削除する世代は自分で選べます。")
        val files = service.shelf.entries()
        if (files.isEmpty()) label("保存したAPKはありません。")
        files.forEach { file ->
            label(service.shelf.describe(file))
            if (!file.isFile) return@forEach
            button("APK詳細・再インストール") { open(file) }
            button("この保存ファイルを削除") {
                AlertDialog.Builder(this).setMessage(service.shelf.describe(file)).setNegativeButton("戻る", null)
                    .setPositiveButton("APKを削除") { _, _ -> work("削除中…", { service.delete(file) }) }.show()
            }
        }
    }
    @Deprecated("Platform back API")
    override fun onBackPressed() {
        offset = 0
        when {
            busy -> { executor.shutdownNow(); finish() }
            settings -> { settings = false; render() }
            shelf -> { shelf = false; render() }
            releaseId != null -> { releaseId = null; render() }
            selected != null -> { selected = null; render() }
            else -> super.onBackPressed()
        }
    }
}
