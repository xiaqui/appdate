# TASK — 自動更新確認・無人APK取得・通知インストール

## 現在の実行対象（唯一）
**docs/TASK_AUTO_UPDATE_DOWNLOAD_NOTIFY_JA.md** を今回の実装仕様・検収条件の正本とする。最新main、xiaqui/rinの共通資料、AGENTS.md、README.md、関連コード、既存PR・進行中branchを確認する。

今回の実装対象は「起動時・定期の自動更新確認、Wi-Fi優先のAPK自動取得、通知タップによる標準インストーラ起動、既存3世代保存上限との安全な連携」のみ。無人インストールやADB/Shizuku権限拡張は行わない。

## 着手と完了条件
1. 最新mainから専用branchを作る。同目的の既存PRがあれば重複作成しない。
2. PAT自動登録PR #4（work/pat-repo-auto-import）は**別タスクとして既にPR作成済み・実機検収待ち**。勝手にmergeせず、そのbranchの変更を本タスクへ混ぜない。未mergeでも現行mainの登録repoで動作すること。
3. 詳細仕様に沿って実装し、test・lint・CI・独立レビュー・P0/P1修正を実施する。Android 6〜10の互換性と保存データ保護を維持する。
4. commit/pushして独立PRを作り、**実機検収前にmergeしない**。必要なプレビューAPKのみGitHub Releasesへ配送し、検収項目と未完了を報告する。

## 前タスクと履歴
- 共通PATによるrepo自動登録: **PR #4**。仕様: `docs/TASK_PAT_REPO_AUTO_IMPORT_JA.md`。PRの検収・merge判断は別途継続し、このTASK切替で完了扱いにしない。
- Phase 1、PR #1/#2/#3、正式v0.4.2: 完了済み。以前のTASK本文は `docs/TASK_PHASE1_ARCHIVE_2026-11-14_JA.md` に保存済み。
