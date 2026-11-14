# TASK — 共通PATによるrepo一括自動登録

## 現在の実行対象（唯一）
**docs/TASK_PAT_REPO_AUTO_IMPORT_JA.md** を今回の実装仕様・検収条件の正本とする。最新mainとxiaqui/rin/README.mdに従う共通資料、AGENTS.md、README.md、関連コード、既存PR、進行中branchを確認してから着手する。

今回の依頼「xiaqui/artifact の最新TASK.mdに従って完走」は、**この自動登録タスクのみ**を意味する。過去のPhase 1や外部Intent連携を再実装したり、別のTODOを自動的に消化したりしない。

## 進め方と完了条件
1. 最新mainから自動登録専用の新規branchを作る。進行中の `work/external-intents` と変更を混在させない。既存PRが同目的で存在すれば重複作成せず現状を確認する。
2. 共通PATの保存・変更を契機に、列挙APIが返す全repoを機械的に登録する。再取得は追加専用。細部は上記仕様書に従い、手動登録・削除、共通/個別PAT、既存データを保持する。
3. テスト・lint・CI・独立レビュー・P0/P1修正を完了する。実機未確認を合格扱いしない。
4. 実装をcommit/pushし、PRを作成する。**PRはマージせず実機検収待ちで停止**する。結果としてPR URL、CI、実機検収項目、GitHub API列挙制限を報告する。
5. APKの配送が必要ならGitHub Releasesを使い、xiaqui/rin/STYLE.mdの版入り命名・Release乱造防止を守る。単なるpushや文書更新では新Releaseを作らない。

## 現在の進捗

専用branch `work/pat-repo-auto-import`、[PR #4](https://github.com/xiaqui/artifact/pull/4) で実装。実機未検収・未マージ。検証と配送の最新結果はPRおよび `docs/WORK_PAT_REPO_IMPORT_JA.md` を参照。外部Intent branchは保持。

## 過去タスク
Phase 1、PR #1/#2/#3、v0.4.2正式Releaseは完了済み。以前の詳細・実機観察は `docs/TASK_POST_PHASE1_JA.md`、`README.md`、GitHub履歴を参照する。下記旧TASK本文は履歴として保存し、**現在の実行指示として扱わない**。


