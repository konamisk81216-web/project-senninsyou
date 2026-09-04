import java.sql.Connection;
import java.sql.DriverManager;

public class DatabaseTest {

    public static void main(String[] args) {

        String url = System.getenv("DATABASE_URL");

        if (url == null) {
            System.out.println("DATABASE_URLが見つかりません。");
            return;
        }
        java.net.URI dbUri = java.net.URI.create(url);

        String userInfo = dbUri.getUserInfo();
        String[] userParts = userInfo.split(":", 2);

        String user = userParts[0];
        String password = userParts[1];

        String jdbcUrl = "jdbc:postgresql://"
                + dbUri.getHost()
                + dbUri.getPath()
                + "?sslmode=require";

        try {
            Connection connection =
        DriverManager.getConnection(jdbcUrl, user, password);

            System.out.println("🏯 Neonへの接続成功！");

            connection.close();

        } catch (Exception e) {
            System.out.println("接続に失敗しました。");
            e.printStackTrace();
        }
    }
}