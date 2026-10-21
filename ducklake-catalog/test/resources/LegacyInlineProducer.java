import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;

/** Isolated historical producer. Its 1.5.6 native library must never load into the 2.0 test JVM. */
class LegacyInlineProducer {
    public static void main(String[] args) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:duckdb:");
             var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("PRAGMA version")) {
                if (!rows.next() || !"v1.5.6".equals(rows.getString(1))) {
                    throw new IllegalStateException("Expected historical v1.5.6 producer");
                }
            }
            statement.execute(new String(System.in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
