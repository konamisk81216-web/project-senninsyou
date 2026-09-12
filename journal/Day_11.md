# Day11 - 2026/09/11

## 今日の目的
Project千人将をCLI中心のシステムからWebアプリへ進化させ、
ブラウザからAI将軍へ命令できる状態を作る。

---

## 今日できたこと

### 1. Spring BootによるWeb API化
- Spring Boot 4.1.1を導入
- 組み込みTomcatを起動
- `http://localhost:8080` でWebアプリを表示できるようになった

### 2. Javaパッケージ構成を修正
全Javaクラスを以下へ移動。

`com.senninsyou`

Spring Bootをデフォルトパッケージで起動したことで、
ライブラリ全体までComponent Scanされ、
Bean重複エラーが発生していた。

パッケージを分けることで解決。

---

### 3. Neon DBとWeb APIを接続
`GET /api/tasks`

を作成し、ブラウザからNeon PostgreSQLのタスクを取得。

現在DBから6件のタスク取得を確認。

---

### 4. DATABASE_URLの接続処理を修正
NeonのURLにポート番号が書かれていない場合、

`dbUri.getPort()`

が `-1` になる問題が発生。

その場合はPostgreSQL標準ポート

`5432`

を使用するように修正。

---

### 5. 司令室Web UIを作成
黒×ゴールドをベースにした
Project千人将の司令室画面を作成。

主な表示：

- AI将軍
- 本日の戦況
- 優先任務
- 司令本部
- 任務管理
- AI部隊
- 収益化サポート
- 戦況分析

---

### 6. WebからAI将軍へ命令可能になった
以下のAPIを作成。

`POST /api/ai/command`

処理の流れ：

ブラウザ
↓
Spring Boot
↓
Neonから現在のタスク取得
↓
AI将軍用Prompt生成
↓
OpenAI API
↓
AI回答
↓
Web画面へ表示

これにより、

「次何をすればいい？」

などの命令に対して、
AI将軍が現在のDB状況を確認した上で判断できるようになった。

---

### 7. AI回答をJSON形式で表示
AI将軍の回答を以下の形式に統一。

- 状況
- 次の任務
- 優先度
- 担当AI

Web側で `JSON.parse()` を使用し、
生のJSONではなく見やすいUIとして表示できるようになった。

---

### 8. 任務承認ボタンを追加
AI将軍が提案した任務の下に

`⚔️ この任務を登録`

ボタンを表示するところまで完成。

---

## 発生した問題

### Spring Boot Bean重複エラー
原因：
Javaクラスがデフォルトパッケージに存在していた。

解決：
全クラスを `com.senninsyou` に移動。

### DATABASE_URLが読み込めない
PCやターミナルを再起動すると
PowerShellの環境変数が消える場合がある。

### Web画面が古いままになる
Spring Boot起動時にStaticファイルが
`target/classes` へコピーされるため、
HTMLやJavaScript変更後に古いファイルが表示されることがあった。

対処：

1. Spring Boot停止
2. `mvn clean spring-boot:run`
3. Chromeで `Ctrl + Shift + R`

---

## 現在のシステム

ブラウザ
↓
Spring Boot
↓
Neon PostgreSQL
↓
OpenAI API
↓
AI将軍
↓
Web画面へ回答

ここまで接続完了。

---

## 次回やること

### 最優先

AI将軍が提案した任務を

`⚔️ この任務を登録`

ボタンからNeonへ保存する。

作業予定：

1. `TaskController.java`
2. `POST /api/tasks` を実装
3. `mvn clean compile`
4. Webの承認ボタンからPOST
5. Neonへ保存
6. 優先任務一覧を自動更新

完成すると、

AI提案
↓
人間が承認
↓
DBへ任務登録
↓
Webへ反映

というProject千人将Ver.1の重要なループが完成する。

---

## Project千人将の現在地

CLIアプリ
↓
DB保存
↓
OpenAI API
↓
Spring Boot
↓
Web UI
↓
AI将軍とWebで対話

まで進化。

次は

AI提案 → 承認 → DB登録

を完成させる。