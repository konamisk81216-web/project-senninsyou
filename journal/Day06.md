# Day06（2026/09/04）

## Neon PostgreSQL導入・Java接続

今日はProject千人将に
クラウドデータベースを導入した。

これまでJavaの中だけに存在していたタスクを、
プログラムを終了しても残せる仕組みへ進化させる。

## 今日やったこと

- Neon PostgreSQL環境を構築
- Project千人将用データベースを準備
- tasksテーブルを作成
- PostgreSQL JDBC Driverを導入
- `lib` フォルダへJDBC Driverを配置
- JavaからNeonへの接続テスト
- SELECT処理を実装
- INSERT処理を実装
- DBからタスクを取得
- JavaからタスクをDBへ登録

## Git記録

### 13:30

`Add Neon database connection test`

JavaからNeon PostgreSQLへの
接続テストを実装。

### 14:03

`Add task SELECT from Neon database`

Javaからtasksテーブルの
データを取得できるようになった。

### 14:37

`Add task INSERT to Neon database`

Javaから新しいタスクを
Neonへ保存できるようになった。

## 学んだこと

### PostgreSQL

データを長期間保存・管理するための
データベース。

### Neon

PostgreSQLをクラウド上で
利用できるサービス。

### JDBC

Javaとデータベースをつなぐ「橋」。

Java
↓
JDBC
↓
PostgreSQL

という形で通信する。

### SELECT

データベースからデータを取得する。

### INSERT

データベースへ新しいデータを追加する。

### PreparedStatement

SQLの `?` にJavaから値を渡すための仕組み。

安全にSQLを実行するためにも重要。

## tasksテーブル

タスクには主に、

- id
- task_name
- status
- priority
- assigned_agent
- created_at

を保存できる構造を採用した。

## 今日の成果

Project千人将に
初めてクラウド上の「記憶」ができた。

以前：

Java
↓
ArrayList
↓
終了すると消える

現在：

Java
↓
JDBC
↓
Neon PostgreSQL
↓
終了しても残る

大きな一歩。

## 次回の目標

- タスク管理全体をNeonへ接続
- Taskクラスを導入
- UPDATEを実装
- DELETEを実装
- CRUDを完成させる

## 将軍から今日の一言

「Project千人将に、消えない記憶が宿った。」