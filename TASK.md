# TASK — Artifact Installer 試作（Phase 1）

## 目的
GitHub Mobile等で開いたGitHub Actions Artifactから、Android標準パッケージインストーラまでの手数を減らす小型Androidアプリを試作する。将来OctoDroidおよびForge Mobile（xiaqui/forge）へ移植・組み込み可能な構造にする。

## 今回の範囲
- Android 6〜10を主要検証対象（minSdk 23）。Kotlin、軽量なネイティブUI。初期実装は単独アプリ。
- ACTION_VIEW（http/httpsの対応リンク、application/zip、APK）とACTION_SEND（URLテキスト、content URI）を可能な範囲で受け付ける。Androidのリンク解決・アプリ選択はOS/呼び出し元依存であり、GitHub Mobileからの直接起動を保証しない。Intent受信内容（action、type、scheme、host、URI種別）を機密を伏せて診断表示できるようにする。
- 既存のZIP/APKファイル（content://を含む）を開く経路を確実に実装する。ZIP内のAPKを抽出し、単一APKなら選択を省略、複数なら一覧から選ぶ。zip-slip、ZIP爆弾、サイズ上限、異常なファイル名、破損ZIPを防御する。
- APKはアプリ専用保存領域の永続的な保存先へ残す。ZIPと展開用一時ファイルは成功・失敗・キャンセル後に安全に削除する。複数APKの場合、選択・保存したAPK以外は一時扱い。重複名衝突を回避する。
- FileProviderのcontent URIとFLAG_GRANT_READ_URI_PERMISSIONでOS標準インストーラに渡す。Android 8以降のREQUEST_INSTALL_PACKAGESと「この提供元を許可」導線、Android 6/7との差を考慮する。ユーザー確認を回避した無人インストールは行わない。
- APKのpackageName、versionName/versionCode、可能なら署名証明書フィンガープリントを表示。署名一致による上書き可否は確実に判定できる範囲だけ提示し、断定不能なら明記する。
- ダウンロード経路は、直接取得可能なAPK/ZIP URLとGitHub Actions Artifact URLを区別。Artifactの認証が必要なら適切な認証フローを設計し、PATをURL・ログ・ソースへ埋め込まない。初期試作で認証取得が難しい場合は、対応済み経路を完成させ、未対応理由と次フェーズの具体策を記録する。GitHub Mobileのログインセッションを流用できる前提にしない。
- UIは受信→取得/読み込み→APK確認→標準インストーラ起動の最短経路。失敗時は再試行と理由表示。不要な常駐・広範なストレージ権限は使わない。

## 将来の組み込みに備える設計
URL/Intent解釈、取得・認証、ZIP抽出、APK検査・保存、インストーラ起動を分離し、Android Activityに処理を密結合しない。OctoDroid/Forge Mobileへの組み込みは今回実施しない。GitHub Actions以外のForgejo系成果物にも拡張可能なインターフェースとする。

## 検証
- ZIP内1 APK、複数APK、APK単体、content URI、破損ZIP、悪意あるZIPパス、取得失敗、キャンセル、保存名衝突、インストーラ復帰を試験。
- Android 6/7/8/9/10のAPI差分を確認。GitHub Mobile、ブラウザ、共有シートからの起動可否は実機で判定できる診断手順を作成する。
- GitHub Actionsでdebug APKを生成し、Artifactとして回収可能にする。SDKセットアップに時間を浪費するローカルビルドを必須にしない。
- READMEに使用方法、既知制限、認証方針、APK保存先、ZIP削除条件、実機確認項目を記載。

## 実装手順・完了条件
最新mainから実装し、必要なAGENTS.md等の開発文書を整備。テスト・lint・独立レビューとP0/P1修正を行い、PRを作成。今回は試作APKとPRまで。mergeは別途承認待ち。CIの実際の結果、APK取得場所、未対応項目、実機未検証事項を明示する。既存の他repoは変更しない。
