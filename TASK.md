# TASK — リポジトリ改名時の登録データ移行

## 現在のWork実行対象（唯一）

**`docs/TASK_REPO_RENAME_MIGRATION_JA.md`** を今回の実装・検証・完了条件の正本とする。社長がWorkへ「TASKに従って完走」と指示した場合は、**この改名対応タスクだけ**を実行する。最新の `xiaqui/rin` README/STYLE と、このrepoの最新main・AGENTS.md・README.md・当該docs・open PR・進行branchを確認し、仕様をコピペで再入力させない。

GitHub上のリポジトリ自体の改名、visibilityの変更、既存PRのマージは**実行しない**。現時点のrepo名は `xiaqui/artifact` のまま。既存登録・保存APK・履歴・認証情報を壊さず、改名に備える。

## 着手から完了まで

1. 同目的の既存PRの有無を最新のGitHubで確認。なければ最新mainから専用branchを作成する。
2. 詳細仕様に従い実装し、単体・必要な計装テスト、lint、GitHub Actions CI、独立レビュー、P0/P1修正まで自走する。Android 6〜10を維持し、PAT・署名鍵を露出させない。
3. commit/push、独立PR作成または更新、検収用APKが必要なら固定鍵署名と版を確認して必要最小限のGitHub Release配送まで行う。文書変更だけでReleaseを量産しない。
4. PR URL・最新head・CI・レビュー・移行試験結果・未確認の実機検収項目を報告する。**社長の実機検収前にマージしない**。

## 他の進行中作業（今回の実行対象外）

- **PR #4** 共通PATによるrepo自動登録。仕様: `docs/TASK_PAT_REPO_AUTO_IMPORT_JA.md`。公開/非公開の区別なくAPK付きReleaseのあるrepoを自動登録する方向へ仕様を更新する課題は、PR #4側で扱う。このTASKからbranchを変更・mergeしない。
- **PR #5** スクリーンショット保護範囲。別タスク。変更・mergeしない。
- **PR #6** 起動時・定期自動確認、Wi-Fi APK取得、通知など。仕様: `docs/TASK_AUTO_UPDATE_DOWNLOAD_NOTIFY_JA.md`。すでに別PRで進行しているため、今回のWork指示では再実装・変更・mergeしない。
- Phase 1、PR #1/#2/#3、正式v0.4.2は完了済み。旧仕様は `docs/TASK_PHASE1_ARCHIVE_2026-11-14_JA.md` に保持。

今後別タスクへ切り替えるときは、この「現在のWork実行対象」を明示的に差し替える。Workが古いTASKを推測で再実行しない。
