// Independent Java17 driver adoption proof. No embedded DuckDB JDBC is allowed on this client classpath.
import dev.brikk.ducklake.catalog.TestingDucklakeDuckDbQuackCatalogServer;
import com.gizmodata.quack.jdbc.codec.DecodeLimits;
import com.gizmodata.quack.jdbc.message.MessageCodec;
import com.gizmodata.quack.jdbc.message.QuackMessage;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class Java17Quack0111Smoke {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static String url(TestingDucklakeDuckDbQuackCatalogServer server) {
        return "jdbc:quack://" + server.getHost() + ":" + server.getMappedPort();
    }
    static Connection connect(String url, TestingDucklakeDuckDbQuackCatalogServer server) throws SQLException {
        Properties properties = new Properties(); properties.setProperty("token", server.getToken());
        properties.setProperty("requestTimeout", "30");
        return DriverManager.getConnection(url, properties);
    }
    public static void main(String[] args) throws Exception {
        try { Class.forName("org.duckdb.DuckDBDriver"); throw new AssertionError("embedded client leaked into direct test"); }
        catch (ClassNotFoundException expected) { }
        try (TestingDucklakeDuckDbQuackCatalogServer server = new TestingDucklakeDuckDbQuackCatalogServer()) {
            healthy(server);
            partition(server, false);
            partition(server, true);
        }
        System.out.println("Java " + System.getProperty("java.version") +
            ": Quack0.11.1/v3/core2.0; zoned-nanos/nulls, healthy cancel/reuse and lost-header/stalled-body cancel deadlines PASS");
    }
    static void healthy(TestingDucklakeDuckDbQuackCatalogServer server) throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (Connection c = connect(url(server), server); Statement s = c.createStatement()) {
            check("0.11.1".equals(c.getMetaData().getDriverVersion()), "wrong driver");
            Object session = c.getClass().getMethod("session").invoke(c);
            check(Long.valueOf(3).equals(session.getClass().getMethod("protocolVersion").invoke(session)), "wrong protocol");
            try (ResultSet r = s.executeQuery("PRAGMA version")) {
                check(r.next() && "v2.0.0".equals(r.getString(1)) && "d54cbbe3df".equals(r.getString(2)), "wrong server");
            }
            try (ResultSet r = s.executeQuery("SELECT '1969-12-31 23:59:59.999999999+00'::TIMESTAMPTZ_NS, NULL::TIMESTAMPTZ_NS")) {
                check(r.next() && r.getMetaData().getScale(1) == 9, "zoned nanos metadata lost");
                check(Instant.ofEpochSecond(-1, 999999999).atOffset(ZoneOffset.UTC).equals(r.getObject(1)), "zoned nanos changed");
                check(r.getObject(2) == null, "NULL changed");
            }
            s.execute("SET max_execution_time=15000");
            Future<?> query = worker.submit(() -> s.executeQuery("/* catalog_0111_healthy */ SELECT sum(i) FROM range(1000000000000)t(i)"));
            awaitActive(server, "catalog_0111_healthy", query);
            long start = System.nanoTime();
            s.cancel();
            cancelled(query);
            check(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(3), "healthy cancel did not complete promptly");
            check(c.isValid(1), "healthy confirmed cancellation lost connection");
        } finally { worker.shutdownNow(); check(worker.awaitTermination(15, TimeUnit.SECONDS), "healthy worker leaked"); }
    }
    static void partition(TestingDucklakeDuckDbQuackCatalogServer server, boolean partial) throws Exception {
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try (Partition proxy = new Partition(server, partial);
             Connection c = connect("jdbc:quack://127.0.0.1:" + proxy.http.getAddress().getPort(), server);
             Statement s = c.createStatement()) {
            s.execute("SET max_execution_time=15000");
            String marker = "catalog_0111_partition_" + partial;
            Future<?> query = callers.submit(() -> s.executeQuery("/* " + marker + " */ SELECT sum(i) FROM range(1000000000000)t(i)"));
            try {
                awaitActive(server, marker, query);
                int prepares = proxy.prepares.get();
                proxy.hold.set(true);
                long start = System.nanoTime();
                Future<?> cancel = callers.submit(() -> { s.cancel(); return null; });
                try { cancel.get(6, TimeUnit.SECONDS); throw new AssertionError("unconfirmed cancel advertised success"); }
                catch (ExecutionException failed) {
                    check(failed.getCause() instanceof SQLException, "lost JDBC failure classification");
                    check(failed.getCause().getMessage().contains("could not be confirmed"), "wrong cancel error: " + failed.getCause());
                }
                check(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(6), "CANCEL/DISCONNECT escaped their own budgets");
                check(c.isClosed() && !c.isValid(1), "unconfirmed session still advertised reusable");
                check(proxy.controlsHeld.await(1, TimeUnit.SECONDS), "both downstream control acknowledgements were not partitioned");
                check(proxy.cancels.get() == 1 && proxy.disconnects.get() == 1, "control was replayed or missing");
                check(proxy.prepares.get() == prepares, "SQL was replayed");
                check(proxy.prepareUuid.get() != null && proxy.prepareUuid.get().equals(proxy.cancelUuid.get()), "cancel used a different query UUID");
                proxy.release.countDown();
                cancelled(query);
                check(proxy.failure.get() == null, "proxy failed: " + proxy.failure.get());
                c.close();
                check(proxy.disconnects.get() == 1, "idempotent close replayed DISCONNECT");
                System.out.println("partial_body=" + partial + " cancel_ms=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            } finally { proxy.release.countDown(); query.cancel(true); }
        } finally { callers.shutdownNow(); check(callers.awaitTermination(15, TimeUnit.SECONDS), "partition workers leaked"); }
    }
    static void cancelled(Future<?> query) throws Exception {
        try { query.get(3, TimeUnit.SECONDS); throw new AssertionError("cancelled query succeeded"); }
        catch (ExecutionException failed) {
            check(failed.getCause() instanceof SQLException && "57014".equals(((SQLException) failed.getCause()).getSQLState()),
                "wrong query cancellation: " + failed.getCause());
        }
    }
    static void awaitActive(TestingDucklakeDuckDbQuackCatalogServer server, String marker, Future<?> query) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        try (Connection admin = connect(url(server), server); Statement s = admin.createStatement()) {
            while (System.nanoTime() < end) {
                check(!query.isDone(), "query finished before cancellation");
                try (ResultSet r = s.executeQuery("SELECT * FROM quack_active_connections()")) {
                    while (r.next()) for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) {
                        String value = r.getString(i); if (value != null && value.contains(marker)) return;
                    }
                }
                Thread.sleep(10);
            }
        }
        throw new AssertionError("server did not expose owned query");
    }
    static final class Partition implements AutoCloseable {
        final HttpServer http;
        final ExecutorService workers = Executors.newCachedThreadPool();
        final AtomicBoolean hold = new AtomicBoolean();
        final CountDownLatch release = new CountDownLatch(1), controlsHeld = new CountDownLatch(2);
        final AtomicInteger prepares = new AtomicInteger(), cancels = new AtomicInteger(), disconnects = new AtomicInteger();
        final AtomicReference<Object> prepareUuid = new AtomicReference<>(), cancelUuid = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        volatile long version = 1;
        Partition(TestingDucklakeDuckDbQuackCatalogServer backend, boolean partial) throws IOException {
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); http.setExecutor(workers);
            HttpClient client = HttpClient.newHttpClient();
            http.createContext("/quack", exchange -> {
                try (exchange) {
                    byte[] request = exchange.getRequestBody().readAllBytes();
                    QuackMessage message = MessageCodec.decode(request, DecodeLimits.DEFAULT, version);
                    if (message instanceof QuackMessage.PrepareRequest p) { prepares.incrementAndGet(); prepareUuid.set(p.queryUuid()); }
                    if (message instanceof QuackMessage.CancelRequest c) { cancels.incrementAndGet(); cancelUuid.set(c.queryUuid()); }
                    if (message instanceof QuackMessage.DisconnectMessage) disconnects.incrementAndGet();
                    HttpResponse<byte[]> reply = client.send(HttpRequest.newBuilder(URI.create("http://" + backend.getHost() + ":" +
                        backend.getMappedPort() + "/quack")).timeout(Duration.ofSeconds(20))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(request)).build(), HttpResponse.BodyHandlers.ofByteArray());
                    if (message instanceof QuackMessage.ConnectionRequest && reply.statusCode() == 200) {
                        QuackMessage decoded = MessageCodec.decode(reply.body());
                        if (decoded instanceof QuackMessage.ConnectionResponse c) version = c.quackVersion().orElse(1L);
                    }
                    if (hold.get()) {
                        int written = 0;
                        if (partial) {
                            exchange.sendResponseHeaders(reply.statusCode(), reply.body().length);
                            exchange.getResponseBody().write(reply.body(), 0, 1); exchange.getResponseBody().flush(); written = 1;
                        }
                        if (message instanceof QuackMessage.CancelRequest || message instanceof QuackMessage.DisconnectMessage) controlsHeld.countDown();
                        check(release.await(12, TimeUnit.SECONDS), "proxy safety deadline expired");
                        try {
                            if (!partial) exchange.sendResponseHeaders(reply.statusCode(), reply.body().length);
                            exchange.getResponseBody().write(reply.body(), written, reply.body().length - written);
                        } catch (IOException expectedClosedClient) { }
                    } else {
                        exchange.sendResponseHeaders(reply.statusCode(), reply.body().length); exchange.getResponseBody().write(reply.body());
                    }
                } catch (Throwable error) { failure.compareAndSet(null, error); }
            });
            http.start();
        }
        public void close() throws InterruptedException {
            release.countDown(); http.stop(0); workers.shutdownNow();
            check(workers.awaitTermination(15, TimeUnit.SECONDS), "proxy workers leaked");
        }
    }
}
