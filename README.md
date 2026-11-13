# Release Store — 個人用 Android アプリストア

GitHub Releasesを配布元として、登録した複数のpublic/privateリポジトリのAPKを端末で確認・更新する試作です。Android 6〜10（API 23〜29）が主要対象です。

## 初回設定

1. [検収用プレリリース](https://github.com/xiaqui/artifact/releases)で、目的の `store-preview-<SHA>` にある **ReleaseStore-preview.apk** をログイン済みブラウザから開きます。ストア版のIDは `jp.xiaqui.artifact.store.preview`。旧Installer版と並行導入でき、旧版の保存データは旧版から開けます。
2. 起動して「リポジトリを登録」に `owner/repo` またはGitHub URLを貼り付けます。「最新の対象にプレリリースも含める」は登録ごとに変更できます。
3. 非公開repoは「認証・再認証」で、そのrepoのContents読み取り権限を持つfine-grained PATを入力します。複数repoの認証はメモリ内で別々に保持し、プロセス終了後は再入力します。トークンの更新・解除は同じ画面から行います。公開repoは匿名で更新確認できます。

## 更新と過去版

一覧の「すべての更新を確認」、または個別repoの「更新を確認」を押します。下書きを除くAPK付きReleaseを公開日時順に表示します。プレリリースを除く設定では正式版だけが対象です。取得日時とエラーを表示し、通信失敗後も最後に取得した履歴を利用できます。

最新Releaseの未解析APKは、更新確認1回につき最大4件を一時取得してpackageName・アプリ名・versionCode・署名を調べます。通信量はAPKサイズに依存します。同じAssetの検査結果は次回も利用します。残りの候補と過去版は「APK情報を確認」で個別に解析できます。複数APKは明示的に選択してください。ABI・split構成の完全な判定および分割APKの一括導入は対象外です。

「保存してインストール確認へ」で選択したAPKを取得・検査し、永続保存します。確認画面で「標準インストーラで開く」を押してください。Android 6はPackageInstaller.Session、7以降はFileProviderを使用。Android 8以降の初回は「この提供元を許可」を設定し、戻ってもう一度押します。6/7はOSの提供元不明アプリ設定に従います。

更新状態は実APKと端末のpackageName、数値のversionCode、署名証明書から判定します。現署名が違う場合は「署名不一致の可能性」と表示します。署名ローテーション、ABI、SDK要件など、導入可否の最終判定はOSに従います。旧版の通常の上書きは制限されます。手動でアンインストールすると対象アプリのデータが消えます。ストアにアンインストール操作はありません。自動ロールバックや設定移行はPhase 2です。

## 保存・障害時

保存先はアプリ専用の `files/apks/`。1 APKは128 MiBまで、1パッケージ3世代・全体24件・512 MiBまで保持します。上限時は「保存したAPK」から削除対象を選びます。自動削除はありません。ストアの保存履歴にはrepo、タグ、Asset識別子、packageName、バージョン、保存日時を記録します。インストールのキャンセル後も同じAPKを再度開けます。登録削除はAPKを保持します。

403/429の利用制限は時間をおいて再試行、401/404ではrepo・権限・期限を確認して再認証してください。API認証は最初の api.github.com 宛に限定し、署名付き転送先へのAuthorizationを外します。GitHub Mobileのセッションは使用しません。1リポジトリ3,000 Release、1 Release 1,000 Assetまで。端末ストレージ不足・破損APK・署名不一致は各段階で表示します。

従来のRelease共有、ファイル選択・ZIP抽出は補助機能です。アプリが作ったZIPと一時コピーは処理後に削除し、選択APKと利用者の元ファイルを保持します。

## 配送・署名

GitHub Actionsで単体テスト、lint、API 23/29試験、APKの署名・minSdkを検査し、SHA単位の検収用プレリリースへ添付して再取得・バイト照合します。Actions ArtifactはCI内の受け渡し用です。同じタグのAssetを別バイナリで上書きしません。

署名は他のAndroidアプリと共通のGradle環境変数 `ANDROID_KEYSTORE_FILE`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD` を使用します。ActionsはRepository Secrets `KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD` を受け取り、キーストアを一時復元します。既存の `STORE_PREVIEW_*` Secretsも互換入力として受け付けます。署名Secretsが不足している場合は配布ビルドを失敗させ、runnerの一時debug鍵によるRelease公開を禁止します。任意のRepository Variable `STORE_PREVIEW_CERT_SHA256` を設定すると署名指紋を固定検証できます。

APKの通常配送先はGitHub Releasesです。Release Storeで取得・更新し、GitHub Actions ArtifactやGoogle Driveを通常配送には使いません。既存インストール版と署名が異なる場合、同じapplicationIdでは上書き更新できません。

## 実機検収

- 公開・非公開repoを登録し、個別・一括更新、編集・削除、通信を切った状態のキャッシュ、プロセス終了後の再認証を確認。
- 単一・複数APKのアプリ名、packageName、versionCode、導入済み版を確認。プレリリース表示を切り替え、過去版を選択。
- 旧版・署名違いの表示を確認。Android 6/7と8以降でOS確認画面を開き、キャンセル後の保存、再起動後の再試行を確認。

JDK 17、Gradle 8.9、Android SDK 35。ローカル検証は `gradle testDebugUnitTest lintDebug assembleDebug`、端末試験は `gradle connectedDebugAndroidTest`。エミュレータの自動試験と物理端末の検収は区別します。
