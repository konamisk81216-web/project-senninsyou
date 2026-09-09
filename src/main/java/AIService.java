import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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

    try {
        ObjectMapper mapper = new ObjectMapper();

        JsonNode json = mapper.readTree(jsonText);

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
}