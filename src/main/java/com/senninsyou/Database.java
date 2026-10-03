package com.senninsyou;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

// DATABASE_URL（postgresql://ユーザー:パスワード@ホスト/DB名）をJDBCの接続情報に直す処理を1か所にまとめる。
// 各クラスが同じ解析を書き写していると、直すときに漏れが出るため。
final class Database {

    record Settings(String jdbcUrl, String user, String password) {
    }

    private static volatile Settings settings;

    private Database() {
    }

    static Settings settings() {
        Settings current = settings;
        if (current == null) {
            current = parse(System.getenv("DATABASE_URL"));
            settings = current;
        }
        return current;
    }

    // 接続はTLSの確立に時間がかかる（Neonまで1回0.5〜1秒ほど）。
    // 続けて何度も読むときは、1回開いた接続を使い回すこと。
    static Connection connect() throws SQLException {
        Settings current = settings();
        return DriverManager.getConnection(current.jdbcUrl(), current.user(), current.password());
    }

    private static Settings parse(String databaseUrl) {
        if (databaseUrl == null || databaseUrl.isBlank()) {
            throw new IllegalStateException("DATABASE_URLが設定されていません。");
        }

        URI dbUri = URI.create(databaseUrl);
        String userInfo = dbUri.getUserInfo();

        if (userInfo == null || !userInfo.contains(":")) {
            throw new IllegalStateException("DATABASE_URLの形式が正しくありません。");
        }

        String[] credentials = userInfo.split(":", 2);
        int port = dbUri.getPort() == -1 ? 5432 : dbUri.getPort();
        String jdbcUrl = "jdbc:postgresql://" + dbUri.getHost() + ":" + port + dbUri.getPath()
                + "?sslmode=require";
        return new Settings(jdbcUrl, credentials[0], credentials[1]);
    }
}
