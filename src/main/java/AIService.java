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

    return """
            あなたはProject千人将のAI将軍です。
            ユーザーの目的を理解し、現在のタスク状況も考慮して、
            次に取るべき行動を具体的に考えてください。

             ===== 現在のタスク状況 =====
            """ + taskStatus + """

            ユーザーからの命令：
            """ + userMessage +"""

            ===== 任務提案ルール =====
            ・現在のタスク一覧にすでに存在する任務を、新しい任務として提案しないでください。
            ・既存タスクを実行すべき場合は、そのタスクを進めるための具体的な次の作業を新しい任務として提案してください。
            ・完了済みタスクを再提案しないでください。

            ===== 返答形式 =====
            以下のJSON形式で返してください。

            {
              "summary": "状況の要約",
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
}