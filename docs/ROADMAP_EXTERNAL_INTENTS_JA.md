# 外部Intent連携ロードマップ（次期作業）

作成: 2026-11-14
状態: **次期作業・未着手**。現行TASKのPAT永続化・共通認証・日時修正・トップ整理・旧Installer保存管理統合、およびRelease配送とmainマージを妨げない。着手時に最新mainと現行コードを再確認する。

## 目的
ブラウザのGitHub Releasesページ、Release Asset、ダウンロード済みAPK/ZIP、ファイル管理アプリの「開く」「共有」からRelease Storeを選び、従来の単発インストールだけでなく、登録済みアプリの更新・履歴と自然につなぐ。

## 現状（work/phase1-installerを2026-11-14確認）
- `AndroidManifest.xml` の公開 `MainActivity` に `ACTION_SEND`（text/plain、APK/ZIP等）と `ACTION_VIEW`（content URIのAPK/ZIP、GitHub Releases/Actions URL、HTTP(S) APK/ZIP URL）向けintent-filterがある。
- 通常起動は `StoreActivity`。外部Intentは `MainActivity` が `Incoming.uri(intent)` 等で処理し、旧Installerの読み込み・検査・保存・標準インストーラへ進む。
- **外部から受け取ったAPKを登録済みrepoやRelease Storeの更新履歴へ自動関連付けする処理は未確認／未整備。**
- Intent Filterの存在は、ブラウザのダウンロードボタン押下時に必ず候補へ出ることを保証しない。ブラウザが直接ダウンロードする場合、インストール確認やダウンロード完了通知からの経路を別途検証する。

## Phase A：経路ごとの実機検証・契約固定
1. Android 6/7/8/9/10で、ブラウザからGitHub Releasesページを開く、Release AssetのAPKリンクをタップする、ダウンロード済みAPK/ZIPをファイル管理アプリから開く、共有シートで渡す、の各経路を確認する。
2. どの経路でRelease Storeが候補に出るか、`ACTION_VIEW` / `ACTION_SEND`、URIスキーム、MIME、権限、実際の遷移先を記録。ブラウザ依存を「確実」と表現しない。
3. GitHub Releases/ActionsのURLフィルタと一般APK/ZIP URLフィルタの誤捕捉・過剰な関連付け・未対応URLを確認。Androidのアプリリンク・既定アプリ設定に配慮する。
4. content URIの一時権限、`onNewIntent`、再起動、キャンセル、巨大ZIP、複数APK、ファイル形式偽装を検証する。

## Phase B：統合した受け取り体験
1. 外部Intentは単一の入口で安全に解析し、URL／ローカルAPK／ZIP／GitHub Releaseの種別を識別。旧Installerの検査・安全対策を再利用する。
2. APKのpackageName、versionCode、署名証明書、SHA-256、取得元を検査。登録済みアプリと照合できた場合は「更新候補」「同一版」「旧版」「署名不一致の可能性」を表示し、関連付けは根拠を示して利用者に確認する。
3. 未登録・出所不明のAPKは**単発インストール**として扱い、勝手にrepo登録や既存登録の書換えをしない。
4. GitHub Releasesページ／Asset URLから起動した場合は、登録済みrepo・Release・Assetとの関連付けを優先し、必要なら共通PATで認証。外部URLにPATを付けない。
5. 保存済みAPK画面と取得履歴へ出所・関連repo・検証結果を表示し、既存の実ファイルと履歴を消さずに移行する。旧Installerとの機能重複を解消する。
6. 標準Androidインストーラのユーザー確認を維持し、無人インストールや署名・ダウングレード制約の回避はしない。

## Phase C：検収・配送
- GitHub Releaseページ→アプリ選択、APK URL→アプリ選択、ダウンロード済みAPK→開く、ZIP→APK選択、共有→受信を端末ごとに確認。
- 既存登録に紐づく更新、未登録の単発導入、同一版・旧版・署名不一致、URL失敗、権限拒否、キャンセル、履歴保存をテスト。
- 単体テスト、lint、API 23/29 CI、独立レビュー、P0/P1修正。署名付きAPKを**GitHub ReleasesのAsset**として配送し、端末で上書き更新を検収。
- 次期作業用PRを作成し、既存Phase 1のmainマージとは切り離す。マージ条件はその時点のユーザー指示を優先。

## 優先順位
- P0: 外部Intentの既存機能を壊さない、URI権限・セキュリティ、既存APK履歴を失わない。
- P1: ダウンロード済みAPK/ZIPの確実な受信、登録済みアプリとの安全な関連付け、URL受信。
- P2: ブラウザごとの導線改善、関連付け候補の自動提案、UI簡素化。

## 非目標
ブラウザのダウンロード操作への強制割り込み、すべてのブラウザで候補表示の保証、Android標準インストーラの置換、無人インストール。
