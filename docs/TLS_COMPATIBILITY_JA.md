# Android 6/7 TLS信頼互換性

## 対象と境界

AppDate 0.9.1（versionCode 18）は、古いAndroidのシステムCAに含まれない場合がある公式ISRG Root X1をアプリへ同梱する。取得元はLet’s Encrypt公式の `https://letsencrypt.org/certs/isrgrootx1.pem`、証明書SHA-256は次の値で固定する。

`96BCEC06264976F37460779ACF28C5A7CFE8A3C0AAE11A8FFCEE05C0BDDF08C6`

- API 24以降はNetwork Security ConfigでシステムCAと同梱CAを併用する。
- API 23はNetwork Security Configの対象外なので、同じ2系統を合成したTrustManagerをAppDateが生成し、AppDateが開くHTTPS接続だけへ設定する。
- システムCAを置換しない。全ユーザーCA、未知CA、平文HTTPは信頼しない。
- HttpsURLConnection標準のホスト名検証を置換しない。証明書チェーン、有効期限、署名の検証も無効化しない。
- GitHub APIへ付けたPATは従来どおり最初の同一originだけに限定し、Assetの別origin転送先へ渡さない。

## CIと実機の分担

CIでは同梱証明書のfingerprint・subject・有効性、平文拒否、システムCA優先・同梱CA fallback・双方拒否、既存のHTTPS転送制約をAPI 23/24/29/34で確認する。CI成功を旧端末の実回線成功とはみなさない。

実機は、既存データを消さず固定署名版を上書きし、Z4T（Android 7）と手動CA未導入のZ3TC（Android 6）で同じGitHub Release APKを手動解析・自動取得する。登録、PAT、履歴、保存APK、Wi-Fi制限が保持されることを確認する。既存の端末CAは削除しない。
