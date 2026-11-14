# 自動更新・APK取得・通知 作業記録

対象: PR #6 / work/auto-update-notify。基準main: 8a1f388f1ba6f63c235b7e825083528d61e6a2fe。
アプリ実装・検証元: 798b967c6f2927d75a92c5abf7a79e8df2b4817f。

## 実装と保持

起動時のキャッシュ先行表示・15分再確認抑止、JobSchedulerの約6時間確認、Wi-Fi初期限定取得、更新候補の明示一括取得、通知から確認画面への遷移を追加。自動インストールは行わない。APK実体のpackage/version/署名/SDKとPackageManagerの導入状態を照合する。アプリ名と対応OSは別欄で表示する。

3世代・24件・512 MiB等の既存上限を維持。自動整理は導入済みの古い自動取得分のみ、手動保存・待機中・開いているAPKと唯一の互換復旧候補を保護。削除した実体の履歴も保持。repo移転の同一性はこのmain系列で追跡できず、新名の別登録を案内して既存登録を保持する。

PR #4のPAT自動登録とPR #5の画面保護変更、外部Intent branchは混ぜていない。mainも変更していない。

## 検証

[CI 43601806132](https://github.com/xiaqui/artifact/actions/runs/43601806132) 成功。
- 単体テスト33件、lintDebug、署名付きassembleDebug成功。
- API 23/29で接続試験17項目（再起動専用readは通常実行でskipし別プロセスで実行）。通知タップで確認画面へ遷移、複数件の遷移先、BOOT受信時の通知再照合、破損保存の再取得候補化、SDK情報・非対応除外、導入実状態、通信中断、世代整理・一時保護・履歴保持を検証。
- 既存の強制終了/adb reboot後の認証維持と、小画面・文字拡大の7行表示、正式0.4.2からcode 9への固定署名上書き・保存領域保持も成功。
- 通知のBOOT処理はテストからの受信呼び出しで検証。実際の長時間Doze・OEM省電力での通知復元/定期実行は実機未検証。

独立レビューでP1（通知APKを永久手動扱いにする点、Receiver期限超過）とP2（照合失敗表示、通知BOOT復元、破損ファイル再候補、SDK解析不明の再取得、OS欄省略）を修正。再レビューで未解決P0/P1/P2なし。repo改名fallbackも明記。

## 配送

同じテスト済みAPKを再ビルドせず配送するため、既存android.ymlに明示的な `[deliver-tested]` の配送jobを追加。指定CI成功・source SHA・アプリ差分なし・APKサイズ/hash/署名/版/minSdkを再照合して公開する。通常pushやdocs更新ではReleaseを作らない。

- APK: ReleaseStore-v0.6.0-preview.apk / 2,136,638 bytes
- applicationId: jp.xiaqui.artifact.store.preview
- versionName / versionCode: 0.6.0 / 9、minSdk 23
- APK SHA-256: 37d7844cbb38dc7b239411daadc445eceda0ed0ca47549736ebe4de0f2e84c24
- 署名: v1/v2、固定alias xiaqui
- 証明書SHA-256: dcfd2c8122274ec4148473c96b1853989c8d22e58e445d6ec4889ad5b34dffb2
- ビルド元: 798b967c6f2927d75a92c5abf7a79e8df2b4817f

2026-11-14 UTC: 配送は未完了。[配送run 43637669070](https://github.com/xiaqui/artifact/actions/runs/43637669070) は初回・失敗jobのみの再試行とも、runner_id=0、steps空のまま約2秒で失敗。APK検証・アップロードは未実行。同時の配送設定検証run 43637673251もrunner開始前に失敗。jobログはBlobNotFoundで取得できず、原因は未確定（利用枠不足とは断定しない）。接続ではcheck annotationsが取得対象外、代替ブラウザは未認証のためprivate repoの原因表示を確認できなかった。

復旧時は配送runのfailed jobsのみ再実行すれば、検証済みAPKを再ビルドせず配送できる。予定tagはstore-preview-798b967c6f29。公開後にassetのサイズ/hash/source/certificateを再照合するまで配送完了扱いにしない。配送設定検証runも復旧後に再実行する。PRは未マージ、Release配送と実機検収待ち。

## 実機検収

1. Z3TC(Android 6)、Z4T(Android 7)、Android 8/9/10で、起動直後のキャッシュ/導入版/対応OSと自動確認を確認。
2. Wi-Fi・モバイル切替、画面OFF、長時間省電力、PAT失効時の保留/失敗と再試行。
3. 単件/複数通知タップ、OS確認、キャンセル後のオフライン再インストール、実インストール後の通知解消。
4. 3世代上限時に手動保存・待機APK・復旧候補を残し、整理不能時は保留すること。

実PAT・組織承認・実機OEM制御、Forge/IP Messenger/Nearby Games実機の導入版照合は未検収。エミュレータの模擬データでの成功と区別する。
