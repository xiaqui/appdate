# スクリーンショット保護範囲 作業記録

## 2026-11-21 PR #5

通常のStore画面全体に設定されていた `FLAG_SECURE` を解除し、共通PAT・個別PATの入力ダイアログだけを保護する。PR #4/#7統合後のmainへ追従し、自動登録、初回Release確認、改名保護、認証、履歴、保存APKを維持する。

- 通常の `StoreActivity` windowには `FLAG_SECURE` を設定しない。
- PATダイアログは作成時と表示時に専用helperで `FLAG_SECURE` を設定する。
- 計装試験で通常windowが非保護、機密dialogが保護、dialog表示中・終了後も通常windowが非保護であることをAPI 23/29で確認する。
- PR #6、Issue #8/#9、名称統一、UI全面改修は含めない。
- 固定署名previewを1件配送し、社長の実機検収前にマージしない。

### 完了確認

- source head: `e9644fd1b2de4c8981846f76fcc596891df203a2`。最新main `643016575090c278301a0c57a02c83c7bfacedf5` を取り込み、PR #5へ反映した。
- 独立レビューで未解決P0/P1/P2なし。`MainActivity` の既存保護は受信・インストーラ画面の別範囲として維持し、TASK指定のLauncher配下Store画面だけを変更した。
- Actions: https://github.com/xiaqui/appdate/actions/runs/44890593110 。unit、lint、固定鍵署名、API 23/29計装試験、0.4.2からの上書き、保存APK領域保持が成功。
- 初回API 29 jobはMaven依存取得の一時失敗でテスト開始前に停止。コードを変更せず失敗jobだけを再実行し、同一sourceで成功した。
- Preview: https://github.com/xiaqui/appdate/releases/tag/store-preview-e9644fd1b2de
- APK: `ReleaseStore-v0.7.1-preview.apk`、2,133,884 bytes、SHA-256 `8f08585cc049df77a5b7918c6d8d903935264d28731797c6c509e35f07768187`。applicationId `jp.xiaqui.artifact.store.preview`、versionName 0.7.1、versionCode 11、minSdk 23。
- v1/v2固定署名、証明書SHA-256 `dcfd2c8122274ec4148473c96b1853989c8d22e58e445d6ec4889ad5b34dffb2`。Release Asset再取得・バイト一致確認済み。
- PR #5はopen・未マージ。社長の通常画面撮影、PATダイアログ撮影不可、終了後の再撮影、既存データ保持の実機検収を待つ。
