// Maintained repair evidence: javac --release 17; run with compiled replay classes on Java 17.
import dev.brikk.ducklake.corpus.*;
import dev.brikk.ducklake.slt.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class Java17HarnessIntegrity {
    static FileResult replay(String text) {
        return new ReplayDriver().replay(SltParser.INSTANCE.parse("java17.test", text + "\n"));
    }
    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        check(replay("query I\nSELECT 9007199254740993::BIGINT\n----\n9007199254740992\n").getFailed().size() == 1,
            "adjacent integer was accepted");
        check(replay("query T\nSELECT 'true'\n----\n1\n").getFailed().size() == 1, "VARCHAR became Boolean");
        check(replay("query I\nSELECT 1,2 WHERE false\n----\n\n").getFailed().size() == 1, "empty result lost arity");
        check(replay("query R\nSELECT true\n----\n1\n").getPassed() == 1, "SQL BOOLEAN control failed");
        check(replay("query I\nCREATE TABLE t(i INTEGER)\n----\n\nquery I\nDROP TABLE t\n----\n").getPassed() == 2,
            "non-result DDL control failed");
        check(replay("query I nosort lbl\nSELECT 1,2\n----\n\nquery I nosort lbl\n" +
            "SELECT * FROM (VALUES (1),(2)) t(x)\n----\n").getFailed().size() == 1, "label shape change was accepted");
        ReplayReadEngine engine = new ReplayReadEngine() {
            public String getName() { return "java17-wrong-null"; }
            public void connect(OracleAttachment a) {}
            public boolean accepts(String sql) { return true; }
            public List<List<String>> executeQuery(String sql) { return List.of(List.of("NULL")); }
            public void close() {}
        };
        String attach = "statement ok\nATTACH 'ducklake:{TEST_DIR}/m.db' AS lake " +
            "(DATA_PATH '{TEST_DIR}/data', DUCKLAKE_VERSION '1.0', AUTOMATIC_MIGRATION FALSE)\n\n";
        FileResult mirror = new ReplayDriver(engine, null, null, 40).replay(SltParser.INSTANCE.parse("mirror.test",
            "statement ok\nLOAD ducklake\n\n" + attach + "query T\nSELECT NULL::VARCHAR\n----\nNULL\n\n"));
        check(mirror.getFailed().size() == 1 && mirror.getFailed().get(0).getReason().contains("diverged"),
            "live NULL collided with literal token");
        Path root = Files.createTempDirectory(Path.of(args[0]), "harness-java17-");
        Path first = Files.writeString(root.resolve("first.test"), "query I\nSELECT 1\n----\n2\n\n" + attach);
        Path next = Files.writeString(root.resolve("next.test"), "statement ok\nSELECT 1\n\n");
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        kotlin.jvm.functions.Function1<String,String> callback = original -> {
            worker.set(Thread.currentThread());
            try { release.await(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            throw new IllegalStateException("stop disposable worker");
        };
        try {
            CorpusRunner runner = new CorpusRunner(root, Map.of(), null, callback, 1);
            CorpusReport report = runner.run(List.of(first, next));
            check(report.getFailures().size() == 1, "timeout erased failure");
            check(report.getFiles().get(1).getFileSkipReason().startsWith("BLOCKED:"), "reused shared state");
            check(worker.get() != null && !worker.get().isAlive(), "cooperative worker not joined");
        } finally {
            release.countDown();
            if (worker.get() != null) worker.get().join(5000);
        }
        System.out.println("Java " + System.getProperty("java.version") + ": H02/H03/H04 witnesses PASS");
    }
}
