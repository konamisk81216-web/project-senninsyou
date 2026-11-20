package com.senninsyou;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 実PostgreSQLの試験（NextMovePostgresIT・ConversationPostgresIT）の接続先。試験専用で、本番のjarには入らない。
// ・接続先は 127.0.0.1 の専用テストDB（senninsyou_nextmove_it）だけ。ほかのホスト・DB名・追加のパラメータは拒否する。
// ・本番用の接続設定（DATABASE_URL など）は読まない。足りないときは、ほかの設定で代わりにせず、失敗として返す。
final class PostgresItConfig {

    static final String URL_ENV = "SENNINSYOU_PG_IT_URL";
    static final String USER_ENV = "SENNINSYOU_PG_IT_USER";
    static final String PASSWORD_ENV = "SENNINSYOU_PG_IT_PASSWORD";
    static final String DATABASE = "senninsyou_nextmove_it";
    static final String HOST = "127.0.0.1";

    // jdbc:postgresql://127.0.0.1:<ポート>/senninsyou_nextmove_it だけを許す（? 以降のパラメータ・複数ホストは不可）。
    private static final Pattern ALLOWED_URL = Pattern.compile("^jdbc:postgresql://127\\.0\\.0\\.1:(\\d{1,5})/" + DATABASE + "$");

    final String url;
    final String user;
    final String password;
    final int port;

    private PostgresItConfig(String url, String user, String password, int port) {
        this.url = url;
        this.user = user;
        this.password = password;
        this.port = port;
    }

    static final class Rejected extends Exception {
        Rejected(String message) {
            super(message);
        }
    }

    // 環境変数から読む。値そのもの（特にパスワード）は、エラーの文にも出さない。
    static PostgresItConfig fromEnvironment(Map<String, String> env) throws Rejected {
        String url = env.get(URL_ENV);
        String user = env.get(USER_ENV);
        String password = env.get(PASSWORD_ENV);
        if (url == null || url.isBlank()) {
            throw new Rejected(URL_ENV + " が設定されていません（本番用の接続設定には切り替えません）。");
        }
        if (user == null || user.isBlank()) {
            throw new Rejected(USER_ENV + " が設定されていません。");
        }
        if (password == null || password.isEmpty()) {
            throw new Rejected(PASSWORD_ENV + " が設定されていません。");
        }
        Matcher matcher = ALLOWED_URL.matcher(url);
        if (!matcher.matches()) {
            throw new Rejected(URL_ENV + " は jdbc:postgresql://127.0.0.1:<ポート>/" + DATABASE + " の形だけを受け付けます。");
        }
        int port = Integer.parseInt(matcher.group(1));
        if (port < 1 || port > 65535) {
            throw new Rejected(URL_ENV + " のポート番号が正しくありません。");
        }
        return new PostgresItConfig(url, user, password, port);
    }
}
