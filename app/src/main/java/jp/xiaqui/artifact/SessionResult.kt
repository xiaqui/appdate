package jp.xiaqui.artifact

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast
import java.io.File

/** Android 6's stock UI does not accept a FileProvider URI. Stage with the public session API. */
internal fun stageMarshmallowInstall(context: Context, file: File): Int {
    val installer = context.packageManager.packageInstaller
    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
    params.setSize(file.length())
    val id = installer.createSession(params)
    try {
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, file.length()).use { out ->
                file.inputStream().use { transfer(it, out, MAX_INPUT) }
                session.fsync(out)
            }
        }
        return id
    } catch (failure: Exception) {
        installer.abandonSession(id)
        throw failure
    }
}

@android.annotation.SuppressLint("UnspecifiedImmutableFlag")
internal fun requestMarshmallowInstall(context: Context, file: File) {
    val installer = context.packageManager.packageInstaller
    val id = stageMarshmallowInstall(context, file)
    try {
        installer.openSession(id).use { session ->
            // API 23 requires a mutable result PendingIntent so PackageInstaller can supply status.
            val callback = PendingIntent.getBroadcast(context, id, Intent(context, SessionResult::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT)
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            session.commit(callback.intentSender)
        }
    } catch (failure: Exception) {
        installer.abandonSession(id)
        throw failure
    }
}

internal fun installStatus(status: Int) = when (status) {
    PackageInstaller.STATUS_SUCCESS -> "インストール完了。"
    PackageInstaller.STATUS_FAILURE_ABORTED -> "インストールをキャンセルしました。"
    PackageInstaller.STATUS_FAILURE_STORAGE -> "インストール先の容量が不足しています。"
    PackageInstaller.STATUS_FAILURE_BLOCKED -> "提供元の許可または端末ポリシーで拒否されました。"
    PackageInstaller.STATUS_FAILURE_CONFLICT -> "既存パッケージと競合しています。署名を確認してください。"
    PackageInstaller.STATUS_FAILURE_INVALID -> "OSがAPKを不正と判定しました。"
    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "OS・端末と互換性がありません。"
    else -> "OSから詳しいインストール結果を取得できません。"
}

class SessionResult : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val prompt = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            try { context.startActivity(prompt.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (_: RuntimeException) { Toast.makeText(context, "OS確認画面を開けません。", Toast.LENGTH_LONG).show() }
        } else {
            val message = installStatus(status)
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
}
