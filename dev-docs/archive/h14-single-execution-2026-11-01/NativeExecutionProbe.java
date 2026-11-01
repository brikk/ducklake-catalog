import java.sql.DriverManager;
import java.sql.Statement;

/** Standard JDBC outcome probe: each distinct SQL command executes exactly once. */
public final class NativeExecutionProbe {
    private static void execute(Statement s, String sql) throws Exception {
        boolean resultSet = s.execute(sql);
        if (resultSet) {
            try (var rows = s.getResultSet()) {
                int count = 0;
                while (rows.next()) count++;
                System.out.println("result rows=" + count + " columns=" + rows.getMetaData().getColumnCount() + " sql=" + sql);
            }
        } else {
            System.out.println("count=" + s.getLargeUpdateCount() + " sql=" + sql);
        }
    }
    public static void main(String[] args) throws Exception {
        try (var c = DriverManager.getConnection("jdbc:duckdb:"); var s = c.createStatement()) {
            execute(s, "CREATE TABLE t(i INTEGER)");
            execute(s, "INSERT INTO t VALUES(1),(2)");
            execute(s, "INSERT INTO t VALUES(3) RETURNING i AS Count");
            execute(s, "WITH v AS (SELECT 4 AS i) INSERT INTO t SELECT i FROM v");
            execute(s, "UPDATE t SET i=i WHERE false RETURNING i");
            execute(s, "SELECT count(*) AS Count FROM t");
            execute(s, "DELETE FROM t WHERE i=4");
            execute(s, "TRUNCATE t");
            execute(s, "DROP TABLE t");
        }
    }
}
