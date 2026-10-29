// Standalone minimum-runtime repair witness: compile with --release 17 and run on Java 17.
import dev.brikk.ducklake.corpus.*;
import dev.brikk.ducklake.slt.*;
import java.sql.SQLException;
import java.util.*;

public final class Java17HarnessStages {
    static final String ATTACH = "statement ok\nLOAD ducklake\n\nstatement ok\n" +
        "ATTACH 'ducklake:{TEST_DIR}/meta.db' AS lake (DATA_PATH '{TEST_DIR}/data', " +
        "DUCKLAKE_VERSION '1.0', AUTOMATIC_MIGRATION FALSE)\n\n";
    static final String QUERY = "query I\nSELECT 1\n----\n1\n\n";
    static final class Engine implements ReplayReadEngine {
        boolean accepts = true, failConnect;
        int connects, queries;
        public String getName() { return "java17-stage-control"; }
        public void connect(OracleAttachment a) throws RuntimeException {
            connects++;
            if (failConnect) Java17HarnessStages.<RuntimeException>raise(new SQLException("controlled setup failure"));
        }
        public boolean accepts(String sql) { return accepts; }
        public List<List<String>> executeQuery(String sql) { queries++; return List.of(List.of("1")); }
        public void close() {}
    }
    @SuppressWarnings("unchecked") static <T extends Throwable> void raise(Throwable error) throws T { throw (T) error; }
    static FileResult replay(String text, Engine e) {
        return new ReplayDriver(e, null, null, 40).replay(SltParser.INSTANCE.parse("java17-stages.test", text));
    }
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) {
        Engine rejecting = new Engine(); rejecting.accepts = false;
        FileResult rejected = replay(ATTACH + QUERY, rejecting);
        CorpusReport report = new CorpusReport(List.of(rejected));
        check(report.getTotalMirrorAttempts() == 0 && report.getTotalMirrorSkips() == 1 && rejecting.queries == 0,
            "rejected query was treated as mirror coverage");
        boolean insufficient = false;
        try { report.requireMirrorPasses(1); } catch (IllegalStateException expected) { insufficient = true; }
        check(insufficient, "zero-mirror conformance guard passed");
        Engine verified = new Engine();
        CorpusReport actual = new CorpusReport(List.of(replay(ATTACH + QUERY, verified)));
        actual.requireMirrorPasses(1);
        check(actual.getTotalMirrorAttempts() == verified.queries && actual.getTotalMirrorPasses() == 1,
            "successful mirror not measured");
        String badTime = "query T\nSELECT TIME '24:00:00'\n----\n<REGEX>:.*\n\n";
        FileResult conversion = replay(badTime, null);
        check(conversion.getFailed().size() == 1 && conversion.getFailed().get(0).getReason().contains("column 1"),
            "conversion failure passed a regex");
        Engine setup = new Engine(); setup.failConnect = true;
        String expectedAttach = ATTACH.replace("statement ok\nATTACH", "statement error\nATTACH").stripTrailing() +
            "\n----\ncontrolled setup failure\n\n";
        FileResult expectedError = replay(expectedAttach, setup);
        check(expectedError.getFileSkipReason() == null, "invalid expected-error fixture: " + expectedError);
        check(expectedError.getFailed().size() == 1 && setup.connects == 0,
            "adapter error boundary: connects=" + setup.connects + " result=" + expectedError);
        Engine fault = new Engine(); fault.failConnect = true;
        FileResult failedSetup = replay(ATTACH, fault);
        check(failedSetup.getFailed().size() == 1 && failedSetup.getFailed().get(0).getReason().contains("connect failed"),
            "adapter setup failure was misclassified");
        FileResult legacy = new FileResult("manual.test", null, List.of());
        check(legacy.getQueryCoverage() == null && legacy.copy("copy.test", null, List.of()).getQueryCoverage() == null,
            "manual/copy result fabricated measured evidence");
        System.out.println("Java " + System.getProperty("java.version") + ": H05/H09/H08 stages PASS");
    }
}
