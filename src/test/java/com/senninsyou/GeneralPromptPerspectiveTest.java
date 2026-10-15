package com.senninsyou;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

// AI将軍のプロンプトに、ハル・ガクの判断の視点（質問の種類ごとの視点）の指示が入っていることを確かめる。
// ここで確かめるのは指示の文があるかどうかだけ。実際の回答が指示どおりになるかは、OpenAI での検証で別に確かめる。
// OpenAI には送らない（プロンプトを組み立てるだけ）。実行: mvn test
class GeneralPromptPerspectiveTest {

    private final String prompt = new AIService().buildGeneralPrompt("質問", "タスク", "収益", "", false, "制作");

    @Test
    void 判断の視点の見出しは回答方針の前にあり_台詞のまねをしない() {
        int perspective = prompt.indexOf("===== 判断の視点（ハル・ガク） =====");
        assertTrue(perspective >= 0);
        assertTrue(perspective < prompt.indexOf("===== 回答方針 ====="));
        assertTrue(prompt.contains("台詞のまねや作品の引用はしないでください。"));
        assertTrue(prompt.contains("ほかの欄の書き方は変えません。"), "視点は summary だけに使い、返答の形は変えない");
    }

    @Test
    void 経営だけと技術だけの指示がある() {
        assertTrue(prompt.contains("・経営・事業だけの質問（お金、価格、販売、市場、時間の使い方など）：ハル寄りに、勝ち筋と、いま決めることを示す。技術の話は入れない。"));
        assertTrue(prompt.contains("・IT・情報系だけの質問（実装、設計、不具合、データ、インフラ、セキュリティなど）：ガク寄りに、実現できるか、技術的なリスク、進める順番を示す。事業の話は入れない。"));
    }

    @Test
    void 両方を含む質問は_結論_事業_技術の順に各1文で書く指示がある() {
        assertTrue(prompt.contains("・事業と技術の両方を含む質問では、summaryを『結論：…』『事業の視点（ハル寄り）：…』『技術の視点（ガク寄り）：…』の順に各1文、計3文で書いてください。"));
        assertTrue(prompt.contains("技術の文には、実現方法・技術的リスク・確認の順番のいずれかを具体的に含めてください。"));
        assertFalse(prompt.contains("両方が書かれている場合だけ"), "置き換えた古い指示を残さない");
    }
}
