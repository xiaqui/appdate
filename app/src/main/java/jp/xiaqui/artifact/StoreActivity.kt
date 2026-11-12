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
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

class StoreActivity : Activity() {
    private lateinit var catalog: Catalog
    private lateinit var service: StoreService
    private lateinit var panel: LinearLayout
    private val executor = Executors.newSingleThreadExecutor()
    private var selected: String? = null
    private var releaseId: Long? = null
    private var busy = false
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
        shelf = state?.getBoolean("shelf") == true
    }
    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt("offset", offset)
        out.putString("repo", selected)
        releaseId?.let { out.putLong("release", it) }
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
                appendLine("最新APK · ${latest.tag} ${if (latest.preview) "（プレリリース）" else "（正式版）"}")
                assets.take(4).forEach { a ->
                    val info = r.inspected[a.identity()]
                    appendLine(a.name + " · " + (info?.let { "${it.pkg} ${it.version} (${it.code}) / ${Installation.status(this@StoreActivity, it)}" } ?: "未解析・判定不能"))
                }
                if (assets.size > 4) appendLine("ほか ${assets.size - 4} 件")
            } else appendLine(if (r.fetched == 0L) "更新未確認" else "対象APKなし")
            append("最終取得 " + if (r.fetched == 0L) "—" else DateFormat.getDateTimeInstance().format(Date(r.fetched)))
            if (r.error.isNotEmpty()) append("\n取得失敗 · ${r.error}")
        }
    }

    private fun <T> itemsPage(items: List<T>, draw: (T) -> Unit) {
        val pageSize = 20
        val start = offset.coerceIn(0, (items.size - 1).coerceAtLeast(0) / pageSize * pageSize)
        if (start > 0) button("前の20件") { offset = start - pageSize; render() }
        items.drop(start).take(pageSize).forEach(draw)
        if (start + pageSize < items.size) button("次の20件") { offset = start + pageSize; render() }
    }
    private fun render() {
        if (shelf) { library(); return }
        val r = selected?.let(catalog::get)
        if (r != null) {
            val release = r.visible.firstOrNull { it.id == releaseId }
            if (release != null) { assets(r, release); return }
            releaseId = null
            page(r.repo)
            label(if (r.previews) "最新の対象：正式版＋プレリリース" else "最新の対象：正式版のみ")
            label(latestSummary(r))
            button("更新を確認 / 再試行") { refresh(listOf(r)) }
            button("認証・再認証") { auth(r) }
            button("登録を編集") { edit(r) }
            label("Release履歴", true)
            itemsPage(r.visible) { h ->
                button("${h.tag} · ${if (h.preview) "プレリリース" else "正式版"}\n${h.published} · APK ${h.assets.size} 件") {
                    releaseId = h.id; offset = 0; render()
                }
            }
            button("登録を削除") {
                AlertDialog.Builder(this).setMessage("${r.repo}の登録と取得キャッシュを削除します。保存したAPKは残ります。")
                    .setNegativeButton("戻る", null).setPositiveButton("登録を削除") { _, _ ->
                        if (catalog.remove(r.repo)) { processAuth.forget(r.repo); selected = null }
                        render()
                    }.show()
            }
            return
        }
        selected = null
        page("アプリ一覧")
        button("リポジトリを登録") { edit(null) }
        val registrations = catalog.names()
        if (registrations.isNotEmpty()) button("すべての更新を確認") { work("更新を確認中…", { registrations.forEach { repo ->
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            catalog.get(repo)?.let(service::refresh)
        } }) }
        itemsPage(registrations) { repo ->
            val entry = catalog.get(repo) ?: return@itemsPage
            label(latestSummary(entry))
            button("${entry.repo} の更新・過去版") { selected = entry.repo; offset = 0; render() }
        }
        button("保存したAPK") { shelf = true; render() }
        button("端末のZIP / APKを開く") { startActivity(Intent(this, MainActivity::class.java)) }
    }

    private fun edit(r: Registration?) {
        val field = EditText(this).apply { hint = "owner/repo または GitHub URL"; setSingleLine(); setText(r?.repo.orEmpty()) }
        val preview = CheckBox(this).apply { text = "最新の対象にプレリリースも含める"; isChecked = r?.previews ?: true }
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad,0,pad,0); addView(field); addView(preview)
        }
        val dialog = AlertDialog.Builder(this).setTitle("リポジトリ登録").setView(fields)
            .setNegativeButton("戻る", null).setPositiveButton("保存", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val updated = catalog.register(r?.repo, field.text.toString(), preview.isChecked)
                    if (r != null && r.repo != updated.repo) processAuth.forget(r.repo)
                    selected = updated.repo; releaseId = null
                    dialog.dismiss(); render()
                } catch (e: ImportFailure) { field.error = e.message }
            }
        }
        dialog.show()
    }

    private fun auth(r: Registration) {
        val field = EditText(this).apply {
            hint = "fine-grained PAT"; setSingleLine(); isSaveEnabled = false
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            if (android.os.Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        val dialog = AlertDialog.Builder(this).setTitle(r.repo)
            .setMessage("Contents読み取り権限。トークンはプロセス終了までこのリポジトリ専用で保持します。")
            .setView(field).setNegativeButton("戻る", null).setNeutralButton("認証を解除") { _,_ -> processAuth.forget(r.repo) }
            .setPositiveButton("認証して確認", null).create()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.setOnShowListener {
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val token = field.text.toString().trim()
                if (!Regex("[A-Za-z0-9_]{1,500}").matches(token)) field.error = "トークンを確認してください。"
                else { processAuth.use(r.repo, token); field.text.clear(); dialog.dismiss(); refresh(listOf(r)) }
            }
        }
        dialog.show()
    }

    private fun refresh(rows: List<Registration>) = work("更新を確認中…", {
        for (r in rows) { if (Thread.currentThread().isInterrupted) throw InterruptedException(); service.refresh(r) }
    })
    private fun assets(r: Registration, h: StoreRelease) {
        page(h.tag)
        label(r.repo + " · " + h.published)
        label("ABIの適合は未判定。分割APKの一括導入は対象外です。")
        itemsPage(h.assets) { a ->
            label(a.name, true)
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
        val files = service.dir.listFiles().orEmpty().filter { it.isFile }.sortedByDescending { it.lastModified() }
        if (files.isEmpty()) label("保存したAPKはありません。")
        files.forEach { file ->
            label(service.provenance(file))
            button("標準インストーラで開く") { open(file) }
            button("この保存ファイルを削除") {
                AlertDialog.Builder(this).setMessage(service.provenance(file)).setNegativeButton("戻る", null)
                    .setPositiveButton("APKを削除") { _, _ -> work("削除中…", { service.delete(file) }) }.show()
            }
        }
    }
    @Deprecated("Platform back API")
    override fun onBackPressed() {
        offset = 0
        when {
            busy -> { executor.shutdownNow(); finish() }
            shelf -> { shelf = false; render() }
            releaseId != null -> { releaseId = null; render() }
            selected != null -> { selected = null; render() }
            else -> super.onBackPressed()
        }
    }
}
