package com.senninsyou;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// L-1「選んだ収益機会の根拠から、将軍が次の一手を提案する」の流れ。
// 1. 同じ機会の提案を作成中なら断る（同じアプリのプロセスの中だけ。失敗しても必ず解除する）
// 2. 根拠を1つの読み取りトランザクションで読み、接続を閉じる（AIの返答待ちに入る前に、DBの読み取りを終える）
//    読み取りに失敗したら、AIを呼ばず、実行記録も始めない（推測で提案しない）
// 3. 実行記録を始める。開始に失敗したら、AIを呼ばない（記録のない有料の呼び出しを作らない）
// 4. AI将軍を1回だけ呼ぶ（L-1専用の接続。SDKの中も含めて自動の再試行は0回）
// 5. 返答を確かめる（渡した根拠番号の中だけか・金額や件数を書いていないか）。合わなければ提案を出さない
// 6. 実行記録を閉じる。保存を確認できなければ、そう表示する（AIも記録も呼び直さない）
// 提案だけで、AI社員の実行・承認カード・会話の記録にはつながらない。
final class NextMoveService {

    static final String AGENT = "AI将軍";
    static final String NOTICE = "提案だけです。AI社員は実行していません。";
    static final String RECORD_NOT_CONFIRMED = "完了記録の保存を確認できませんでした。";

    enum Status {
        OK,
        NOT_FOUND,            // 機会が存在しない（AIを呼ばない）
        BUSY,                 // 同じ機会の提案を作成中（AIを呼ばない）
        READ_FAILED,          // 根拠の読み取りに失敗（AIを呼ばない）
        AI_UNAVAILABLE,       // APIキーがない（AIを呼ばない）
        RECORD_START_FAILED,  // 実行記録を始められなかった（AIを呼ばない）
        AI_FAILED,            // AIの返答を受け取れなかった
        INVALID_ANSWER        // 返答が決まりに合わなかった（提案は出さない）
    }

    // 画面へ返す結果。facts（根拠）と proposal（提案）は OK のときだけ入る。
    record Result(
            Status status,
            String message,
            List<NextMovePrompt.Fact> facts,
            NextMoveAnswer proposal,
            String recordNote,
            String notice) {
    }

    interface ConnectionOpener {
        Connection open() throws SQLException;
    }

    interface ActivityLog {
        // 失敗したら -1（今の AiActivityRepository.start と同じ）。
        long start(String agent, String action);

        AiActivityRepository.FinishResult finish(long id, String status, String detail);
    }

    private final ConnectionOpener connections;
    private final NextMoveEvidenceReader reader;
    private final ActivityLog activities;
    private final NextMoveAi ai;
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    NextMoveService(ConnectionOpener connections, NextMoveEvidenceReader reader, ActivityLog activities, NextMoveAi ai) {
        this.connections = connections;
        this.reader = reader;
        this.activities = activities;
        this.ai = ai;
    }

    // 本番の組み立て。DATABASE_URL・OPENAI_API_KEY は呼ばれたときに読む（起動時には読まない）。
    static NextMoveService createDefault() {
        ActivityLog log = new ActivityLog() {
            private AiActivityRepository repository() {
                Database.Settings settings = Database.settings();
                return new AiActivityRepository(settings.jdbcUrl(), settings.user(), settings.password());
            }

            @Override
            public long start(String agent, String action) {
                try {
                    return repository().start(agent, action);
                } catch (RuntimeException e) {
                    System.out.println("稼働記録の開始に失敗しました。");
                    return -1;
                }
            }

            @Override
            public AiActivityRepository.FinishResult finish(long id, String status, String detail) {
                try {
                    return repository().finishReporting(id, status, detail);
                } catch (RuntimeException e) {
                    System.out.println("稼働記録の完了に失敗しました。");
                    return AiActivityRepository.FinishResult.UNKNOWN;
                }
            }
        };
        return new NextMoveService(Database::connect, new NextMoveEvidenceReader(), log,
                NextMoveAi.OpenAi.fromEnvironment());
    }

    Result propose(long opportunityId) {
        if (!inFlight.add(opportunityId)) {
            return failure(Status.BUSY, "同じ収益機会の提案を作成中です。返答のあとにもう一度押してください。", null);
        }
        try {
            return proposeOnce(opportunityId);
        } finally {
            inFlight.remove(opportunityId);
        }
    }

    private Result proposeOnce(long opportunityId) {
        NextMoveEvidence evidence;
        try (Connection connection = connections.open()) {
            evidence = reader.read(connection, opportunityId);
        } catch (SQLException | RuntimeException e) {
            System.out.println("収益機会の次の一手：根拠を読み取れませんでした。");
            return failure(Status.READ_FAILED, "根拠を取得できませんでした。推測では提案しません。", null);
        }
        if (evidence == null) {
            return failure(Status.NOT_FOUND, "収益機会が見つかりませんでした。", null);
        }
        if (!ai.available()) {
            return failure(Status.AI_UNAVAILABLE, "AI将軍を呼び出せない設定です（APIキーがありません）。", null);
        }

        long activityId = activities.start(AGENT, "収益機会の次の一手を提案する（機会 " + opportunityId + "）");
        if (activityId < 0) {
            return failure(Status.RECORD_START_FAILED, "実行記録を開始できなかったため、提案しませんでした。", null);
        }

        String raw;
        try {
            raw = ai.ask(NextMovePrompt.build(evidence));
        } catch (RuntimeException e) {
            raw = null;
        }
        if (raw == null) {
            String note = finishNote(activities.finish(activityId, "失敗", "返答を受け取れませんでした"));
            return failure(Status.AI_FAILED, "AI将軍の返答を受け取れませんでした。", note);
        }

        NextMoveAnswer answer;
        try {
            answer = NextMoveAnswer.parse(raw, evidence.evidenceIds());
        } catch (NextMoveAnswer.InvalidAnswerException e) {
            String note = finishNote(activities.finish(activityId, "失敗", e.getMessage()));
            return failure(Status.INVALID_ANSWER, "AI将軍の返答が決まりに合わなかったため、提案を表示しません（" + e.getMessage() + "）。", note);
        }

        String note = finishNote(activities.finish(activityId, "完了", ""));
        return new Result(Status.OK, "AI将軍の提案です。", NextMovePrompt.facts(evidence), answer, note, NOTICE);
    }

    private static String finishNote(AiActivityRepository.FinishResult result) {
        return result == AiActivityRepository.FinishResult.SAVED ? null : RECORD_NOT_CONFIRMED;
    }

    private static Result failure(Status status, String message, String recordNote) {
        return new Result(status, message, null, null, recordNote, NOTICE);
    }
}
