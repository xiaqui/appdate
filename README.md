# Release APK Installer

Android 6〜10向けの試作。GitHub MobileやブラウザからプライベートReleaseを共有し、APKを保存してOS標準インストーラで開けます。

## 最初のインストールと使い方

1. GitHubにログインし、このリポジトリの検収用プレリリースから **ReleaseInstaller-debug.apk** をダウンロードして端末で開きます。試作はビルドごとにdebug鍵が変わることがあり、違う鍵の旧版から更新する場合は再インストールが必要です。アンインストールすると保存済みAPKは消えます。
2. GitHub Mobileまたはブラウザで対象Releaseの「共有」から **Release APK Installer** を選びます。対応するリンクを「開く」場合も受信できます。対象は github.com の /OWNER/REPO/releases（直近30件から選択）、/releases/tag/TAG、/releases/latest、/releases/download/TAG/NAME.apk、/releases/latest/download/NAME.apk です。latestはGitHubが指定する最新の正式Releaseを参照します。プレリリースにはタグリンクを使えます。
3. GitHubで **対象リポジトリ限定・Contents: Read-only** のfine-grained personal access tokenを発行し、画面のリポジトリ名を確認して入力します。組織で承認が必要な場合は承認済みトークンを使います。公開Releaseは匿名でも開けます。トークンはそのプロセスのメモリ内で1リポジトリに限って再利用し、ホームの「Releaseトークンを解除」またはプロセス終了で破棄します。GitHub Mobileでのログイン状態は共有されません。
4. APKが1件なら自動、複数ならファイル名を選択します。個別Assetリンクは名前が一致したAPKを取得します。packageName、versionName、versionCode、証明書SHA-256を確認して「標準インストーラで開く」を押します。Android 6はPackageInstaller.Session、7以降はFileProviderを使用します。Android 8以降の初回は「この提供元を許可」して戻り、ボタンを再度押してください。Android 6/7はOSの提供元不明アプリ設定に従います。
5. OS確認画面でキャンセルしてもAPKは内部の files/apks/ に残り、ホームの「保存したAPK」から再度開けます。1ファイル128 MiBまで。同名は別々に保存します。

## 認証と既知の制約

APIでReleaseとAsset IDを解決し、バイナリを取得します。トークンの送信先は最初の api.github.com HTTPSリクエストのみです。バイナリのHTTPS転送は最大5回、転送時にはAuthorizationを外します。宣言サイズと、GitHubが提供する場合はSHA-256も確認します。401/403/404では権限、期限、レート制限、Release・タグの有無を確認してください。認証を変更するには解除して再試行します。Release一覧は下書きを除き、Asset一覧は最大1,000件を検査します。GitHub Enterpriseや分割APKの一括インストールは対象外です。

GitHub Mobileの内蔵画面や既定ブラウザがリンクを処理すると候補に出ない場合があります。共有メニューを利用してください。受信診断はaction、type、scheme、限定したhost、URI種別だけを表示します。

## 保存済みファイルと旧Artifact経路

「ZIP / APK を開く」、ファイルの共有、直接HTTPS取得も利用できます。Actionsの認証付きArtifactはサインイン済みブラウザで取得してファイル選択で開きます。ZIP内の単一APKは自動選択、複数は1件選択です。成功・失敗・キャンセルでアプリが作成したZIPと一時コピーを削除します。利用者のダウンロード元ファイルは保持します。強制終了時の一時ファイルは次回起動で清掃します。

## 実機検収

- Android 6〜10でプライベートReleaseを共有し、候補の表示、診断のVIEW/SEND、APK取得と署名情報を確認する。Mobileとブラウザでそれぞれ試す。
- 権限のないトークンでの失敗と、解除・正しいトークンでの再試行を確認する。
- OSの提供元許可とインストール／キャンセルを試し、アプリ再起動後も「保存したAPK」から開けることを確認する。

## 開発・組み込み

JDK 17、Gradle 8.9、Android SDK 35。検証は `gradle testDebugUnitTest lintDebug assembleDebug`、エミュレータは `gradle connectedDebugAndroidTest`。ActionsでAPI 23/29を実行します。物理端末の動作とMobileが実際に渡すIntentは実機で確認してください。

ReleaseLinkがURLを解析し、GitHubReleasesがRelease・Assetの取得、ReleaseAuthが認証を担当します。ホストアプリは認証を差し替えられます。Releaseから取得したAPKとローカル経路はApkStoreとInstallationを共有します。SourceReaderとArchiveは従来のファイル・ZIP経路を担当します。
