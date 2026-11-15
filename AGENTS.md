# 開発

作業開始時は `xiaqui/rin` の最新README.md・STYLE.mdと、当repoの最新main、`TASK.md`、README.md、関連文書、既存PRを確認する。

## 一言指示の解釈

社長からWorkへ **「TASKに従って完走」** と指示された場合、`TASK.md` の「現在のWork実行対象（唯一）」に書かれたタスクだけを、そこに記載された検証・レビュー・PR作成・必要な検収APK配送まで進める。指示の全文を社長にコピーさせたり、過去のTASKを推測実行したりしない。マージ・実機検収・GitHubリポジトリ名変更等の権限はTASKに明記された範囲を守る。実装済みや他PRの作業は重複しない。

Android 6（API 23）以降のAPI境界を確認し、ファイル処理や取得処理をActivityから独立させる。検証コマンドと実機検収項目はREADME.mdに記載する。検収APKはGitHub Releasesへ必要な場合のみ公開し、Actions ArtifactはCI内の受け渡しに使用する。公開リポジトリでもPAT・署名鍵・個人情報をコミット・ログへ出さない。
