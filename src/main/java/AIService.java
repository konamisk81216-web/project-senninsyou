import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;

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
            """ + userMessage;
    }
}