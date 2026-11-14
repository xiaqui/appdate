# 共通PAT repo自動登録 作業記録

2026-11-14。最新TASK.mdの唯一の対象を実装。基準mainは `cf9163d0f875ad2ec9891341d4ae7251054ce3b6`、branchは `work/pat-repo-auto-import`、PRは [#4](https://github.com/xiaqui/artifact/pull/4)。Phase 1・正式Release・外部Intent作業を再実装していない。

## 実装

- 共通PAT保存/変更後と明示的な再取得で、固定GitHub APIをページ取得。全repoを追加専用で登録。
- 部分成功、件数、未完了、認証/権限/404/レート制限/通信/空応答を区別。生レスポンスやトークンを保存しない。
- GitHub IDとowner/repoで照合。既存設定・個別PAT・保存APKを保持。IDで名前変更を検出した際は現在名を記録し、既存キーを保持。名前再利用でIDが違う場合は保留して既存情報を守る。
- 成功した更新確認で対象APKがなければ「配布なし」。取得失敗は別表示。
- APKは0.4.3 / versionCode 8。外部Intent試作0.5.0 / code 7とは別系統で、mainからの変更のみ。固定鍵・applicationId・minSdk 23を維持。

## 検証・レビュー

GitHub Actionsで単体テスト、lint、署名付きビルド、API 23/29端末試験を実行。列挙APIは人工応答を用い、複数ページ・公開/非公開・アーカイブ・Releaseなし・途中失敗・権限不足・重複・再取得・再追加・PAT変更・安定ID・旧形式を検証。端末試験は個別PAT/履歴の保持とプロセス終了・再起動後の登録保持を追加。

独立レビューで指摘されたP1（試験seedの全体試験への混入）、P2（空応答中断を成功扱いする境界、PAT類似語を含む正当repo名の誤拒否）を修正。最終CI結果と配送識別子はPRへ記録する。

## 実機検収待ち

実PATを利用したGitHub側の列挙範囲、組織承認、実端末の保存→自動追加/変更→再取得/通信中断/端末再起動を確認する。API 23/29エミュレータの成功は実機合格ではない。PRはマージしない。

GET /user/reposが返すrepoはPATで閲覧可能な全repoと同一とは限らない。Metadata読み取りで列挙し、private ReleaseにはContents読み取りが必要。未列挙repoは従来通り手動登録可能。通常push・docs更新ではReleaseを作らず、検収用APKは明示配送で一度公開する。
