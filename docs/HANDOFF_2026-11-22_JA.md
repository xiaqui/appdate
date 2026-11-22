# AppDate 次スレッド引き継ぎ（2026-11-22）

対象: `xiaqui/appdate` / 次スレッド: `appdate.ch`（相談・検収）、実装時は `appdate.wk`。本書は現状の記録であり、新規機能の着手承認ではない。

## 最初に確認する正本
1. `xiaqui/rin` 最新mainの `README.md` → `PERSONAL.md` / `STYLE.md` / `DEVELOP.md` / `DELIVERY.md` / `MEMORY.md`。
2. `xiaqui/appdate` 最新mainの `AGENTS.md` / `TASK.md` / `README.md`、本書、関連docs、Issue・PR・Release・Actions。
3. GitHubの現在状態を優先する。以下のSHAやrunは2026-11-22時点の履歴。古いTASKの「PR #6がOPEN」「現在のWork実行対象」等を未完了として再実行しない。

## 今回の確定成果
- PR #4（共通PAT登録後のAPK配布repo自動登録と初回確認）、#5（PAT入力中のみ画面撮影保護）、#7（repo改名対応）、#6（自動更新・Issue #8の独立3スイッチ）はmain統合済み。
- PR #6 は社長の条件付き承認「7種類が通ったらまじおk」に従い、Android API 23/29/30/31/33/34/35のdeviceジョブがすべて成功した後にマージ済み。7OS run: `44997418025`（success、APK再利用、verify skipped）。マージコミット: `ea99ab87c3b7511b47468cbd033eef9dd9bc6160`。
- 正式Release `v0.8.1` **公開済み**。Asset: `ReleaseStore-0.8.1.apk`（2,172,091 bytes）、versionCode 13、applicationId `jp.xiaqui.artifact.store.preview`、minSdk 23。SHA-256: `28cc3ebe4dc65bd38928652cc6c6e53f9cc7c65888266c52ea0b6bc7d752d864`。固定署名証明書SHA-256: `dcfd2c8122274ec4148473c96b1853989c8d22e58e445d6ec4889ad5b34dffb2`。
- 検収用Release `store-preview-fa230d039ad8` の `ReleaseStore-v0.8.1-preview.apk` と正式APKのGitHub公開digestは一致。再ビルドせず同一バイナリで昇格。正式版の採用情報は `.github/release/accepted.json` が正本。preview tagの対象コミット `fa230d039ad833044ee0fe321d9ba321b6dd4070` とAPK manifest内のsource SHAは同一概念ではないので混同しない。
- 正式昇格の初回run `44998683241` は失敗。原因はSquash Merge後の祖先判定不一致。さらにpreview Asset名が版付きであるため `.github/release/promote.sh` を調整。修正後run `45000153538` はsuccess、正式Release `v0.8.1` とAssetのdigestをGitHub APIで確認済み。修正コミット `1193a7a2711361b2df2c9a02b44a6e0b22bb2384`。正式ReleaseのAPKを別版として再生成しない。
- Xperia Ace IIIで、旧Overlay v0.1.0→更新検出→自動取得→更新準備通知→標準インストーラ→Overlay v0.11.0への上書き成功を社長が実機確認。初回起動時は通知が出ず、AppDate再起動後に通知が出た。起動直後の通知タイミングは追加観察事項であり、原因未確定。7OS CI合格を全OS実機合格と表現しない。

## 現在の動作・設定
- 起動時の自動更新確認: 初期ON。約6時間ごとのバックグラウンド確認: 初期OFF。Wi-FiでのAPK自動取得: 初期ON。3項目は独立設定。
- 既存PAT、repo登録、保存済みAPK、履歴を保全。更新対象は原則として導入済みかつ互換性・版・署名を確認できたもの。未導入・判定不能を無条件に自動更新しない。
- インストール最終確認はAndroid標準UIで利用者が行う。サイレントインストールは禁止。
- Actionsはverifyで署名済みAPKを一度生成し、deviceは同一APKを再利用。CIだけの変更時は検証済みbundleのsource identity・hash・署名を確認して再利用する。通常API 23/29/34、必要時に7OS。Artifactsは一時受け渡しであり、Releaseの濫造を避ける。

## 次工程の候補（未着手・優先順位未承認）
- **名称整理**: UI上のRelease Store / Artifact / Installer、APKファイル名・ReleaseタイトルをAppDateへ整合。既存applicationId・署名・データ・上書き互換性は維持。名称変更だけでも別バイナリ配送ならversionCode増加規則を守る。
- **トップ画面と操作**: 更新ありを上部にまとめ、通常一覧は安定した固定順で表示。アプリアイコン表示、必要情報の階層化、下スワイプ更新、アプリ起動導線。操作中に行を並べ替えない。既存機能の実装済み部分を再確認し、二重実装しない。
- **Issue #9 / Python資産**: Python配布物取得とPydroid 3連携の仕様・安全性・対象範囲を再確認。
- **OS対応と判定**: Android 11以降のパッケージ可視性・導入済み判定、通知権限、Wi-Fi・JobScheduler・省電力動作、Android 14でのIP Messenger判定など、既存TASKの残件を現コードと照合。実機未検証は未検証と明記。
- **外部Intent**: `docs/ROADMAP_EXTERNAL_INTENTS_JA.md` にあるブラウザ/ファイル/共有からのAPK・ZIP受信とrepo関連付け。AndroidのURI権限とPAT漏洩防止を優先。
- ロールバック、保存履歴、Forgejo連携などの長期候補は `docs/ROADMAP_PERSONAL_APP_STORE_JA.md` と既存Issueを確認。未承認の機能を自動で着手しない。

## 次スレッドで最初にすること
1. 正式 `v0.8.1`、main、open PR/Issueを再取得し、公開状態と版の整合を確認。
2. `TASK.md` のPR #6実行指示は**履歴化が必要**。次のWorkを起動する前に、社長が選んだ次工程だけを「現在のWork実行対象（唯一）」へ設定し直す。古いPR #6へpushしない。
3. 今回の依頼は引き継ぎ文書の保存のみ。次工程の実装・追加APK配送・マージは未承認。

## 参照
- 正式Release: https://github.com/xiaqui/appdate/releases/tag/v0.8.1
- PR #6: https://github.com/xiaqui/appdate/pull/6
- 7OS成功: https://github.com/xiaqui/appdate/actions/runs/44997418025
- 正式昇格成功: https://github.com/xiaqui/appdate/actions/runs/45000153538
- 実装記録: `docs/WORK_AUTO_UPDATE_JA.md`
- 後続仕様: `docs/ROADMAP_PERSONAL_APP_STORE_JA.md`、`docs/ROADMAP_EXTERNAL_INTENTS_JA.md`
