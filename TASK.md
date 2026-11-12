# TASK — Release APK Installer 試作（PR #1）

## 今回の検収対象

プライベートGitHubリポジトリのReleaseからAPKを取得・保存し、利用者が確認してAndroid標準インストーラで開ける単独アプリにする。Android 6〜10を主要対象とし、minSdkは23。実装と配送は既存PR #1で完結し、マージは検収後の別判断とする。

- GitHub Mobile、ブラウザのVIEWと共有テキストのSENDからRelease一覧・タグ・latest・APK Assetリンクを受ける。リンク解決とMobileの実Intentは実機で確認する。
- 対象リポジトリのContents読み取り権限で認証し、APKだけを選択して取得する。1件なら自動選択、複数なら一覧選択。認証情報をURL、診断、ログ、保存状態に含めず、署名付き転送先には送らない。
- 取得したAPKを検査し、packageName、versionName、versionCode、取得できた署名を表示する。アプリ専用永続領域に同名衝突を避けて保存し、再起動後も一覧から開ける。
- OS標準の確認と提供元許可を維持する。取得・認証・APK保存・インストーラ起動を分離し、将来OctoDroid/Forge Mobileから再利用できる形にする。他repoへの組み込みは今回の対象外。
- 既存のActions Artifact案内、直接HTTPS取得、ファイル選択、ZIP抽出は副経路として保持する。アプリが作ったZIPと作業コピーを終了時に消し、選んだAPKを残す。

## 検証と引き渡し

既存単体試験に認証先制限、転送、Release URL/Asset選択、取得失敗を追加。lint、debug APKビルド、端末API境界のエミュレータ試験、独立レビューとP0/P1修正を実施する。GitHub Actionsで試作APKを作り、取得先、SHA、実機未確認項目を報告する。PR #1はopenのまま検収待ちとする。
