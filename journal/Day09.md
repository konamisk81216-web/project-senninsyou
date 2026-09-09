# Day09（2026/09/08）

## 今日の目標
Project千人将の開発基盤をMavenへ移行し、
AI将軍がユーザーの命令と現在のタスク状況を理解できる土台を作る。

---

## 今日やったこと

### 1. Maven環境を構築
Apache Maven 3.9.16を導入。

最初はMavenのローカルリポジトリが、

C:\Users\小浪 玄\.m2\repository

に作成できずエラーになった。

原因はユーザーフォルダの日本語・スペースを含むパスだったため、
Mavenのsettings.xmlを変更し、

C:\Development\maven-repository

をローカルリポジトリとして使用するようにした。

結果：

BUILD SUCCESS

Mavenが正常に動作するようになった。

---

### 2. PostgreSQL JDBCをMaven管理へ移行

これまでは、

lib/postgresql-42.7.13.jar

を手動で配置していた。

pom.xmlへPostgreSQL JDBCをdependencyとして追加し、
Mavenが自動でライブラリを管理できるようにした。

これにより、長いclasspath指定を毎回書く必要がなくなった。

---

### 3. JavaプロジェクトをMaven標準構成へ変更

以前：

app/
├─ Main.java
├─ AIService.java
├─ Task.java
└─ TaskRepository.java

変更後：

src/
└─ main/
   └─ java/
      ├─ Main.java
      ├─ AIService.java
      ├─ Task.java
      └─ TaskRepository.java

Maven標準構成へ移行。

mvn compile

を実行し、

Compiling 4 source files
BUILD SUCCESS

を確認した。

---

### 4. Neon PostgreSQLの接続を再確認

DATABASE_URLの認証情報を更新。

新しい接続情報を環境変数へ設定し、
Project千人将からNeonへ接続。

タスク一覧を取得できたため、

Java
↓
Maven
↓
TaskRepository
↓
Neon PostgreSQL

の接続が正常であることを確認した。

---

### 5. OpenAI Java SDKを導入

pom.xmlへOpenAI Java SDKを追加。

AIServiceにOpenAIClientを持たせ、
OPENAI_API_KEYが存在する場合だけClientを作る構成にした。

APIキーが存在しない場合は、

「OPENAI_API_KEYが設定されていません。」
「現在はAI将軍をオフラインで起動します。」

と表示する。

APIキーをソースコードへ直接書かない安全な構成にした。

---

### 6. AI将軍への自然言語命令を実装

Main.javaから、

「競合AIサービスを調査して」

のような自然言語を入力できるようにした。

流れ：

玄の命令
↓
Main.java
↓
AIService
↓
buildGeneralPrompt()
↓
AI将軍への命令書

実際に、

===== AI将軍への命令書 =====

あなたはProject千人将のAI将軍です。
ユーザーの目的を理解し、
次に取るべき行動を具体的に考えてください。

ユーザーからの命令：
競合AIサービスを調査して

という命令書を生成できた。

---

### 7. AI将軍が現在の戦況を読めるようにした

TaskRepositoryを整理し、

getAllTasks()

showAllTasks()

getAllTasksAsText()

に役割を分割。

Neonに保存されているタスクを文字列としてAIServiceへ渡せるようにした。

これにより、

Neon DB
↓
TaskRepository
↓
現在のタスク状況
↓
Main
↓
AIService
↓
AI将軍

という経路が完成。

AI将軍への命令書に、

===== 現在のタスク状況 =====

1 | Project千人将のDBを構築する | 完了 | 高 | 千人将
6 | MainからDB保存テスト | 未着手 | 中 | null
7 | 競合サービス調査 | 完了 | 高 | 偵察AI

のような戦況を含められるようになった。

---

### 8. AI将軍の返答形式をJSON化

将来的にAIの回答をJavaが自動処理できるよう、

{
  "summary": "状況の要約",
  "nextTask": "次に実行するタスク",
  "priority": "高・中・低",
  "assignedAgent": "担当AI"
}

という返答形式を定義した。

AIが自由に文章を返すだけではなく、
Project千人将が処理できる「データ」として返答させるため。

---

### 9. TaskProposalクラスを作成

新しく、

TaskProposal.java

を作成。

保持するデータ：

- summary
- nextTask
- priority
- assignedAgent

コンストラクタとgetterを実装。

Task.javaが「実際の任務」なら、

TaskProposal.javaは
「AI将軍が提案した次の任務候補」

という役割。

---

### 10. Jacksonを導入

AIから返ってくるJSONをJavaで解析するため、
Jackson DatabindをMavenへ追加。

AIServiceに、

parseTaskProposal(String jsonText)

を実装。

これにより、

AIのJSON
↓
Jackson
↓
summary / nextTask / priority / assignedAgent
↓
TaskProposal

という変換経路を作った。

最終コンパイル：

BUILD SUCCESS

---

## Gitで保存した主な節目

Maven標準構成への移行：

Migrate project to Maven structure

OpenAI Client準備：

Prepare OpenAI client in AIService

AI将軍への自然言語命令やDB戦況連携についても、
開発の節目としてGit管理を進めた。

---

## 今日つまずいたところ

### Mavenのローカルリポジトリエラー

ユーザーフォルダのパスが原因で、
Mavenが.m2/repositoryを作成できなかった。

settings.xmlから保存先を変更して解決。

### Scanner closed

scanner.close()がwhileループ内にあり、
1回目の処理後にScannerが閉じてしまった。

現在は不要なcloseを外して解決。

### nextInt()後の入力飛ばし

nextInt()のあとにEnterが残るため、

scanner.nextLine();

を追加して解決。

### Java Text Blockの連結エラー

userMessageの後ろを「;」で終了してしまい、
返答形式部分がJavaコードとして解釈された。

+ userMessage + """

として文字列を続けることで解決。

---

## 今日理解したこと

- Maven = Javaの部品管理係
- pom.xml = Mavenの設定・設計書
- dependency = プロジェクトが必要とする外部部品
- src/main/java = Maven標準のJavaコード置き場
- target/classes = Mavenが作ったclassファイル置き場
- 環境変数 = パスワードやAPIキーをコード外から安全に渡す方法
- JSON = AIとJavaが情報を受け渡すための共通フォーマット
- Jackson = JSONをJavaへ翻訳する通訳官
- TaskProposal = AI将軍が考えた任務候補を入れる箱

---

## Project千人将の現在地

Java CLI
↓
Neon PostgreSQL
↓
DB CRUD
↓
TaskRepository
↓
Maven
↓
OpenAI SDK
↓
AIService
↓
自然言語命令
↓
DB戦況認識
↓
JSON返答設計
↓
TaskProposal
↓
JSON解析 ← 現在地

AI将軍が「ユーザーの命令」と
「Project千人将の現在のタスク状況」を同時に認識できる土台が完成した。

---

## 次回やること

1. 偽物のAI JSONを使ってTaskProposal変換を動作テスト
2. AI将軍の任務提案を画面へ表示
3. 「この任務を登録しますか？」という承認機構を作る
4. 承認されたTaskProposalをNeonへ保存
5. OpenAI API課金は必要になるまで後回し
6. API通信可能になったら本物のAI返答で同じ処理を動かす

最終的には、

玄の命令
↓
AI将軍が戦況を確認
↓
次の任務を考える
↓
玄が承認
↓
Neonへ任務登録
↓
AI部隊へ割り振る

までつなげる。

---

## 今日の将軍評価 ⭐

理解度：★★★★☆
実装力：★★★★☆
問題解決力：★★★★★
Git運用：★★★★☆
Project千人将の進軍度：★★★★★

総合：★★★★★

今日は単純にコード量を増やした日ではなく、
Project千人将の「AI将軍が戦況を理解して仕事を作る」という
中核部分へ初めて到達した日。

Maven・DB・AIService・TaskProposalがつながり始め、
「Javaの勉強用CLI」から
「1人会社AI OS」へ明確に形が変わった。