package com.senninsyou;


import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.openai.models.ChatModel;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;


public class AIService {

    private String apiKey;
    private OpenAIClient client;

    public AIService() {

        apiKey = System.getenv("OPENAI_API_KEY");

        if (apiKey != null && !apiKey.isBlank()) {

            client = OpenAIOkHttpClient.builder()
                    .apiKey(apiKey)
                    .build();
        }
    }

    public void startGeneral() {

        if (client == null) {
            System.out.println("OPENAI_API_KEYが設定されていません。");
            System.out.println("現在はAI将軍をオフラインで起動します。");
            return;
        }

        System.out.println("AI将軍を起動します。");
        System.out.println("OpenAI Clientの準備が完了しています。");
    }
    
    public String buildGeneralPrompt(String userMessage, String taskStatus) {
        return buildGeneralPrompt(
                userMessage,
                taskStatus,
                "収益記録はまだ参照されていません。");
    }

    public String buildGeneralPrompt(
            String userMessage,
            String taskStatus,
            String revenueStatus) {

    return """
            あなたはProject千人将のAI将軍です。
            ユーザーの目的を理解し、現在のタスク状況と収益実績を考慮して、
            次に取るべき行動と、新しい収益機会を簡潔かつ具体的に考えてください。

            ===== 現在のタスク状況 =====
            """ + taskStatus + """

            ===== 現在の収益実績 =====
            """ + revenueStatus + """

            ユーザーからの命令：
            """ + userMessage +"""

            ===== 任務提案ルール =====
            ・現在のタスク一覧にすでに存在する任務を、新しい任務として提案しないでください。
            ・完了済みタスクを再提案しないでください。
            ・既存任務の次工程は、独立して管理する価値がある場合だけ新しい任務として提案してください。
            ・1回の回答で提案する最優先任務は1件だけにしてください。
            ・質問、説明依頼、状況確認では、無理に新しい任務を作らないでください。
            ・収益実績がある場合は、利益、作業時間、1時間あたり利益、ROI、想定対実績を根拠として比較してください。
            ・実績に基づき「継続・改善・停止候補」のいずれかを示し、理由を説明してください。
            ・記録が少ない場合は断定せず、「データ不足」と明記してください。
            ・収益記録がない場合や取得できない場合は、実績を推測・創作しないでください。
            ・外部への応募、投稿、決済、投資を自動実行せず、必要な場合は人間の承認を求めてください。

            ===== 市場機会ルール =====
            ・新規事業、拡大中の市場、需要が増えている分野、AIで効率化しやすい仕事を検討してください。
            ・市場の成長性だけでなく、競争、初期費用、必要スキル、収益化までの速さ、ユーザーとの相性を評価してください。
            ・最新情報を参照できない場合は、現在拡大中だと断定せず、調査すべき仮説として示してください。
            ・有望な案でも、最初は低コストの小規模検証を提案してください。

            ===== 回答方針 =====
            ・最初に結論を示し、不要な前置きや入力内容の繰り返しを避けてください。
            ・各項目は原則3文以内にしてください。
            ・事実、推測、提案を混同しないでください。
            ・判断に必要な情報が不足する場合は推測せず、最も重要な確認事項を1つだけ示してください。
            ・nextTaskは完了条件が分かる1件、80文字以内にしてください。
            ・登録すべき任務がない場合はnextTask、priority、assignedAgentを空文字にしてください。
            ・優先度は、高＝期限・損失防止・他任務の阻害、中＝収益や進捗へ直結、低＝改善・整理・将来準備、としてください。
            ・担当はAI将軍、偵察AI、軍師AI、投資分析AI、制作AI、営業AI、品質管理AI、評価AIから選んでください。

            ===== 返答形式 =====
            以下のJSONだけを返し、MarkdownやJSON以外の文章を付けないでください。

            {
              "summary": "状況の要約",
              "revenueInsight": "収益実績に基づく判断。実績がなければ、その旨を明記",
              "performanceDecision": "継続・改善・停止候補・データ不足のいずれかと、その理由",
              "marketOpportunity": "新規事業、成長市場、稼ぎやすさの仮説。根拠がなければ調査事項を明記",
              "nextTask": "次に実行するタスク",
              "priority": "高・中・低",
              "assignedAgent": "担当AI"
              
            }
            """;
    }
    public TaskProposal parseTaskProposal(String jsonText) {

        if (jsonText == null || jsonText.isBlank()) {
        System.out.println("AI将軍の返答が空です。");
        return null;
        }

        try {

        int start = jsonText.indexOf("{");
        int end = jsonText.lastIndexOf("}");

        if (start == -1 || end == -1 || start >= end) {

            System.out.println("AI将軍の返答からJSONを見つけられませんでした。");
            return null;
        }

        jsonText = jsonText.substring(start, end + 1);

        ObjectMapper mapper = new ObjectMapper();

        JsonNode json = mapper.readTree(jsonText);

        if (
            json.get("summary") == null ||
            json.get("nextTask") == null ||
            json.get("priority") == null ||
            json.get("assignedAgent") == null
        ) {
            System.out.println("AI将軍のJSONに必要な項目が足りません。");
            return null;
        }

        String summary = json.get("summary").asText();
        String nextTask = json.get("nextTask").asText();
        String priority = json.get("priority").asText();
        String assignedAgent = json.get("assignedAgent").asText();

        return new TaskProposal(
                summary,
                nextTask,
                priority,
                assignedAgent
        );

        } catch (Exception e) {
            System.out.println("AI将軍のJSON解析に失敗しました。");
            e.printStackTrace();
            return null;
        }
    }

    public String askGeneral(String prompt) {

    if (client == null) {
        System.out.println("OpenAI APIを利用できません。");
        return null;
    }

    try {

        ResponseCreateParams params = ResponseCreateParams.builder()
            .input(prompt)
            .model(ChatModel.GPT_5_2)
            .build();

        Response response = client.responses().create(params);

        return response.output().stream()
            .flatMap(item -> item.message().stream())
            .flatMap(message -> message.content().stream())
            .flatMap(content -> content.outputText().stream())
            .map(outputText -> outputText.text())
            .findFirst()
            .orElse(null);

    } catch (Exception e) {

        System.out.println("AI将軍との通信に失敗しました。");
        System.out.println("ネット接続やAPI設定を確認してください。");

        return null;
        }
    }

    public void close() {
    if (client != null) {
        try {
            client.close();
        } catch (Exception e) {
            System.out.println("OpenAI Clientの終了処理に失敗しました。");
            }
        }
    }
}
