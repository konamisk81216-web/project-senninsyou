# Day07（2026/09/07）

## タスク管理CRUD完成

今日はProject千人将の
データベース版タスク管理を完成させた。

Javaのタスク管理とNeon PostgreSQLを本格的につなぎ、
タスクの追加・取得・変更・削除を
すべてJavaから操作できるようになった。

## 今日やったこと

- タスク管理機能をNeon PostgreSQLへ接続
- Task.javaを作成
- Taskモデルを導入
- priorityをTaskへ追加
- assignedAgentをTaskへ追加
- DBからTaskオブジェクトを生成
- タスク一覧表示をDB対応
- タスク状態変更機能を実装
- UPDATE処理を実装
- タスク削除機能を実装
- DELETE処理を実装
- CRUDすべての動作確認
- GitHubへ完成地点を保存

## Git記録

### 22:33

`Connect task management to Neon database`

タスク管理機能を
Neon PostgreSQLへ接続。

### 23:01

`Add Task model with priority and assigned agent`

Taskクラスを導入し、

- ID
- タスク名
- 状態
- 優先度
- 担当AI

をJavaオブジェクトとして扱えるようにした。

### 23:19

`Add task status update feature`

タスクIDを指定して
状態を変更できるUPDATE機能を追加。

### 23:37

`Complete task CRUD operations`

DELETEを追加し、
CRUDすべての動作確認が完了。

## CRUDとは

### Create

タスクを作る。

SQL：

`INSERT`

### Read

タスクを読む。

SQL：

`SELECT`

### Update

タスクを変更する。

SQL：

`UPDATE`

### Delete

タスクを削除する。

SQL：

`DELETE`

この4つをまとめてCRUDと呼ぶ。

## Taskクラス

タスクを単なる文字列ではなく、
1つのデータとして扱えるようにした。

Taskには、

- id
- taskName
- status
- priority
- assignedAgent

を持たせる。

これによって将来AI将軍が、

「どの任務を」
「どの優先度で」
「どのAIに任せるか」

を判断できる土台になる。

## 問題と解決

### 問題1：文字コード

日本語入力が文字化けする問題が発生。

JavaとPowerShellの文字コードを確認し、
入力方法を調整して解決した。

### 問題2：nextInt()とnextLine()

数字を入力した後にEnterが残り、
次の文字入力が正常に動かないことがあった。

`scanner.nextLine()`を使って
残ったEnterを処理した。

### 問題3：波括弧

`if`、`while`、`try`、`catch`が増えたことで
`{ }` の対応が崩れ、

`reached end of file while parsing`

のコンパイルエラーが発生。

コードの構造を確認し、
不足していた `}` を追加して解決した。

## 動作確認

タスク追加
↓
DBへ保存

タスク一覧
↓
DBから取得

状態変更
↓
UPDATE

タスク削除
↓
DELETE

すべて正常に動作。

削除テストでは、

`1件のタスクを削除しました。`

と表示され、
削除後もタスク管理メニューへ正常に戻ることを確認した。

## 今日の成果

**Project千人将 タスク管理CRUD完成。**

初期：

Java CLI
↓
ArrayList

現在：

Java CLI
↓
Taskモデル
↓
JDBC
↓
Neon PostgreSQL
↓
CRUD

まで進化した。

Project千人将の「任務管理基盤」が完成した。

## 現在地点

Java CLI
↓
DB永続化 ✅
↓
Taskモデル ✅
↓
CRUD ✅
↓
コード整理 ← NEXT
↓
AI API
↓
AI将軍
↓
Web API
↓
スマホUI
↓
AI部隊
↓
収益化自動化

## 次回の目標

Main.javaに集中しているDB処理を整理する。

TaskRepositoryなどのクラスへDB処理を分離し、
Main.javaを「命令を出す司令官」に戻す。

その後、

**OpenAI API → AI将軍**

の実装へ進む。

## 将軍から今日の一言

「任務を記録する城は完成した。次は、その城に考える将軍を置く。」