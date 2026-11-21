# スクリーンショット保護範囲 作業記録

## 2026-11-21 PR #5

通常のStore画面全体に設定されていた `FLAG_SECURE` を解除し、共通PAT・個別PATの入力ダイアログだけを保護する。PR #4/#7統合後のmainへ追従し、自動登録、初回Release確認、改名保護、認証、履歴、保存APKを維持する。

- 通常の `StoreActivity` windowには `FLAG_SECURE` を設定しない。
- PATダイアログは作成時と表示時に専用helperで `FLAG_SECURE` を設定する。
- 計装試験で通常windowが非保護、機密dialogが保護、dialog表示中・終了後も通常windowが非保護であることをAPI 23/29で確認する。
- PR #6、Issue #8/#9、名称統一、UI全面改修は含めない。
- 固定署名previewを1件配送し、社長の実機検収前にマージしない。
