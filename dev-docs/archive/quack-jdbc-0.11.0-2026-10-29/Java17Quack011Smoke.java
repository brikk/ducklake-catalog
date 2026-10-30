// Standalone adoption check: Java 17, published Quack driver, no embedded DuckDB JDBC on the client classpath.
import dev.brikk.ducklake.catalog.TestingDucklakeDuckDbQuackCatalogServer;
import java.sql.*;
import java.time.*;
import java.util.Properties;
import java.util.concurrent.*;

public final class Java17Quack011Smoke {
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    static Connection connect(TestingDucklakeDuckDbQuackCatalogServer server) throws SQLException {
        Properties properties = new Properties(); properties.setProperty("token", server.getToken());
        return DriverManager.getConnection("jdbc:quack://" + server.getHost() + ":" + server.getMappedPort(), properties);
    }
    public static void main(String[] args) throws Exception {
        try { Class.forName("org.duckdb.DuckDBDriver"); throw new AssertionError("embedded driver leaked onto direct-Java classpath"); }
        catch (ClassNotFoundException expected) { /* Required independent client. */ }
        try (TestingDucklakeDuckDbQuackCatalogServer server = new TestingDucklakeDuckDbQuackCatalogServer();
             Connection connection = connect(server); Statement statement = connection.createStatement()) {
            check("0.11.0".equals(connection.getMetaData().getDriverVersion()), "wrong driver version");
            Object session = connection.getClass().getMethod("session").invoke(connection);
            check(Long.valueOf(3).equals(session.getClass().getMethod("protocolVersion").invoke(session)), "not protocol v3");
            try (ResultSet rows = statement.executeQuery("PRAGMA version")) {
                check(rows.next() && "v2.0.0".equals(rows.getString(1)) && "d54cbbe3df".equals(rows.getString(2)), "wrong server core");
            }
            try (ResultSet rows = statement.executeQuery("SELECT CAST('1969-12-31 23:59:59.999999999+00' AS TIMESTAMPTZ_NS), " +
                    "epoch_ns(CAST('1969-12-31 23:59:59.999999999+00' AS TIMESTAMPTZ_NS)), NULL::TIMESTAMPTZ_NS")) {
                check(rows.next(), "missing zoned-nanos row");
                check(rows.getMetaData().getColumnType(1) == Types.TIMESTAMP_WITH_TIMEZONE && rows.getMetaData().getScale(1) == 9,
                    "wrong zoned-nanos JDBC metadata");
                OffsetDateTime value = rows.getObject(1, OffsetDateTime.class);
                check(value.equals(Instant.ofEpochSecond(-1, 999999999).atOffset(ZoneOffset.UTC)), "lost negative-epoch nanoseconds");
                check(rows.getLong(2) == -1 && rows.getObject(3) == null, "epoch/null changed");
            }
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try (Statement blocked = connection.createStatement()) {
                blocked.execute("SET max_execution_time=10000"); // fixture safety, never an unbounded test query
                String marker = "catalog_quack011_cancel_prepare";
                Future<?> execution = worker.submit(() -> {
                    try (ResultSet rows = blocked.executeQuery("/* " + marker + " */ SELECT sum(i) FROM range(1000000000000) t(i)")) {
                        while (rows.next()) rows.getObject(1);
                    }
                    return null;
                });
                awaitActive(server, marker, execution);
                long start = System.nanoTime();
                blocked.cancel();
                try { execution.get(3, TimeUnit.SECONDS); throw new AssertionError("cancelled query reported success"); }
                catch (ExecutionException failure) {
                    check(failure.getCause() instanceof SQLException, "cancellation lost JDBC error classification");
                    check("57014".equals(((SQLException) failure.getCause()).getSQLState()), "not a query-cancelled SQL state");
                }
                check(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(3), "cancel exceeded fixture bound");
                check(connection.isValid(1), "confirmed cancellation made connection unusable");
            } finally {
                worker.shutdownNow();
                check(worker.awaitTermination(15, TimeUnit.SECONDS), "owned cancellation worker did not stop");
            }
        }
        System.out.println("Java " + System.getProperty("java.version") + ": Quack0.11.0/v3/DuckDB2.0.0, zoned-nanos and real PREPARE cancellation PASS");
    }
    static void awaitActive(TestingDucklakeDuckDbQuackCatalogServer server, String marker, Future<?> execution) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        try (Connection admin = connect(server); Statement statement = admin.createStatement()) {
            while (System.nanoTime() < deadline) {
                check(!execution.isDone(), "query finished before cancellation");
                try (ResultSet rows = statement.executeQuery("SELECT * FROM quack_active_connections()")) {
                    while (rows.next()) {
                        for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++) {
                            String value = rows.getString(i);
                            if (value != null && value.contains(marker)) return;
                        }
                    }
                }
                Thread.sleep(10);
            }
        }
        throw new AssertionError("server never exposed the owned active query");
    }
}
