# Artifact Installer（Phase 1）

Android 6〜10向けの、ZIPから必要なAPKを選んで保存し、端末のパッケージインストーラで開く小型アプリです。

## 使い方

1. GitHub Actionsの実行ページから `ArtifactInstaller-debug.apk` を取得して開き、OSの画面でインストールします。ビルドごとに生成する試験用debug鍵を使用します。別の鍵で署名されたビルドへの更新には再インストールが必要な場合があり、アンインストールすると保存済みAPKが消えます。
2. アプリの「ZIP / APK を開く」からダウンロード済みファイルを選択します。ファイル管理アプリの「開く」や「共有」、ブラウザのHTTPSリンク共有も利用できます。GitHubのArtifactページを受け取った場合、サインインしたブラウザでArtifactを保存し、アプリのファイル選択に戻ります。
3. ZIP内のAPKが1個なら自動で選びます。複数ならフォルダ名を含む一覧から1個を選びます。保存先はアプリ内部の `files/apks/` です。同名ファイルを繰り返し開いても別名で残り、ホームの「保存したAPK」から再度開けます。分割APKの一括インストールには対応していません。
4. パッケージ名、バージョン、取得できた署名証明書のSHA-256を確認し、「標準インストーラで開く」を押します。Android 8〜10で初回の許可設定が表示されたら「この提供元を許可」して戻り、もう一度ボタンを押してください。Android 6はPackageInstaller.SessionからOS確認画面を開き、Android 7以降はFileProviderの読み取り許可を標準インストーラに渡します。Android 6/7ではOS側の「提供元不明のアプリ」設定に従います。インストールまたはキャンセルはOS画面で選びます。アプリはインストール結果・上書き可否を断定しません。

## 入力と後片付け

APK・ZIPは内容を検査します。圧縮入力と選択APKは各128 MiB、展開総量は384 MiB、ZIP索引は8 MiB、項目数は16,000、候補は256までです。異常なパス、重複項目、CRC不一致、読み取り失敗はエラーになります。アーカイブのパスを保存先に使いません。端末内の元ファイルは利用者所有のため保持します。アプリが取り込んだZIPと選択用コピーは完了・失敗・キャンセル時に削除し、強制終了後の残骸は次回起動で掃除します。保存済みAPKはアプリのデータ消去・アンインストールまで残ります。

## URLと認証

直接バイナリを返すHTTPS URLに対応します。HTTPS転送は最大5回。HTTP、URL内のユーザー情報、GitHub ActionsのWebページは直接ダウンロードしません。GitHub MobileのセッションやCookieの転用、PAT入力・保存は実装していません。期限切れやアクセス権のないArtifactはブラウザ側で確認してください。

次フェーズではGitHub Appのデバイス認証と最小限のActions読み取り権限を使用し、ホストアプリから認証済み取得実装を注入する計画です。APIのZIP取得が返す署名付き転送先へAuthorizationを引き継がず、トークンやURLクエリを診断・ログに出さない設計とします。

## 実機検収

Android 6/7（API 23〜25）、8（26/27）、9（28）、10（29）で、ファイル選択→単一/複数APK→保存→許可→OS確認→キャンセル/インストール→保存一覧を確認してください。署名取得はAPI 28で切り替えます。物理端末でのOS別検収は未実施です。

GitHub MobileのArtifacts画面をタップ、リンクを共有、ブラウザで開く、ダウンロード一覧から開く、の順に別々に試してください。候補に出たか、起動したか、受信画面のaction/type/scheme/host/URI種別、端末・OS・呼び出しアプリの版を記録します。URL全文、クエリ、パス、トークンは共有しないでください。対象フィルタはVIEW/SEND。直接リンクは小文字の.apk/.zip（ファイル名中のピリオド最大5個を想定）が対象です。アプリ内ブラウザで完結する場合やOSの既定アプリが開く場合、診断は受信できず、共有かファイル選択を使います。GitHub Mobileの実Intentと直接起動可否は未確認です。

## 開発

JDK 17、Gradle 8.9、Android SDK 35で `gradle testDebugUnitTest lintDebug assembleDebug`。端末またはエミュレータを接続して `gradle connectedDebugAndroidTest`。ActionsのAPI 23/29エミュレータでContent URI、APK検査、リンク解決を検証します。Actionsではbranchの実SHAをチェックアウトし、APKのminSdk・署名を検査して、ZIP展開不要のAPK Artifactを7日保持します。

`Incoming` がIntentを解釈し、`SourceReader` はContentResolverと差し替え可能な `RemoteSource` を扱います。`GitHubAccess` で認証導線を分岐し、`Archive`、`ApkStore`、`Installation` がそれぞれZIP、保存、OSとの連携を担当します。設定変更中の読み込みは取り消し、再試行または再選択できます。外部URLは状態保存に含めません。
