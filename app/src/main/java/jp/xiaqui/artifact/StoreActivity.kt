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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
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
    private val automatic by lazy { AutoSettings(this) }
    private val autoListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runOnUiThread { if (!isDestroyed && !isFinishing && !busy) render() }
    }
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
    override fun onResume() {
        super.onResume()
        automatic.prefs.registerOnSharedPreferenceChangeListener(autoListener)
        if (!busy) render()
        AutoUpdates.startup(this)
    }
    override fun onPause() {
        automatic.prefs.unregisterOnSharedPreferenceChangeListener(autoListener)
        super.onPause()
    }
    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }

    private fun page(heading: String) {
        title = "AppDate"
        panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad,pad,pad,pad) }
        val content = ScrollView(this).apply { addView(panel) }
        if (heading == "AppDate") {
            setContentView(SwipeRefreshLayout(this).apply {
                addView(content)
                setOnRefreshListener { swipeRefresh(this) }
            })
        } else setContentView(content)
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
    private fun row(r: Registration) = rowFor(r, android.os.Build.VERSION.SDK_INT <= 29, android.os.Build.VERSION.SDK_INT) { Installation.installed(this, it) }
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
        val heading=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        heading.addView(TextView(this).apply {
            text=info.name.substringBeforeLast(" · Android"); textSize=16f; setTypeface(typeface,Typeface.BOLD)
            maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END
        },LinearLayout.LayoutParams(0,-2,1f))
        heading.addView(TextView(this).apply { text=" Android"+info.name.substringAfterLast(" · Android"); textSize=13f })
        item.addView(heading)
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

    /** The pull gesture refreshes metadata without replacing the home screen with progress text. */
    private fun swipeRefresh(indicator: SwipeRefreshLayout) {
        if (busy) { indicator.isRefreshing = false; return }
        val repos = catalog.names()
        if (repos.isEmpty()) { indicator.isRefreshing = false; return }
        busy = true
        batch.edit().putString("summary", "前回の一括確認は未完了").commit()
        executor.execute {
            val result = runCatching {
                val summary = batchSummary(repos) { repo ->
                    catalog.get(repo)?.let(service::refresh)
                    catalog.get(repo)?.let(::row)
                }
                if (!batch.edit().putString("summary", summary).commit())
                    throw ImportFailure("更新確認結果を保存できません。")
            }
            runOnUiThread {
                if (!isDestroyed && !isFinishing) {
                    busy = false
                    indicator.isRefreshing = false
                    render()
                    result.exceptionOrNull()?.let { notice(if (it is Exception) storeError(it) else "更新確認に失敗しました。") }
                }
            }
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
        if (settings) {
            page("認証設定")
            label("共通PATは権限のある全repoで利用します。公開Releaseは原則匿名です。新しいprivate repoはGitHub側でトークンの対象に追加し、必要なら組織の承認を確認してください。期限切れ・失効時は新しいPATを登録してください。")
            button("共通PATの登録・変更・解除") { auth(null) }
            fun option(title: String, checked: Boolean, change: (Boolean) -> Unit) {
                panel.addView(CheckBox(this).apply { text=title; isChecked=checked
                    setOnCheckedChangeListener { _, value -> change(value); AutoUpdates.schedule(this@StoreActivity) }
                })
            }
            option("自動処理を一時停止", automatic.paused) { automatic.paused=it }
            option("APKを自動取得", automatic.download) { automatic.download=it }
            option("APK取得はWi-Fi接続時のみ", automatic.wifiOnly) { automatic.wifiOnly=it }
            label("起動時（15分以内は再確認省略）と約6時間ごとに確認します。省電力等で実行が遅れる場合があります。メタデータ確認はモバイル回線でも行います。自動APK取得は単一APKの検査後、導入済みアプリの更新のみ保存します。")
            label("自動整理は、自動取得分のうち導入完了済みの古い世代だけ。手動保存・インストール待ち・唯一の互換復旧APK・履歴は削除しません。安全な整理対象がなければ保留します。")
            label(if(AutoUpdates.notificationsEnabled(this)) "更新通知は有効です。" else "更新通知は無効です。保存APKは一覧から開けます。")
            button("通知を許可 / 通知設定") {
                if(android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),42)
                else startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:$packageName")))
            }
            label(automatic.state())
            return
        }
        if (shelf) { library(); return }
        val r = selected?.let(catalog::get)
        if (r != null) {
            val release = r.visible.firstOrNull { it.id == releaseId }
            if (release != null) { assets(r, release); return }
            releaseId = null
            page(r.repo)
            label(if (r.previews) "最新の対象：正式版＋プレリリース" else "最新の対象：正式版のみ")
            label(latestSummary(r))
            automatic.prefs.getString("repo:${r.repo}",null)?.let { label("自動/一括取得：$it") }
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
                        try { vault.forget(r.repo); if (catalog.remove(r.repo)) selected = null }
                        catch (e: ImportFailure) { notice(e.message.orEmpty()) }
                        render()
                    }.show()
            }
            return
        }
        selected = null
        page("AppDate")
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
        panel.addView(Button(this).apply {
            text="未取得の更新を一括ダウンロード"; isAllCaps=false
            val candidates=AutoUpdates.manualCandidates(this@StoreActivity)
            isEnabled=candidates.isNotEmpty() && !AutoUpdates.running.get()
            setOnClickListener {
                val bytes=candidates.sumOf { it.visible.first().assets.single().size }
                fun begin(override: Boolean) { AutoUpdates.manual(this@StoreActivity,override) { runOnUiThread { if(!isDestroyed && !busy) render() } } }
                if(automatic.wifiOnly && !networkState(this@StoreActivity).wifi)
                    AlertDialog.Builder(this@StoreActivity).setMessage("Wi-Fi限定を今回だけ解除します。最大約 ${bytes/1024/1024+1} MiB（解析含む）を使用します。モバイル通信で取得しますか？")
                        .setNegativeButton("戻る",null).setPositiveButton("今回のみ許可") { _,_ -> begin(true) }.show()
                else begin(false)
            }
        })
        // Background update progress belongs in Settings and notifications, not the app list.
        batch.getString("summary", null)?.let { label(it) }
        itemsPage(registrations) { repo -> catalog.get(repo)?.let(::addRow) }
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
                    val name = repoName(field.text.toString())
                    if (r != null && r.repo != name && catalog.get(name) == null) vault.forget(r.repo)
                    val updated = catalog.register(r?.repo, name, preview.isChecked)
                    selected = updated.repo; releaseId = null
                    dialog.dismiss(); render()
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
            label(meta?.let { "${it.summary}\n${it.sdk.label}\n${it.sdk.detail}\n${Installation.status(this, it)}" } ?: "未解析 · packageName / versionCode 不明")
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

