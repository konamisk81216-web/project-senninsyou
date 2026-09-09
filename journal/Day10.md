# Day10（2026/09/09）

## 今日の目標
Project千人将をCLI中心のシステムからWebアプリへ進化させる。

## 今日やったこと

### 1. AI提案 → 承認 → DB登録を最終確認
AI将軍がNeonの現在タスクを確認し、次の任務を提案。

人間が「1：はい」で承認すると、提案された任務がNeonへ登録されることを確認した。

さらにタスク一覧を再取得し、新しい任務が表示されたため、

AI将軍
↓
タスク状況確認
↓
任務提案
↓
人間による承認
↓
Neonへ保存
↓
再取得

の一連の流れが完成した。

### 2. Spring Boot導入
pom.xmlにSpring Bootを追加。

Java 21環境でコンパイルし、

BUILD SUCCESS

を確認した。

### 3. Project千人将をWebサーバーとして起動
SenninsyouApplication.javaを作成。

Spring Bootからlocalhost:8080を起動し、
ブラウザに

「Project千人将 起動中」

と表示することに成功。

Project千人将が初めてWebアプリとして動作した。

### 4. Javaファイルのパッケージ整理
Javaファイルを

src/main/java/com/senninsyou/

へ移動。

対象：
- Main.java
- AIService.java
- Task.java
- TaskProposal.java
- TaskRepository.java
- SenninsyouApplication.java

package com.senninsyou;

を設定し、コンパイル成功。

### 5. TaskController作成
Webからタスクを取得するため、

TaskController.java

を追加。

GET /api/tasks

でNeonのタスク一覧を取得できるAPIを作成した。

### 6. Neon接続エラー修正
DATABASE_URLにポート番号が存在しない場合、

dbUri.getPort()

が -1 になる問題が発生。

PostgreSQL標準ポート5432を使用する処理を追加して解決。

### 7. Web → Java → Neon 開通
ブラウザから

http://localhost:8080/api/tasks

へアクセス。

Neonに保存されている本物のタスク一覧がJSONで表示された。

これにより、

ブラウザ
↓
Spring Boot
↓
TaskController
↓
TaskRepository
↓
Neon PostgreSQL
↓
JSONレスポンス

の通信が完成した。

## Git

Web API基盤完成時点をGitHubへ保存。

コミット：

Add Spring Boot web API foundation

commit:
20f2faf

push成功。

mainとorigin/mainの同期を確認。

※ lib/ は旧手動PostgreSQL jarのため未追跡のまま。

## 現在のProject千人将

CLI
↓
AI将軍
↓
OpenAI API
↓
Neon
↓
人間承認
↓
DB保存

に加えて、

ブラウザ
↓
Spring Boot Web API
↓
Neon

まで完成。

Project千人将はCLIアプリからWebアプリへ移行を開始した。

## 次回の開始地点

次は「千人将 司令画面UI」を作る。

予定：

1. src/main/resources/static/ を作成
2. index.htmlを作成
3. 黒基調のProject千人将司令画面を表示
4. /api/tasks からNeonの任務を取得
5. タスクをカード形式で画面表示
6. AI将軍への命令入力欄を追加

その後、

命令入力
↓
AI将軍
↓
任務提案
↓
承認
↓
Neon登録
↓
画面更新

までWeb上で完結させる。

## 今日の到達点

「ターミナルの中にいる千人将」から
「ブラウザから触れる千人将」への移行開始。

次は、いよいよ司令室を画面として作る。