package jp.xiaqui.artifact

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID

internal val updateCheckLock = Any()
internal val batchProcessId: String = UUID.randomUUID().toString()

internal class CoalescedRequests {
    private val requested = AtomicBoolean(false)
    private val worker = AtomicBoolean(false)
    fun request(): Boolean { requested.set(true); return worker.compareAndSet(false,true) }
    fun consume(): Boolean = requested.getAndSet(false)
    fun releaseAndReacquireIfPending(): Boolean {
        worker.set(false)
        return requested.get() && worker.compareAndSet(false,true)
    }
}

internal val appForeground = ForegroundGate()

internal class ForegroundGate {
    private var active = 0
    private var background = true
    @Synchronized fun started(): Boolean {
        active++
        return background.also { background = false }
    }
    @Synchronized fun stopped(changingConfiguration: Boolean) {
        active = (active - 1).coerceAtLeast(0)
        if (active == 0 && !changingConfiguration) background = true
    }
}

internal enum class CheckMethod(val label: String) {
    MANUAL("手動確認"), STARTUP("自動確認（起動時）"), PERIODIC("自動確認（定期）")
}

internal enum class CheckPhase { RUNNING, COMPLETE, INTERRUPTED, FAILED }

internal data class BatchCounts(
    val total: Int,
    val checked: Int = 0,
    val ok: Int = 0,
    val updates: Int = 0,
    val same: Int = 0,
    val failed: Int = 0
)

internal class BatchAccumulator(private val total: Int) {
    private var checked = 0
    private var ok = 0
    private var updates = 0
    private var same = 0
    private var failed = 0

    fun record(outcome: String?) {
        checked++
        if (outcome == null || outcome == "失敗") failed++ else {
            ok++
            when (outcome) {
                "更新候補" -> updates++
                "同一版" -> same++
            }
        }
    }

    fun counts() = BatchCounts(total, checked, ok, updates, same, failed)
}

internal fun batchCounts(repos: List<String>, check: (String) -> StoreRow?): BatchCounts {
    val result = BatchAccumulator(repos.size)
    for (repo in repos) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        result.record(check(repo)?.outcome)
    }
    if (Thread.currentThread().isInterrupted) throw InterruptedException()
    return result.counts()
}

internal fun countSummary(counts: BatchCounts) =
    "成功 ${counts.ok}（更新 ${counts.updates}・同一 ${counts.same}）／失敗 ${counts.failed}"

internal fun formatBatchResult(
    method: CheckMethod,
    phase: CheckPhase,
    time: String,
    counts: BatchCounts,
    reason: String = "",
    previous: String = ""
): String = buildString {
    append(method.label).append(" · ")
    append(when (phase) {
        CheckPhase.RUNNING -> "確認中 ${counts.checked}/${counts.total}"
        CheckPhase.COMPLETE -> if (counts.failed > 0) "完了（一部失敗）" else "完了"
        CheckPhase.INTERRUPTED -> "未完了"
        CheckPhase.FAILED -> "失敗"
    })
    append(" · ").append(time)
    append('\n').append(countSummary(counts))
    if (reason.isNotBlank()) append("\n").append(reason)
    if (phase != CheckPhase.COMPLETE && previous.isNotBlank())
        append("\n前回の確認結果 · ").append(previous)
}

internal class BatchResults(context: Context) {
    private val prefs = context.getSharedPreferences("batch-result", Context.MODE_PRIVATE)

    fun display(): String? {
        recoverInterrupted()
        val summary = prefs.getString("summary", null)?.takeIf { it.isNotBlank() } ?: return null
        return if (prefs.getInt("schema", 0) >= 2) summary else "前回の確認結果 · $summary"
    }

    fun begin(method: CheckMethod, total: Int): BatchRun {
        recoverInterrupted()
        val legacy = prefs.getInt("schema", 0) < 2
        val current = prefs.getString("summary", null).orEmpty()
        val previous = prefs.getString("last-complete", null)
            ?: current.takeIf { legacy || prefs.getString("phase", "") == CheckPhase.COMPLETE.name }.orEmpty()
        if (legacy && current.isNotBlank()) prefs.edit().putString("last-complete", current).commit()
        val run = BatchRun(this, method, System.currentTimeMillis(), BatchAccumulator(total), previous)
        save(run, CheckPhase.RUNNING)
        return run
    }

    private fun save(run: BatchRun, phase: CheckPhase, reason: String = "") {
        val now = System.currentTimeMillis()
        val counts = run.accumulator.counts()
        val summary = formatBatchResult(run.method, phase, StoreTime.local(if (phase == CheckPhase.RUNNING) run.started else now), counts, reason, run.previous)
        val edit = prefs.edit()
            .putInt("schema", 2)
            .putString("method", run.method.name)
            .putString("phase", phase.name)
            .putLong("started", run.started)
            .putLong("finished", if (phase == CheckPhase.RUNNING) 0L else now)
            .putInt("total", counts.total)
            .putInt("checked", counts.checked)
            .putInt("ok", counts.ok)
            .putInt("updates", counts.updates)
            .putInt("same", counts.same)
            .putInt("failed", counts.failed)
            .putString("summary", summary)
        if (phase == CheckPhase.RUNNING) edit.putString("process", batchProcessId)
        else edit.remove("process")
        if (phase == CheckPhase.COMPLETE) edit.putString("last-complete", summary)
        edit.commit()
    }

    private fun recoverInterrupted() {
        if (prefs.getInt("schema",0) < 2 ||
            prefs.getString("phase","") != CheckPhase.RUNNING.name ||
            prefs.getString("process","") == batchProcessId) return
        val method=runCatching {
            CheckMethod.valueOf(prefs.getString("method",CheckMethod.MANUAL.name).orEmpty())
        }.getOrDefault(CheckMethod.MANUAL)
        val counts=BatchCounts(
            prefs.getInt("total",0),
            prefs.getInt("checked",0),
            prefs.getInt("ok",0),
            prefs.getInt("updates",0),
            prefs.getInt("same",0),
            prefs.getInt("failed",0)
        )
        val now=System.currentTimeMillis()
        val summary=formatBatchResult(method,CheckPhase.INTERRUPTED,StoreTime.local(now),counts,
            "前回処理はプロセス終了により中断しました。登録・履歴・保存APKは保持しています。",
            prefs.getString("last-complete",null).orEmpty())
        prefs.edit().putString("phase",CheckPhase.INTERRUPTED.name).putLong("finished",now)
            .remove("process").putString("summary",summary).commit()
    }

    internal fun progress(run: BatchRun) = save(run, CheckPhase.RUNNING)
    internal fun finish(run: BatchRun) = save(run, CheckPhase.COMPLETE)
    internal fun stop(run: BatchRun, interrupted: Boolean, reason: String) =
        save(run, if (interrupted) CheckPhase.INTERRUPTED else CheckPhase.FAILED, reason)
}

internal class BatchRun(
    private val results: BatchResults,
    internal val method: CheckMethod,
    internal val started: Long,
    internal val accumulator: BatchAccumulator,
    internal val previous: String
) {
    fun record(row: StoreRow?) { accumulator.record(row?.outcome); results.progress(this) }
    fun complete() = results.finish(this)
    fun fail(error: Exception) = results.stop(this, error is InterruptedException,
        if (error is InterruptedException) "中断しました。登録・履歴・保存APKは保持しています。" else storeError(error))
}
