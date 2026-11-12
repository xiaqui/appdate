package jp.xiaqui.artifact

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

class ApkProvider : FileProvider()

internal object Installation {
    @Suppress("DEPRECATION")
    fun inspect(context: Context, apk: File): String {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = context.packageManager.getPackageArchiveInfo(apk.path, flags)
            ?: throw ImportFailure("AndroidがこのAPKを読み取れません。")
        val signers = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        val fingerprints = signers.orEmpty().joinToString("\n") { signer ->
            MessageDigest.getInstance("SHA-256").digest(signer.toByteArray()).joinToString(":") { "%02X".format(it) }
        }
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        return "${info.packageName}\nversion ${info.versionName} ($code)\n署名 SHA-256\n${fingerprints.ifEmpty { "取得できませんでした" }}\n更新可否はOSが判定します。"
    }

    fun permissionSettings(context: Context): Intent? {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls())
            return Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        return null
    }

    @Suppress("DEPRECATION")
    fun installer(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val prompt = Intent(Intent.ACTION_INSTALL_PACKAGE, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val target = context.packageManager.queryIntentActivities(prompt, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo }
            .firstOrNull { it.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0 }
            ?: throw ImportFailure("端末の標準インストーラが見つかりません。")
        return prompt.setClassName(target.packageName, target.name)
    }
}
