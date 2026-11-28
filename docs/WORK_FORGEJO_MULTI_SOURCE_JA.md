# GitHub / Forgejo 複数配布元対応 作業記録

更新日: 2026-11-28

## 目的と依存関係

GitHub Releasesだけだった既存登録を壊さず、Forgejo v1 APIのRelease APKを同じアプリ行へ追加する。検収APKはPR #12（起動時確認・集計）とPR #13（Android 7通信診断）を失わない積み上げとし、統合順は **PR #12 → PR #13 → 本PR** とする。どのPRも実機検収と明示承認まではマージしない。

## 再レビューで確定した設計

- アプリIDはAPK実体の `packageName + signing certificate SHA-256`。versionCodeは版比較にだけ使用する。repo名やサーバー間のrepository ID一致をアプリ同一性の証明にしない。
- 配布元IDは `provider + normalized HTTPS server base URL + repository ID`。Release/Assetはその配布元内で名前空間を分ける。Forgejoのサブパスを保持し、ユーザー情報・query・fragment・非443ポート・`..`を拒否する。
- 旧GitHub登録は最初のForgejo配布元承認時に遅延移行する。登録、PAT、履歴、解析結果、保存APKを一括削除・上書きしない。
- PATはForgejo接続先・owner単位でAndroid Keystore暗号化保存する。自動列挙は `/api/v1/user/repos` をページングし、設定ownerと完全一致するrepoだけを候補にする。自動列挙には `read:user` と `read:repository`、手動repo URL登録には `read:repository` が必要。会社固有URL・owner・PATはrepo、ログ、通知へ出さない。
- 自動列挙は登録ではない。最新Release APKを一時取得し、packageName・署名・versionCode・SHA-256を確認した候補だけを表示する。一括承認時も個別除外でき、承認前は既存優先順位を変えない。
- 更新候補は承認済み配布元の最大versionCode。versionCodeが同じ場合だけ保存済み優先順位を使う。同じversionCodeでSHA-256が不明または不一致なら競合として自動選択を停止する。Forgejo APIがdigestを返さない版では、一時取得したAPK実体からSHA-256を計算する。
- 同じpackageName・署名・versionCodeでSHA-256が異なる独立ビルドは、候補ごとの配布元とSHA-256を表示し、利用者がその版に限って一件を明示選択できる。選択は `source key + versionCode + SHA-256` へ固定し、通常の配布元優先順位を変更しない。新版、Asset差し替え、選択元解除では自動的に失効し、再び自動取得を停止する。SHA-256未確認の候補は選べない。
- 優先配布元の通信・認証・Asset取得失敗時は、同じSHA-256を確認済みの承認済み予備配布元だけへ取得を代替する。既知の新版より古い版へは切り替えない。認証失効も代替可能だが、接続先別の失敗表示を残し、成功したふりをしない。
- 障害中も保存された優先順位を書き換えない。復旧後は次回確認で通常のversionCodeと優先順位へ自動復帰する。フラップ防止のための恒久的な自動優先変更は行わない。
- 配布元解除は取得対象から外すだけで、配布元別履歴と保存APKを保持する。再承認可能。同じAPKはpackageName・署名・versionCode・SHA-256で配布元横断再利用し、重複保存を避ける。
- Forgejo APIと添付APKは登録済みの同一HTTPS originに限定する。API redirectは拒否し、Authorizationを別originへ転送しない。TLS検証を無効化しない。社内CAが必要な場合はアプリ内の無検証通信ではなく端末の安全な信頼設定を別途検討する。

## 実装範囲

- `DistributionSource` とprovider別Release adapterを追加。
- GitHub単一配布元の旧保存形式を読み取り、承認時に非破壊で複数配布元形式へ移行。
- Forgejo接続先登録、owner指定自動列挙、手動repo URL登録、APK実体による候補照合、一括承認・個別除外。
- アプリ詳細で配布元別状態、優先変更、解除・再承認を提供。トップ一覧の固定順とアプリ単位の件数は維持。
- Forgejo repo取得・候補・保留の集計は認証設定画面と操作完了ダイアログだけに表示し、トップ一覧へ常駐させない。
- versionCode最大・同版digest一致・優先順位による集約と、同一digestに限定した取得フォールバック。
- 保存APKの配布元横断重複回避。Wi-Fi制限、通知、標準インストーラ、既存の保存上限を共用。

## 未確認の外部条件

会社Forgejoの実URL、Forgejo major version、証明書チェーン、PAT scope設定は公開repoへ記録しない。CIは人工fixtureで検証し、実サーバー接続・private repo取得・実機上書きは社長の検収項目として分離する。

## 0.9.0実機検収からの追加修正

- owner `sugi` の列挙、候補提示、個別承認、既存GitHub行への統合、Forgejo優先・GitHub予備、organization権限なしでの登録は実機確認済み。これらは再実装しない。
- Forge Mobileの両配布元はpackageName・署名・versionCodeが同じでも独立ビルドのSHA-256が異なったため、同一APKとしての自動代替は行わない。上記の版限定明示選択を追加した。
- 競合・今回配布なし・照合不能では、前回確認済みのアプリ名、対応OS、導入版、履歴をトップ表示から消さず、配布元別の今回結果と全体選択結果を分ける。
- 更新確認中に優先順位や承認状態が変わっても、古い確認結果は動的な履歴・エラーだけを合流し、最新の優先順位・承認状態を上書きしない。

## CI・レビュー結果

- 実装・テストhead `d01363e8ba6db6e755615ddcb31714e4966e6310` をActions run [#46101200271](https://github.com/xiaqui/appdate/actions/runs/46101200271) で検証した。unit/lint/署名済みpreview生成と、API 23/24/29/34のdevice検証はすべて成功した。
- API 29で一度、通知発行直後の非同期反映を待たずに通知一覧を読んで失敗する既存PR #12由来のテスト競合が再現した。対象通知IDを5秒上限で待つよう修正し、同じ4 APIで再検証した。アプリ本体の通知処理は変更していない。
- 独立レビューは、標準Forgejo Asset URL境界、tokenのorigin固定、repo改名時のrepository ID照合、承認時再検証、部分成功・中断、同版digest競合、障害時の同一digest代替と復旧を再確認した。残るP0/P1/P2はない。
- 配送コミットは文書とworkflowだけを変更する。run #46101200271の成功済みbundleについて、参照run成功、source identity、APK SHA-256、署名、applicationId、versionCodeとAPI 23/24/29/34結果を再検証し、verify/deviceを省略した場合だけpreviewを公開する。不一致・期限切れ・不足時は安全に停止する。

## 検収項目

1. 既存0.8.4へ上書きし、GitHub登録、共通/個別PAT、履歴、保存APK、自動更新設定が残る。
2. Forgejo接続先・owner・PAT登録後、owner外repoが混ざらず候補が表示される。個別除外後に承認できる。
3. 同じ署名の移行repoは既存アプリ行へ統合され、Forgejo優先・GitHub予備になる。異署名は統合しない。同版異内容は自動取得が停止し、候補のSHA-256を確認して一件を明示選択できる。選択後も通常優先順位は変わらない。
4. Forgejoだけ新版、GitHubだけ新版、同版同一APKを確認する。トップの行・更新件数・通知はアプリ単位で一件。
5. Forgejo停止またはPAT失効時に、同一APKはGitHubから代替取得でき、失敗元も表示される。Forgejo復旧後は手動で順位を直さずForgejo優先へ戻る。
6. Wi-Fi自動取得制限とユーザー操作による標準インストーラ確認が全配布元で維持される。
7. 登録操作結果は認証設定・完了表示に出るが、トップへ常駐しない。競合中も既知のアプリ名、導入版、対応OS、履歴、保存APKが残る。

