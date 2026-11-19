package com.senninsyou;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

// 実PostgreSQLの試験の接続先の確認（PostgresItConfig）だけを試す。DBにはつながない。実行: mvn test
class PostgresItConfigTest {

    private static Map<String, String> env(String url) {
        Map<String, String> env = new HashMap<>();
        if (url != null) env.put(PostgresItConfig.URL_ENV, url);
        env.put(PostgresItConfig.USER_ENV, "nextmove_it");
        env.put(PostgresItConfig.PASSWORD_ENV, "secret-value-for-test");
        return env;
    }

    @Test
    void acceptsOnlyTheDedicatedLocalTestDatabase() throws Exception {
        PostgresItConfig config = PostgresItConfig.fromEnvironment(env("jdbc:postgresql://127.0.0.1:55432/senninsyou_nextmove_it"));
        assertEquals(55432, config.port);
        assertEquals("nextmove_it", config.user);
    }

    @Test
    void rejectsOtherHostsDatabasesAndParameters() {
        String[] rejected = {
                "jdbc:postgresql://localhost:55432/senninsyou_nextmove_it",
                "jdbc:postgresql://127.0.0.2:55432/senninsyou_nextmove_it",
                "jdbc:postgresql://example.neon.tech:5432/senninsyou_nextmove_it",
                "jdbc:postgresql://127.0.0.1:55432/neondb",
                "jdbc:postgresql://127.0.0.1:55432/senninsyou_nextmove_it_other",
                "jdbc:postgresql://127.0.0.1:55432/senninsyou_nextmove_it?sslmode=require",
                "jdbc:postgresql://127.0.0.1:55432,db.example.com:5432/senninsyou_nextmove_it",
                "jdbc:postgresql://127.0.0.1/senninsyou_nextmove_it",
                "jdbc:postgresql://127.0.0.1:0/senninsyou_nextmove_it",
                "postgresql://user:pass@127.0.0.1:55432/senninsyou_nextmove_it"
        };
        for (String url : rejected) {
            assertThrows(PostgresItConfig.Rejected.class, () -> PostgresItConfig.fromEnvironment(env(url)), url);
        }
    }

    @Test
    void missingSettingsFailAndNeverFallBackToDatabaseUrl() {
        Map<String, String> onlyProduction = new HashMap<>();
        onlyProduction.put("DATABASE_URL", "postgresql://user:pass@127.0.0.1:55432/senninsyou_nextmove_it");
        PostgresItConfig.Rejected error = assertThrows(PostgresItConfig.Rejected.class, () -> PostgresItConfig.fromEnvironment(onlyProduction));
        assertTrue(error.getMessage().contains(PostgresItConfig.URL_ENV));

        Map<String, String> noPassword = env("jdbc:postgresql://127.0.0.1:55432/senninsyou_nextmove_it");
        noPassword.remove(PostgresItConfig.PASSWORD_ENV);
        assertThrows(PostgresItConfig.Rejected.class, () -> PostgresItConfig.fromEnvironment(noPassword));
    }

    @Test
    void errorMessagesDoNotContainThePassword() {
        PostgresItConfig.Rejected error = assertThrows(PostgresItConfig.Rejected.class,
                () -> PostgresItConfig.fromEnvironment(env("jdbc:postgresql://db.example.com:5432/senninsyou_nextmove_it")));
        assertFalse(error.getMessage().contains("secret-value-for-test"));
    }
}
