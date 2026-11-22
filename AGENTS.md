# 開発

作業開始時は `xiaqui/rin` の最新README.md・STYLE.mdと、当repoの最新main、`TASK.md`、README.md、関連文書、既存PRを確認する。

## 一言指示の解釈

社長からWorkへ **「TASKに従って完走」** と指示された場合、`TASK.md` の「現在のWork実行対象（唯一）」に書かれたタスクだけを、そこに記載された検証・レビュー・PR作成・必要な検収APK配送まで進める。指示の全文を社長にコピーさせたり、過去のTASKを推測実行したりしない。マージ・実機検収・GitHubリポジトリ名変更等の権限はTASKに明記された範囲を守る。実装済みや他PRの作業は重複しない。

Android 6（API 23）以降のAPI境界を確認し、ファイル処理や取得処理をActivityから独立させる。検証コマンドと実機検収項目はREADME.mdに記載する。検収APKはGitHub Releasesへ必要な場合のみ公開し、Actions ArtifactはCI内の受け渡しに使用する。公開リポジトリでもPAT・署名鍵・個人情報をコミット・ログへ出さない。

## APKのバージョンと配送（必須）

検収用previewを含め、**内容の異なるAPKを新しく配送する場合は、同じapplicationIdの既存配送APKよりversionCodeを必ず増加**させ、利用者が識別できるようversionNameも更新する。コミットSHAやReleaseタグが異なるだけでは更新版にならない。単なる再ビルドもAPKバイト列が異なるなら別成果物として扱う。旧版と同じversionCodeの別APKを配送しない。既存の署名とapplicationIdは維持する。正式版への昇格は検収済みAPKを**再ビルドせず同一バイナリ**で行うため、版を変更しない。

Release workflowは、配布前にAPK実体のapplicationId/versionCode/versionNameと既存Release APKを照合し、異なるバイナリなのに同一または低いversionCodeなら**失敗させて公開しない**。比較・取得に失敗した場合も安全側に停止する。版を上げる際はGradle設定・workflowのメタデータ検査・APK名・Releaseタイトルを一緒に更新する。過去の公開Assetを上書きして帳尻を合わせない。
