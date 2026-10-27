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

    // 2026-10-27 の有料確認（round2）で、技術の話でハルを指名した項目5にガクが答えた（1回の結果。原因は断定していない）。
    // 指名があれば話題に関係なくその話者が答えること、目的で選ぶのは指名がない場合だけであることを、はっきり分けて書く。
    @Test
    void 指名があれば話題に関係なく指名された話者_指名がない場合だけ目的で決め_両方はハルからガクの順() {
        assertTrue(consult.contains("・はっきりした指名がある場合：利用者が「ハルに」「ガクに」「両方に」などと話者をはっきり指名したら、相談の話題が経営でも技術でも、指名された話者が答えてください。話題と合わないと感じても話者を変えず、その話者の視点で答えてください。"));
        assertTrue(consult.contains("例：「ハルに聞きたい。APIが遅い原因の調べ方は？」は、技術の話でもハルが答えます。「ガクに聞きたい。講座の価格の決め方は？」は、経営の話でもガクが答えます。"));
        assertTrue(consult.contains("・指名がない場合だけ、単語だけで決めず、相談の目的で決めてください。"));
        assertTrue(consult.contains("はハル。技術・情報・実現性の判断"));
        assertTrue(consult.contains("「両方」にしてください。ハル、ガクの順で話します。"));
        assertTrue(consult.contains("指名があったときは、指名に従ったことを書いてください。"));
        assertFalse(consult.contains("それを最優先してください。"), "「最優先」だけで話題との関係をあいまいにした古い書き方は残さない");
        // 指名の決まりは、目的で選ぶ決まりより前にある。
        assertTrue(consult.indexOf("・はっきりした指名がある場合") < consult.indexOf("・指名がない場合だけ"));
        // 「相談の目的で決める」と書く行は、指名がない場合に限っている。
        for (String line : consult.split("\n")) {
            if (line.contains("相談の目的で決めて")) assertTrue(line.contains("指名がない場合だけ"), "指名がない場合に限らずに目的で選ぶよう読める行: " + line.trim());
        }
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

    // 2026-10-26 の有料確認で、相談9件のうち4件が「ハル・ガクの回答＋clarifyQuestion にも質問」で拒否された。
    // 原因は、会話段階の古い指示「必要なら確認したいことを1つ質問してください」と、話者の決まりの食い違い。
    @Test
    void clarifyQuestionは確認のときだけ_プロンプト全体で食い違う指示がない() {
        assertFalse(consult.contains("必要なら確認したいことを1つ質問してください"), "質問を促す古い指示は残さない");
        assertFalse(propose.contains("必要なら確認したいことを1つ質問してください"));
        assertTrue(consult.contains("clarifyQuestionは話者が「確認」のときだけ使います"));
        assertTrue(consult.contains("・clarifyQuestionは、話者が「確認」のときだけ使います。ハル・ガク・両方のときの補足の質問は、答えるのに本当に必要な場合だけにし、返答全体で多くても1つにしてください。「両方」のときも2人合わせて1つまでです。入れる場合は、clarifyQuestionには書かず、話者の発言の最後に1文で尋ねてください。"));
        assertTrue(consult.contains("\"clarifyQuestion\": \"話者が確認のときだけ、目的を確かめる短い質問を1つ。ハル・ガク・両方のときは必ず空文字（確かめたいことは発言の中に書く）\""));
        // clarifyQuestion に触れる行はすべて、「確認」のときに使うか、空文字にするか、書かないかのどれかを言っている。
        for (String line : consult.split("\n")) {
            if (!line.contains("clarifyQuestion")) continue;
            assertTrue(line.contains("確認") || line.contains("clarifyQuestionは空文字") || line.contains("clarifyQuestionには書かず"),
                    "clarifyQuestion を確認以外で使うよう読める行がある: " + line.trim());
        }
    }

    @Test
    void 補足の質問は発言本文に入れ_質問も含めて3文以内() {
        assertTrue(consult.contains("・haruTextとgakuTextは、補足の質問も含めて、それぞれ原則3文以内にしてください。読み上げるので、記号や箇条書きは使わないでください。"));
        assertFalse(consult.contains("・haruTextとgakuTextは、それぞれ原則3文以内にしてください。"), "質問を数に入れない古い書き方は残さない");
    }

    // 有料確認の項目8で「採点します」と、承認前に実行するように読める発言があった。操作は提案の言い方にする。
    @Test
    void 操作は承認前の提案の言い方にする_相談でもタスク案の整理でも() {
        for (String prompt : new String[] {consult, propose}) {
            assertTrue(prompt.contains("・操作を提案するときは、actionLabelもほかの文も「承認すると〜できます」のような提案の言い方にしてください。"));
            assertTrue(prompt.contains("「〜します」「〜しました」「始めます」「実行中です」など、承認の前に始める・実行済みと受け取れる言い方はしないでください。"));
            assertTrue(prompt.contains("\"actionLabel\": \"承認すると行う内容を、提案の言い方で利用者向けに1文で（例：承認すると、品質管理AIが直近の記事を採点できます）。操作がなければ空文字\""));
        }
        assertTrue(consult.contains("話者の発言でも「承認すると〜できます」のように提案として伝え、承認の前に始める・実行済みと受け取れる言い方はしないでください。"));
        assertFalse(propose.contains("話者の発言でも"), "タスク案の整理には話者の決まりを入れない");
    }

    // round2 の項目8で、採点の操作に無い「厳しめか標準か」を尋ねた。用意されていない設定や選択肢は案内しない。
    @Test
    void 操作に無い設定や選択肢を選べるように案内しない_相談でもタスク案の整理でも() {
        for (String prompt : new String[] {consult, propose}) {
            assertTrue(prompt.contains("・上の5つの操作に用意されていない設定や選択肢（採点の厳しさや観点、文字数など）を、選べるように案内したり尋ねたりしないでください。"));
        }
    }

    // round3 の項目8で、品質管理AIの採点の観点を実際と違う内容（読みやすさ、誤字脱字など）で説明した。操作の中身は一覧の内容だけで伝える。
    @Test
    void 操作の中身は一覧の内容だけで説明し_推測で付け足さない_相談でもタスク案の整理でも() {
        for (String prompt : new String[] {consult, propose}) {
            assertTrue(prompt.contains("・操作の中身を説明するときは、上の一覧に書いてある内容だけで伝えてください。採点の観点、書く内容、調べる範囲など、一覧に書いていない中身を推測で付け足さないでください。"));
            assertTrue(prompt.contains("・runQuality：品質管理AIに、直近の記事の下書きを採点させる。"));
        }
    }

    // 補足の質問の数（返答全体で1つまで）と、回答方針の「確認事項を1つだけ」が食い違わないこと。
    @Test
    void 回答方針の確認事項は相談では話者の決まりに従う_タスク案の整理には話者の決まりを持ち込まない() {
        assertTrue(consult.contains("最も重要な確認事項を1つだけ示してください。確認事項の置き場所と数は、上の「話者（ハル・ガク）」の決まりに従ってください。\n・nextTaskは"));
        assertTrue(propose.contains("最も重要な確認事項を1つだけ示してください。\n・nextTaskは"));
        assertFalse(propose.contains("確認事項の置き場所と数は"));
        assertTrue(consult.contains("・haruTextとgakuTextは、補足の質問も含めて、それぞれ原則3文以内にしてください。"));
    }

    @Test
    void 置き換えた古い指示を残さない() {
        assertFalse(consult.contains("『結論：…』『事業の視点（ハル寄り）：…』"), "summaryを3文に分ける古い指示は使わない");
        assertFalse(consult.contains("===== 判断の視点（ハル・ガク） ====="));
    }
}
