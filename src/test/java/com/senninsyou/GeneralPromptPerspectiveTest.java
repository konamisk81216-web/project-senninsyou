package com.senninsyou;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

// AI将軍のプロンプトに、ハル・ガクの話者の決まりが入っていることを確かめる。
// ここで確かめるのは指示の文があるかどうかだけ。AIが実際にその通り話者を選ぶかは、OpenAI での検証で別に確かめる。
// OpenAI には送らない（プロンプトを組み立てるだけ）。実行: mvn test
class GeneralPromptPerspectiveTest {

    private final String consult = new AIService().buildGeneralPrompt("質問", "タスク", "収益", "", false, "制作");
    private final String propose = new AIService().buildGeneralPrompt("質問", "タスク", "収益", "これまでの相談", true, "制作");

    @Test
    void 話者の決まりは回答方針の前にあり_台詞のまねをしない() {
        int speakers = consult.indexOf("===== 話者（ハル・ガク） =====");
        assertTrue(speakers >= 0);
        assertTrue(speakers < consult.indexOf("===== 回答方針 ====="));
        assertTrue(consult.contains("台詞や口調のまね、作品の引用はしないでください。"));
        assertTrue(consult.contains("あなたはAI将軍として、この相談に1回だけ答えます。ハルとガクは別々のAIではなく、この返答の中の話者です。"));
    }

    @Test
    void 指名を最優先し_指名がなければ目的で決め_両方はハルからガクの順() {
        assertTrue(consult.contains("・利用者が「ハルに」「ガクに」「両方に」などと話者をはっきり指名した場合は、それを最優先してください。"));
        assertTrue(consult.contains("・指名がない場合は、単語だけで決めず、相談の目的で決めてください。経営・戦略・アイデアの判断"));
        assertTrue(consult.contains("はハル。技術・情報・実現性の判断"));
        assertTrue(consult.contains("「両方」にしてください。ハル、ガクの順で話します。"));
    }

    @Test
    void 目的が曖昧なら確認を1問だけ_確認では操作を出さない() {
        assertTrue(consult.contains("推測で答えず「確認」にし、目的を確かめる短い質問を1つだけclarifyQuestionに書いてください。"));
        assertTrue(consult.contains("確認のときは操作を提案しないので、action、actionLabel、actionTheme、actionAudienceもすべて空文字にしてください。"));
    }

    @Test
    void 相談の返答形式には話者の5項目があり_タスク案の整理にはない() {
        for (String field : AIService.SPEAKER_FIELDS) {
            assertTrue(consult.contains("\"" + field + "\":"), field + " が相談の返答形式にある");
            assertFalse(propose.contains("\"" + field + "\":"), field + " はタスク案の整理の返答形式にない");
        }
        assertTrue(propose.contains("タスク案の整理は、AI将軍として答えてください。ハル・ガクの話者は使いません。"));
        assertFalse(propose.contains("===== 話者（ハル・ガク） ====="));
    }

    @Test
    void 置き換えた古い指示を残さない() {
        assertFalse(consult.contains("『結論：…』『事業の視点（ハル寄り）：…』"), "summaryを3文に分ける古い指示は使わない");
        assertFalse(consult.contains("===== 判断の視点（ハル・ガク） ====="));
    }
}
