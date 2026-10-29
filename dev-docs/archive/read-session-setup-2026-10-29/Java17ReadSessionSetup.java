// Review R04 repair witness: javac --release 17; execute with Java 17 and the current catalog classes.
import dev.brikk.ducklake.catalog.*;
import javax.sql.DataSource;
import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.util.concurrent.atomic.*;

public final class Java17ReadSessionSetup {
    static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException e) { throw e.getCause(); }
    }
    static DucklakeCatalogConfig config(TestingDucklakePostgreSqlCatalogServer server, Path root) {
        DucklakeCatalogConfig c = new DucklakeCatalogConfig();
        c.setCatalogDatabaseUrl(server.getJdbcUrl()); c.setCatalogDatabaseUser(server.getUser());
        c.setCatalogDatabasePassword(server.getPassword()); c.setDataPath(root.toString()); c.setMaxCatalogConnections(1);
        return c;
    }
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory(Path.of(args[0]), "read-session-java17-");
        try (TestingDucklakePostgreSqlCatalogServer server = new TestingDucklakePostgreSqlCatalogServer()) {
            try (Connection nativeDb = DriverManager.getConnection("jdbc:duckdb:"); Statement s = nativeDb.createStatement()) {
                s.execute("LOAD ducklake; LOAD postgres");
                s.execute("ATTACH '" + server.getDuckDbAttachUri() + "' AS lake (DATA_PATH '" + root +
                    "', DUCKLAKE_VERSION '1.0', AUTOMATIC_MIGRATION FALSE); DETACH lake");
            }
            try (JdbcDucklakeCatalog reader = new JdbcDucklakeCatalog(config(server, root));
                 JdbcDucklakeCatalog writer = new JdbcDucklakeCatalog(config(server, root))) {
                Field field = JdbcDucklakeCatalog.class.getDeclaredField("dataSource"); field.setAccessible(true);
                DataSource original = (DataSource) field.get(reader);
                AtomicBoolean fail = new AtomicBoolean(true);
                AtomicInteger callbacks = new AtomicInteger(), borrowed = new AtomicInteger(), closed = new AtomicInteger();
                SQLException expected = new SQLException("controlled failure to establish REPEATABLE READ", "08006");
                DataSource proxy = (DataSource) Proxy.newProxyInstance(Java17ReadSessionSetup.class.getClassLoader(),
                    new Class[]{DataSource.class}, (o, method, values) -> {
                        Object result = invoke(method, original, values);
                        if (!method.getName().equals("getConnection")) return result;
                        borrowed.incrementAndGet();
                        Connection connection = (Connection) result;
                        return Proxy.newProxyInstance(Java17ReadSessionSetup.class.getClassLoader(), new Class[]{Connection.class},
                            (co, cm, ca) -> {
                                if (cm.getName().equals("close")) closed.incrementAndGet();
                                if (cm.getName().equals("setTransactionIsolation") && fail.getAndSet(false)) throw expected;
                                return invoke(cm, connection, ca);
                            });
                    });
                field.set(reader, proxy);
                try {
                    try { reader.readSession(() -> { callbacks.incrementAndGet(); return reader.getCurrentSnapshotId(); });
                        throw new AssertionError("failed isolation setup entered its callback"); }
                    catch (Exception actual) { check(actual == expected, "original setup failure was replaced"); }
                    check(callbacks.get() == 0 && closed.get() == 1, "callback/connection cleanup contract failed");
                    long before = writer.getCurrentSnapshotId();
                    long pinned = reader.readSession(() -> {
                        long snapshot = reader.getCurrentSnapshotId();
                        writer.createSchema("competing_after_recovery");
                        check(reader.getCurrentSnapshotId() == snapshot, "recovered session lost its snapshot");
                        check(reader.readSession(() -> reader.getCurrentSnapshotId()) == snapshot, "nested session lost its pin");
                        return snapshot;
                    });
                    check(closed.get() == 2 && borrowed.get() == 2, "nested session borrowed a second connection");
                    check(pinned == before && reader.getCurrentSnapshotId() == before + 1, "session release/recovery failed");
                    check(closed.get() == borrowed.get(), "a pooled connection was not returned");
                    check(((com.zaxxer.hikari.HikariDataSource) original).getHikariPoolMXBean().getActiveConnections() == 0,
                        "pool still has an active lease");
                } finally { field.set(reader, original); }
            }
        }
        System.out.println("Java " + System.getProperty("java.version") + ": review R04 setup rejection, snapshot pin and pool recovery PASS");
    }
}
