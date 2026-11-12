package jp.xiaqui.artifact

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast
import java.io.File

/** Android 6's stock UI does not accept a FileProvider URI. Stage with the public session API. */
@android.annotation.SuppressLint("UnspecifiedImmutableFlag")
internal fun requestMarshmallowInstall(context: Context, file: File) {
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

class SessionResult : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val prompt = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            try { context.startActivity(prompt.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (_: RuntimeException) { Toast.makeText(context, "OS確認画面を開けません。", Toast.LENGTH_LONG).show() }
        } else {
            val message = if (status == PackageInstaller.STATUS_SUCCESS) "インストールしました。" else "インストールは完了しませんでした。APKは保存されています。"
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
}
