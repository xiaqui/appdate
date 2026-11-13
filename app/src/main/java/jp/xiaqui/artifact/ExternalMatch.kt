package jp.xiaqui.artifact

internal data class ExternalMatch(val repo: String, val revision: String, val evidence: String, val eligible: Boolean)

internal fun matchExternal(r: Registration, apk: ApkInfo, sha: String, sourceRepo: String?): ExternalMatch? {
    val trustedSource = sourceRepo?.equals(r.repo, true) == true
    val sameBytes = r.history.any { h -> h.assets.any { it.digest?.equals(sha, true) == true } }
    val packages = r.inspected.values.filter { it.pkg == apk.pkg }
    val signerMatches = apk.signer.isNotBlank() && packages.any { it.signer == apk.signer }
    val conflict = packages.isNotEmpty() && !signerMatches
    val evidence = when {
        conflict -> "packageName一致・署名不一致の可能性"
        apk.signer.isBlank() -> if (trustedSource || sameBytes || packages.isNotEmpty()) "署名未確認" else return null
        trustedSource && sameBytes -> "Release取得元・APK SHA-256一致"
        trustedSource -> "GitHub APIで確認したRelease取得元"
        sameBytes -> "登録済みRelease AssetのSHA-256一致"
        signerMatches -> "解析済みAPKのpackageName・署名一致"
        else -> return null
    }
    return ExternalMatch(r.repo,r.revision,evidence,!conflict && apk.signer.isNotBlank())
}
