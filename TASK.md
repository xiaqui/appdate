# TASK — AppDate 統合の次工程

## 最優先ブロッカー：PR #6 Actions device全滅の根本修正とCI無駄削減（2026-11-22）

**この節を先に完走してから、下記の既存PR #6実装・実機検収タスクに戻る。別PRを作らず、既存ブランチ `work/auto-update-notify` とPR #6を更新。実機合格前のmerge禁止。アプリ本体の機能・データ・署名・applicationIdは変更しない。**

### 現状と確実な手掛かり
- `52bc46e` のActions run `44992026161` はverify成功、device（API 23/29/34）失敗。先行run `44991601777` もverify成功・device 7件失敗。previewはdevice依存で配送されない。過去run `44990807656` のverify失敗は別の既修正問題。**最初に各runの失敗ログ本文・終了コード・該当コマンドを取得し、推測で修正しない。**
- `.github/workflows/device-checks.sh` 最終行が `grep 'versionCode=12 '` に固定されているのに、`app/build.gradle.kts` は **0.8.1 / versionCode 13**。これは少なくとも確実な不整合。さらに `gradle connectedDebugAndroidTest` が配送APKと別にビルド・インストールし、署名・同一APK検収を混同する危険がある。**ログで最初の失敗点を確認**し、最終行だけ直して終わりにしない。
- `.github/workflows/android.yml` はverify→device→previewで、workflowの修正ごとにverify（unit/lint/build/署名検査・Release全件APK比較）とエミュレータを再実行する。通常device matrixは **API 23/29/34（Android 6/10/14）** とする。

### Workへの実行指示
1. 最新 `xiaqui/rin` README/PERSONAL/STYLE、当repo AGENTS/README/TASK、最新main、PR #6 head、workflow、device-checks、release scripts、run 44992026161/44991601777の**失敗ログ本文**を確認。既存成果とSHAを保全。GitHub上の最新状態を優先し、無駄なAndroid SDK構築・ビルド環境再構築をしない。
2. device全失敗の**共通原因をログで特定**し、環境・テスト・アプリのどの段階か切り分ける。特に固定versionCode=12、旧版0.4.2からの上書き、`gradle connectedDebugAndroidTest`、installDebugによるAPK差し替え、adb reboot後の起動待ち、スクリーンショット抽出、Android 14の権限/計装差を検証。失敗原因ごとに最小修正し、テスト結果を偽装・skipして緑にしない。
3. **CIを用途別に分離**：ソース/ビルド設定変更時のみunit/lint/署名済みAPK生成・メタデータ/版検査を実施し、SHA-256・source SHA・applicationId・versionCode・署名fingerprintをmanifestとして成果物に保存。deviceスクリプトやworkflowだけの変更時は、**同じソース・同じ署名の既存成功済みAPKとmanifestを再利用**してdeviceを実行できる明示的な経路を設ける。workflow_dispatch等でrun ID / artifact IDを指定する方式でもよいが、参照元runの成功、コミットのソース同一性、SHA-256、署名、版を検証し、不一致なら停止。期限切れ・不明・権限不足なら再利用せず安全に停止または明示的な再ビルドに切り替える。既存APKと異なるソースの混用は禁止。
4. **テスト段階の無駄を削減**：通常deviceはAPI 23/29/34のみ。全OSマトリクスは必要時の手動/リリース前検証に分離。成功済みdeviceジョブは同一runのfailed jobs再実行で再利用し、workflow変更時も不要なverifyを回さない。Gradleの重複ビルドを減らし、deviceはverifyで生成した**そのAPK**の上書き・データ保持を検証。計装用APKの別途ビルドが不可避なら明示して区別する。失敗時はどの段階で止まったか要約を残す。Artifactの保存期限と容量に注意し、ReleaseをCIキャッシュ代わりに乱発しない。
5. **版検査を堅牢化**：固定versionCode/versionName/APKファイル名の重複記述を極力排し、GradleとAPK実体から動的取得。公開済みReleaseの版照合はpackageName・署名・versionCode・APK SHAを確認し、ページネーション、過去Asset取得失敗、同名異内容、異なるapplicationIdを安全に扱う。毎回全Release APKをダウンロードする方式は廃止・最小化し、必要な最新/最大版の信頼できるメタデータまたはキャッシュと整合性検証を設計。失敗を無視して配送しない。**0.8.1 / code 13を根拠なく再度上げない**。異なるAPKを新規配送する場合だけAGENTSの単調増加規則に従う。
6. 修正を段階的に検証する。最初に失敗した1台（API 23等）の原因を解消し、その後API 23/29/34を通す。deviceだけの変更で再ビルドを繰り返さない。必要なrunのみ起動し、同一原因の失敗が続くときは停止してログ解析へ戻る。GitHub Actionsでverify/device/previewの結果、APK SHA、所要時間・重複ジョブ削減を確認する。
7. previewは**検収用APKを必要最小限の1件のみ**、すべてのゲート通過後に既存経路で配送。過去の公開Assetを上書きしない。検収APKとCI検証APKのバイナリ同一性を証明し、Release URL、SHA-256、署名、source SHA、versionCode、API別結果、実機検収項目、残課題をPR #6/README/作業記録へ記載。**社長の実機合格・明示的merge承認までPR #6はOPEN。**
8. 独立レビューを実施しP0/P1を解消。新機能、UI改修、Issue #9、別repo変更を混ぜない。作業途中で止まる場合も再開可能な状態と具体的な未完了事項を残す。

**完了条件：** 原因をログと修正差分で説明できること、API 23/29/34のdevice成功、verifyの不要な再実行を回避できる再利用経路の実証、検収APKの正しい配送、PR #6未マージ。単にテストを減らして成功扱いにするのは不可。

### 2026-11-22 実施結果

- run `44992026161` / `44991601777` は全OSとも計装、再起動、スクリーンショット、0.4.2上書き、`upgrade-sentinel`保持まで成功し、最後の固定値 `versionCode=12`だけが0.8.1/code 13と不一致で終了コード1になっていた。Android 14固有障害やデータ消失ではない。
- verifyで署名済み本体APKと計装APKを一度だけ生成し、source SHA/ソースidentity/applicationId/version/証明書/SHA-256/sizeをmanifestへ保存する。deviceはGradleを実行せず、この本体APKそのものを導入・上書き検証する。版確認はmanifestの実値を使用する。
- 通常matrixをAPI 23/29/34へ限定。run `44993297128` はverifyと3OS deviceが成功。run `44993805366` はworkflowだけの変更に対してrun `44993297128`のbundleを再利用し、`verify=skipped`、`reuse=success`、API 23/29/34 device成功を確認した。
- 公開版照合は毎回全APKを取得せず、既存0.8.0最大code 12のRelease digest台帳と、今後添付する小さいmanifestをページネーション取得する。applicationId・証明書・versionCode・APK SHAが不整合なら配送を停止する。
- 検収APKは0.8.1/code 13、source `cd92f9a5e7b30c10c0c4ca863fa5aa857f2238ad`、APK SHA-256 `28cc3ebe4dc65bd38928652cc6c6e53f9cc7c65888266c52ea0b6bc7d752d864`、証明書SHA-256 `dcfd2c8122274ec4148473c96b1853989c8d22e58e445d6ec4889ad5b34dffb2`。PR #6は実機検収・merge承認待ちのままOPENとする。

## 現在のWork実行対象（唯一）：既存PR #6 自動更新機能＋Issue #8 個別設定の統合・検収APK配送

**社長の2026-11-21承認：PR #6の自動更新本体とIssue #8の3つの独立ON/OFF設定を、一連の作業として既存PR #6に仕上げる。実機検収前にmergeしない。** PR #4/#5/#7はmainへ統合済み。完了済み機能を再実装しない。正式repoは `xiaqui/appdate`。旧名 `xiaqui/artifact` のURL・remoteを作業先にしない。

1. 最新 `xiaqui/rin` README.md / PERSONAL.md / STYLE.md、当repo AGENTS.md / README.md / TASK.md、Issue #8、PR #6の最新head・差分・レビュー・CI・既存作業記録、最新mainを取得する。originとGitHub repository IDを照合し、旧branchの成果・テストを保全する。**新しいPRを作らず既存PR #6を更新**し、mainのPR #4/#5/#7を安全に取り込む。古いhandoffやPR本文の旧URL・旧SHAを現在地とみなさない。
2. PR #6で実装済みの起動時更新確認、約6時間ごとのバックグラウンド確認、Wi-Fi限定APK自動取得、更新準備通知と通知からの操作、手動一括取得、APK検査・保存管理、導入済み版照合、対応minSdk表示を、最新mainに適合させて検証・修正する。既存の初回PAT登録→APK配布repo自動追加→初回確認→トップ遷移を維持し、初回フローを二重実行しない。サイレントインストールは禁止し、インストール最終確認はユーザー操作とOSに委ねる。
3. **Issue #8を同じPR #6に実装**し、設定に以下3項目をそれぞれ独立したスイッチとして設ける。
   - 起動時の自動更新確認：初期値 **ON**
   - バックグラウンドの定期更新確認（約6時間ごと）：初期値 **OFF**
   - Wi-Fi接続時のAPK自動取得：初期値 **ON**
   起動時と定期確認は独立制御。定期OFF時はJobScheduler等の予約を解除し、ONで適切に再登録。自動取得OFFでも更新検出・一覧・手動ダウンロードは維持。自動取得ONでもWi-Fi以外ではダウンロードしない。旧版の「自動更新全体の一時停止」設定との優先順位・移行、既存設定値の保存、再起動・プロセス再生成後の保持を検証し、ユーザーの明示的なOFFを初期値で上書きしない。バックグラウンド制約・権限・通知はAndroid 6〜10で安全に扱う。
4. 自動確認・自動取得・手動確認・初回PAT登録の同時実行、通信断、Wi-Fi切替、GitHub API制限、認証失効、複数repo、容量不足、再試行、通知タップを検証する。APKの実体・packageName・versionCode・署名・出所IDを確認し、署名不一致や未検証APKをインストール可能と誤認させない。PAT・登録・Release履歴・既存保存APKを消さない。秘密をログや通知に出さない。既存の保存上限・自動整理方針と競合する場合はデータ保全を優先し、無断削除を導入しない。
5. Android API 23〜29を対象にunit/lint/必要な計装テストを追加・実行し、GitHub Actionsで固定署名・API 23/29・旧版からの上書きとデータ保持を確認する。独立レビューでP0/P1/P2を修正・再レビューする。Work内でAndroid SDKを再構築して利用枠を浪費せず、既存Actionsを優先する。CIが失敗したら環境要因と実装不具合を分離して報告する。
6. 最新head由来の**検収用preview APKを必要最小限の1件**、既存GitHub Releases経路で配送する。applicationId、versionName/code、minSdk、source SHA、APK SHA-256、署名証明書SHA-256、上書き可否を記録し、PR #6とREADME/作業記録を更新する。旧名URLや過去APKを最新成果物として再利用しない。
7. 社長へ実機検収項目を絞って提示する：①既存データ保持の上書き、②起動時ON/OFF、③定期OFFで予約されないこととON時の動作、④Wi-Fi自動取得ON/OFFとモバイル通信での抑止、⑤更新準備通知からの操作、⑥手動確認・手動取得・既存登録/PAT/保存APK保持。実機でしか判断できない項目とソース・CIで確認済みの項目を区別する。**社長の実機合格とmerge承認（まじおk）までPR #6はOPENのまま保持**する。
8. Issue #9のPython資産取得、下スワイプ更新、アプリ名/AppDateへの全面名称統一、UI全面改修、別repoへの変更、新規機能は混入させない。Issue #8はPR #6での実装対象として参照し、完了後に重複する未着手TODOを残さない。

## 完了状態の整理
- PR #4：2026-11-21実機合格・main統合済み。
- PR #5：2026-11-21実機合格・main統合済み（merge commit `5b833b5fa9531c4ad4ba829060d8808d94ab8ef6`）。
- PR #7：main統合済み。
- PR #6：現在の唯一のWork実行対象。Issue #8の設定を含めて仕上げ、実機合格前はマージしない。

## 完了済み：PR #4の自動登録フィルタ

**既存PR #4（`work/pat-repo-auto-import`）** に、`docs/TASK_PAT_REPO_AUTO_IMPORT_JA.md` の2026-11-16改訂仕様を実装する。最新の `xiaqui/rin` README/STYLE、当repoのAGENTS.md、最新main、PR #4の最新headと差分を確認する。新規PRを乱立させず**PR #4を更新**すること。

**確定条件：公開/非公開を問わず、過去のGitHub ReleasesにAPK assetが一つ以上あるrepoだけを自動追加する。** 最新Releaseだけで判定せず、ページネーションと401/403/404/429・部分失敗・未確認状態を区別する。既存の手動登録、個別PAT、Release履歴、APK、現存登録は自動削除しない。repo同一性はGitHub IDで確認し、PR #7でmainに入った改名保護を壊さない。

1. 完了済み工程の再実装や環境構築を繰り返さず、PR #4の最新headを現在のmainに安全に追従させ、競合を解消する。
2. 対象絞り込みを実装し、Android 6/10の境界、単体/計装テスト、lint、Actions CI、独立レビューとP0/P1/P2修正まで進める。
3. 必要な場合に限って固定署名preview APKをGitHub Releaseへ一つ配送し、ビルド元SHA・署名・上書き互換性を検証する。
4. 成果をcommit/pushしPR #4に反映。社長の実機再検収前にmergeしない。既存の登録を一括削除する処理は追加しない。


### 2026-11-21 実機検収を受けたPR #4追加修正（今回の優先作業）

社長の実機では、共通PAT登録後の自動repo取得が **取得12／新規追加7／APK配布履歴なし5／判定保留0** で完了し、トップ画面に7件（appdate、forge、sh4a、ipmsg、overlay、wolf-chess、xiking）が表示された。**APK配布履歴による自動登録と一覧反映は実機確認済み**。ただし、PAT登録後は認証設定画面に留まり、トップ画面の各行が「導入 不明 → 最新 未確認」のままで、別途「一括更新確認」が必要だった。これを同じ初回設定フローとして仕上げる。

1. 共通PATの登録・変更に伴う自動repo登録の**成功後、新規登録されたrepoのRelease情報・最新バージョンを既存の更新確認処理で自動確認**する。必要なインストール済みアプリとの照合も行い、完了後はトップ画面に確認結果を表示する。ユーザーに手動の「一括更新確認」を要求しない。
2. **PAT登録 → APK履歴で自動登録 → 新規登録分の初回更新確認 → トップ画面へ遷移**を自然な一連の操作にする。登録ゼロ件や部分失敗でも操作不能にしない。再取得操作では既存登録を重複追加せず、新規追加分を対象とする。既存登録の一括更新確認は従来どおり利用できる。
3. repo登録とRelease確認は**独立した処理・失敗境界**にする。確認の通信失敗・API制限・中断で成功済み登録、PAT、保存APK、履歴を取り消したり消したりしない。失敗したrepoは未確認／再試行可能と表示する。通信が遅い場合もUIを固めない。
4. **APK本体の無条件自動ダウンロードやサイレントインストールはしない。** PR #6の定期確認・Wi-Fi自動取得・通知をPR #4へ取り込まない。将来の設定と競合しないよう更新確認の既存ロジックを再利用する。
5. 最新mainとPR #4の最新headを再取得して、**既存PR #4のブランチに追加**する。PR #7の改名保護を維持。Android 6〜10、初回PAT登録、再登録、既存登録あり、ネットワーク障害、画面遷移、データ保持をテストし、Actions CI・独立レビューを行う。
6. 必要なら固定署名の新しい検収APKをGitHub Releasesに**一つだけ**配送する。社長はまず現データを消さずに上書きして登録・認証・保存データ保持を検証し、その後データ消去による初回フロー検収を行う。**実機検収前にマージしない。**

## 並行して保全する作業

- **PR #5** スクリーンショット撮影修正：旧headを保持してmain取り込み・競合解消済み。通常画面は撮影可能、PAT入力ダイアログのみ保護。検証APKと実機スクショ検収が未完了のため**マージ保留**。
- **PR #6** 自動更新確認、Wi-Fi取得、通知：既存の実装と検証成果を保持。PR #4と混ぜない。main追従・独立レビュー・配送・実機検収が必要。
- **PR #7** 改名対応：CI、実機検収合格によりmainへマージ済み。繰り返さない。
- **後続独立タスク**：アプリ表示名/APKをAppDateに統一（packageNameと署名は変更しない）。配布元はGitHub repository ID、アプリはAndroid packageName、配布物はRelease/Asset IDという3層管理へ安全に移行する。

社長への指示は原則「TASKに従って完走」の一言でよい。別タスクへ移るときは、本書の「現在のWork実行対象」を明示的に差し替え、古いタスクを誤って再実行しない。
