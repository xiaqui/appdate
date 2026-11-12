package jp.xiaqui.artifact

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.*
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future

class MainActivity : Activity() {
    private val worker = Executors.newCachedThreadPool()
    private lateinit var panel: LinearLayout
    private lateinit var session: File
    private var task: Future<*>? = null
    private var generation = 0
    private var source: Uri? = null
    private var diagnostic = ""
    private var saved: File? = null
    private var details = ""
    private var reading: android.os.CancellationSignal? = null
    private val storeDir get() = File(filesDir, "apks")

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = File(cacheDir, "imports")
        if (!started) {
            root.deleteRecursively()
            started = true
        }
        session = File(root, UUID.randomUUID().toString())
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val margin = (16 * resources.displayMetrics.density).toInt()
            setPadding(margin, margin, margin, margin)
        }
        setContentView(ScrollView(this).apply { addView(panel) })
        val previous = state?.getString("apk")?.let { File(storeDir, it) }?.takeIf { it.isFile }
        if (previous != null) {
            diagnostic = state?.getString("diagnostic").orEmpty()
            showSaved(previous)
        } else if (state != null) {
            source = state.getParcelable<Uri>("source") ?: runCatching { Incoming.uri(intent) }.getOrNull()
            diagnostic = state?.getString("diagnostic").orEmpty()
            home("読み込みを再開できます。")
        } else receive(intent)
    }

    private fun receive(incoming: Intent) {
        source = try { Incoming.uri(incoming) } catch (_: RuntimeException) { null }
        diagnostic = Incoming.diagnostics(incoming, source)
        if (source != null) load() else home(if (incoming.action == Intent.ACTION_MAIN) "" else "受信内容からファイルまたはURLを取得できませんでした。")
    }

    override fun onSaveInstanceState(state: Bundle) {
        super.onSaveInstanceState(state)
        state.putString("apk", saved?.name)
        state.putString("diagnostic", diagnostic)
        state.putParcelable("source", source?.takeIf { it.scheme == "content" })
    }

    private fun page(title: String) {
        panel.removeAllViews()
        panel.addView(TextView(this).apply { text = title; textSize = 22f })
    }

    private fun text(value: String) {
        if (value.isNotEmpty()) panel.addView(TextView(this).apply { text = value; setTextIsSelectable(true) })
    }

    private fun button(label: String, action: () -> Unit) {
        panel.addView(Button(this).apply { text = label; setOnClickListener { action() } })
    }

    private fun home(message: String = "") {
        saved = null
        page("Artifact Installer")
        text(message)
        if (source != null) button("再試行") { load() }
        button("ZIP / APK を開く") {
            launch(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PICK)
        }
        button("保存したAPK") {
            page("保存したAPK")
            val files = storeDir.listFiles().orEmpty().filter { it.isFile }.sortedByDescending { it.lastModified() }
            if (files.isEmpty()) text("保存したAPKはありません。")
            files.forEach { file -> button(file.name) { showSaved(file) } }
            button("戻る") { home() }
        }
        text(diagnostic)
    }

    private fun cancel() {
        generation++
        task?.cancel(true)
        val old = session
        val signal = reading
        worker.execute { old.deleteRecursively(); signal?.cancel() }
        session = File(old.parentFile, UUID.randomUUID().toString())
        home("処理を取り消しました。")
    }

    private fun <T> work(title: String, operation: () -> T, done: (T) -> Unit) {
        val current = ++generation
        val directory = session
        page(title)
        button("キャンセル") { cancel() }
        task = worker.submit {
            try {
                val result = operation()
                runOnUiThread { if (generation == current && !isDestroyed) done(result) }
            } catch (e: Exception) {
                directory.deleteRecursively()
                val reason = when (e) {
                    is ImportFailure -> e.message.orEmpty()
                    is java.util.zip.ZipException -> "ZIP / APKが破損しているか、形式が異なります。"
                    is SecurityException -> "ファイルの読み取り許可がありません。ファイルを選び直してください。"
                    else -> "読み込みに失敗しました。通信、空き容量、ファイル形式を確認してください。"
                }
                runOnUiThread { if (generation == current && !isDestroyed) home(reason) }
            }
        }
    }

    private fun load() {
        val uri = source ?: return
        saved = null
        val directory = session
        val staged = File(directory, "input.apk")
        val signal = android.os.CancellationSignal()
        reading = signal
        work("読み込み中…", {
            directory.deleteRecursively()
            if (!directory.mkdirs()) throw ImportFailure("一時領域を作成できません。")
            SourceReader(contentResolver).read(uri, staged, signal)
            Archive(staged).inspect()
        }) { index ->
            when {
                index.standalone -> persist(null)
                index.choices.size == 1 -> persist(index.choices.single())
                else -> {
                    page("保存するAPKを選択")
                    index.choices.forEach { entry -> button(entry) { persist(entry) } }
                    button("キャンセル") { cancel() }
                    text(diagnostic)
                }
            }
        }
    }

    private fun persist(entry: String?) {
        val directory = session
        val staged = File(directory, "input.apk")
        work("APKを確認中…", {
            val candidate = if (entry == null) staged else File(directory, "selected.apk").also {
                Archive(staged).extract(entry, it)
                if (!Archive(it).inspect().standalone) throw ImportFailure("選択したファイルはAPKではありません。")
            }
            val metadata = Installation.inspect(this, candidate)
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val dest = ApkStore(storeDir).save(candidate, entry ?: "artifact.apk")
            directory.deleteRecursively()
            dest to metadata
        }) { (file, metadata) -> confirm(file, metadata) }
    }

    private fun showSaved(file: File) {
        work("APKを確認中…", { Installation.inspect(this, file) }) { confirm(file, it) }
    }

    private fun confirm(file: File, metadata: String, message: String = "") {
        saved = file
        details = metadata
        page("保存済みAPKの確認")
        text(metadata)
        text(file.name)
        text(message)
        button("標準インストーラで開く") {
            val settings = Installation.permissionSettings(this)
            when {
                android.os.Build.VERSION.SDK_INT == 23 -> work("標準インストーラに転送中…", {
                    requestMarshmallowInstall(this, file)
                }) { confirm(file, metadata) }
                settings != null -> launch(settings, SETTINGS)
                else -> try { launch(Installation.installer(this, file), INSTALL) }
                    catch (e: ImportFailure) { confirm(file, metadata, e.message.orEmpty()) }
            }
        }
        button("他のファイルを開く") { source = null; home() }
        text(diagnostic)
    }

    private fun launch(intent: Intent, code: Int) {
        try { startActivityForResult(intent, code) }
        catch (_: RuntimeException) {
            val message = "対応するシステム画面を開けません。端末の設定を確認してください。"
            saved?.let { confirm(it, details, message) } ?: home(message)
        }
    }

    @Deprecated("Platform activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK && resultCode == RESULT_OK && data?.data != null)
            receive(Intent(Intent.ACTION_VIEW).setDataAndType(data.data, data.type))
        else if (requestCode == SETTINGS || requestCode == INSTALL)
            saved?.let { confirm(it, details, "保存済みAPKは再度開けます。") }
    }

    override fun onDestroy() {
        generation++
        task?.cancel(true)
        val old = session
        val signal = reading
        worker.execute { old.deleteRecursively(); signal?.cancel() }
        session = File(old.parentFile, UUID.randomUUID().toString())
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        private var started = false
        private const val PICK = 1
        private const val SETTINGS = 2
        private const val INSTALL = 3
    }
}
