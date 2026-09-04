import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.ResultSet;
import java.sql.PreparedStatement;

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

String sql = "INSERT INTO tasks (task_name) VALUES (?)";

PreparedStatement insertStatement =
        connection.prepareStatement(sql);

insertStatement.setString(1, "Project千人将テスト任務");

int rows = insertStatement.executeUpdate();

System.out.println(rows + "件のタスクを登録しました！");

            Statement statement = connection.createStatement();

ResultSet result = statement.executeQuery("SELECT * FROM tasks");

while (result.next()) {
    int id = result.getInt("id");
    String taskName = result.getString("task_name");
    String status = result.getString("status");

    System.out.println(
        id + " | " + taskName + " | " + status
    );
}

            connection.close();

        } catch (Exception e) {
            System.out.println("接続に失敗しました。");
            e.printStackTrace();
        }
    }
}