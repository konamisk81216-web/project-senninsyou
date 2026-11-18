package com.senninsyou;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.Test;

// 完了記録の結果を返す処理（finishReporting、L-1用）の試験。1行を更新できたときだけ SAVED。
// 更新0件は成功扱いにせず NOT_SAVED、途中で失敗して保存されたか分からないときは UNKNOWN。例外は投げない。
// 偽の接続だけを使う（本番DBにはつながない）。今の finish は変えていない。
class AiActivityFinishReportingTest {

    private final AiActivityRepository repository = new AiActivityRepository("jdbc:invalid", "user", "password");

    @Test
    void 一行を更新できたら_SAVED() {
        assertEquals(AiActivityRepository.FinishResult.SAVED,
                repository.finishReporting(connection(() -> 1), 5, "完了", ""));
    }

    @Test
    void 更新0件は_成功扱いにせずNOT_SAVED() {
        assertEquals(AiActivityRepository.FinishResult.NOT_SAVED,
                repository.finishReporting(connection(() -> 0), 5, "完了", ""));
    }

    @Test
    void 更新の途中で失敗したら_UNKNOWN_例外は投げない() {
        assertEquals(AiActivityRepository.FinishResult.UNKNOWN,
                repository.finishReporting(connection(() -> {
                    throw new SQLException("テスト用に更新を失敗させた");
                }), 5, "完了", ""));
    }

    @Test
    void 開始に失敗した番号ならDBに触らずNOT_SAVED() {
        assertEquals(AiActivityRepository.FinishResult.NOT_SAVED,
                repository.finishReporting(connection(() -> {
                    throw new AssertionError("DBに触った");
                }), -1, "完了", ""));
        assertEquals(AiActivityRepository.FinishResult.NOT_SAVED, repository.finishReporting(-1, "完了", ""));
    }

    @Test
    void 接続を開けなければ_UNKNOWN_例外は投げない() {
        assertEquals(AiActivityRepository.FinishResult.UNKNOWN, repository.finishReporting(5, "完了", ""));
    }

    private static Connection connection(Callable<Integer> update) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "prepareStatement" -> statement(update);
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static PreparedStatement statement(Callable<Integer> update) {
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(), new Class<?>[] {PreparedStatement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setString", "setLong" -> null;
                    case "executeUpdate" -> update.call();
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
