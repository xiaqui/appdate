# PR #6 自動更新・Issue #8 統合作業記録

対象: `work/auto-update-notify`。2026-11-21の最新mainを基準に、PR #4/#5/#7の統合済み成果を保持し、PR #6の自動更新本体とIssue #8の3スイッチだけを統合する。下スワイプ、Issue #9、表示名の全面統一、UI全面改修は含めない。

## 実装

- 起動時の自動更新確認（初期値ON）、約6時間ごとの定期確認（初期値OFF）、Wi-Fi接続時のAPK自動取得（初期値ON）を独立保存する。
- 定期OFF時はJobScheduler予約を解除し、ON時だけ永続予約する。起動時と定期処理は、それぞれのスイッチを開始時・処理中に再確認する。
- 旧「自動処理を一時停止」がONなら3項目をOFFへ、旧自動取得OFFは新自動取得OFFへ一度だけ移行する。以後は3項目の明示値を保持する。
- 自動取得はWi-Fi接続時だけ。OFFでもメタデータ確認・一覧・手動取得を維持する。手動一括取得はWi-Fi以外で通信量を示し、その回だけの許可を求める。
- APK実体、packageName、versionCode、署名、minSdk、端末の実インストール状態を照合し、互換更新だけを保存・通知する。インストールはOS確認画面から利用者が行う。
- 既存の保存上限を維持し、自動取得済みの安全な旧世代だけを整理する。手動保存、待機中、開いているAPK、唯一の復旧候補と履歴は保護する。

## 検証計画

GitHub Actionsでunit、lint、固定鍵署名、API 23/29計装、正式0.4.2からの上書きと保存領域保持を確認する。設定移行、3スイッチの独立性・再生成後保持、定期予約の登録解除、Wi-Fi制約、自動取得OFF時の手動経路、通知遷移、通信中断、破損・署名・SDK・容量保護を対象にする。

独立レビューでP0/P1/P2を確認し、必要な修正後に再検証する。最新headのpreview APKをGitHub Releaseへ1件だけ配送し、source SHA、APK SHA-256、版、minSdk、証明書SHA-256、上書き可否を記録する。物理端末のDoze・OEM省電力・Wi-Fi/モバイル切替・実PAT・通知からOS確認・既存データ保持は社長の実機検収事項とし、合格前はマージしない。

## 2026-11-22 CI根本修正と検収成果

- 失敗run `44992026161` / `44991601777` のログを確認。全端末で本体テスト、再起動後復帰、表示画像抽出、0.4.2からの上書き、保存領域のsentinel確認まで成功し、末尾の`versionCode=12`固定照合だけが0.8.1/code 13と不一致で失敗していた。
- 従来は各device jobが`connectedDebugAndroidTest`と`installDebug`で本体を再生成・差し替えていた。修正後はverifyで署名済み本体APKと計装APKを一度だけ生成し、manifestと同じbundleに保存。deviceはADBだけでその本体APKを導入し、全計装、再起動、表示、旧版上書き、データ保持を検証する。
- manifestはsource SHA、アプリソースidentity、applicationId、versionCode/name、証明書SHA-256、本体・計装APKのSHA-256/sizeを保持する。workflow/deviceスクリプトのみの変更では、元runのverify成功・artifact有効期限・現在ソースidentity・署名・版・hashを確認してbundleを再利用する。明示run ID指定も可能。不一致や取得不能は停止する。
- run `44993297128`: verify成功。本体APK SHA-256 `28cc3ebe4dc65bd38928652cc6c6e53f9cc7c65888266c52ea0b6bc7d752d864`。API 23/29/34 device成功。
- run `44993805366`: run `44993297128`のbundleを自動再利用。verifyはskip、reuse成功、API 23/29/34 device成功。workflow変更に伴うunit/lint/APK再ビルドを削減した。
- run `44994826850`: 同じbundleを再利用し、verifyはskip、API 23/29/34 device成功、版ゲート成功、Release再取得とバイト一致に成功。検収用Release [`store-preview-fa230d039ad8`](https://github.com/xiaqui/appdate/releases/tag/store-preview-fa230d039ad8)へ`ReleaseStore-v0.8.1-preview.apk` 1件と、そのAPK Asset digest/sizeへ結合した検証manifestを配送した。
- 配送版は0.8.1/code 13、minSdk 23、applicationId `jp.xiaqui.artifact.store.preview`、source `cd92f9a5e7b30c10c0c4ca863fa5aa857f2238ad`、固定証明書SHA-256 `dcfd2c8122274ec4148473c96b1853989c8d22e58e445d6ec4889ad5b34dffb2`。公開済み版検査は全APK再取得をやめ、既存最大版台帳と今後のRelease manifestを照合する。

実機では、既存版への上書きで登録・共通/個別PAT・Release履歴・保存APKが残ること、3スイッチの保持と独立動作、Wi-Fi/モバイル制約、通知からOS確認、手動確認・取得を確認する。合格と明示的merge承認まではPR #6をOPENに保つ。
