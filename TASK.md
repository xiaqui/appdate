# TASK — AppDate 統合の次工程

## 現在のWork実行対象（唯一）：PR #4の自動登録フィルタ

**既存PR #4（`work/pat-repo-auto-import`）** に、`docs/TASK_PAT_REPO_AUTO_IMPORT_JA.md` の2026-11-16改訂仕様を実装する。最新の `xiaqui/rin` README/STYLE、当repoのAGENTS.md、最新main、PR #4の最新headと差分を確認する。新規PRを乱立させず**PR #4を更新**すること。

**確定条件：公開/非公開を問わず、過去のGitHub ReleasesにAPK assetが一つ以上あるrepoだけを自動追加する。** 最新Releaseだけで判定せず、ページネーションと401/403/404/429・部分失敗・未確認状態を区別する。既存の手動登録、個別PAT、Release履歴、APK、現存登録は自動削除しない。repo同一性はGitHub IDで確認し、PR #7でmainに入った改名保護を壊さない。

1. 完了済み工程の再実装や環境構築を繰り返さず、PR #4の最新headを現在のmainに安全に追従させ、競合を解消する。
2. 対象絞り込みを実装し、Android 6/10の境界、単体/計装テスト、lint、Actions CI、独立レビューとP0/P1/P2修正まで進める。
3. 必要な場合に限って固定署名preview APKをGitHub Releaseへ一つ配送し、ビルド元SHA・署名・上書き互換性を検証する。
4. 成果をcommit/pushしPR #4に反映。社長の実機再検収前にmergeしない。既存の登録を一括削除する処理は追加しない。

## 並行して保全する作業

- **PR #5** スクリーンショット撮影修正：旧headを保持してmain取り込み・競合解消済み。通常画面は撮影可能、PAT入力ダイアログのみ保護。検証APKと実機スクショ検収が未完了のため**マージ保留**。
- **PR #6** 自動更新確認、Wi-Fi取得、通知：既存の実装と検証成果を保持。PR #4と混ぜない。main追従・独立レビュー・配送・実機検収が必要。
- **PR #7** 改名対応：CI、実機検収合格によりmainへマージ済み。繰り返さない。
- **後続独立タスク**：アプリ表示名/APKをAppDateに統一（packageNameと署名は変更しない）。配布元はGitHub repository ID、アプリはAndroid packageName、配布物はRelease/Asset IDという3層管理へ安全に移行する。

社長への指示は原則「TASKに従って完走」の一言でよい。別タスクへ移るときは、本書の「現在のWork実行対象」を明示的に差し替え、古いタスクを誤って再実行しない。
