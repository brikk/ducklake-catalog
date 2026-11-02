import dev.brikk.ducklake.slt.SltExpander;
import dev.brikk.ducklake.slt.SltConditional;
import dev.brikk.ducklake.slt.SltLoop;
import dev.brikk.ducklake.slt.SltParser;
import dev.brikk.ducklake.slt.SltRecord;
import dev.brikk.ducklake.slt.SltUnsupported;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Public parser/AST/expander descriptors; no JDBC, SQL execution or native dependency. */
public final class LoopTerminationJvmSmoke {
    private static void check(boolean value, String detail) {
        if (!value) throw new AssertionError(detail);
    }
    private static void noMissingClosure(List<SltRecord> records, Path source) {
        for (var record : records) {
            if (record instanceof SltUnsupported unsupported) {
                check(!unsupported.getRaw().startsWith("Missing endloop!"), source + ": " + unsupported.getRaw());
            } else if (record instanceof SltLoop loop) noMissingClosure(loop.getBody(), source);
            else if (record instanceof SltConditional guard) noMissingClosure(List.of(guard.getRecord()), source);
        }
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 1) {
            int count = 0;
            Path root = Path.of(args[0]);
            try (var paths = Files.walk(root)) {
                for (var path : paths.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".test") || p.toString().endsWith(".test_slow")).toList()) {
                    noMissingClosure(SltParser.INSTANCE.parse(root.relativize(path).toString(), Files.readString(path)).getRecords(), path);
                    count++;
                }
            }
            System.out.println("PASS closure census: " + count + " pristine files; zero missing-endloop refusals");
            return;
        }
        String body = "\n\nstatement ok\nSELECT 1\n\n";
        for (String header : new String[]{"loop i 0 1", "foreach i one", "concurrentloop i 0 1", "concurrentforeach i one"}) {
            var file = SltParser.INSTANCE.parse("missing.test", header + body);
            check(file.getRecords().size() == 1 && file.getRecords().get(0) instanceof SltUnsupported, "unclosed loop was modeled");
            check(((SltUnsupported) file.getRecords().get(0)).getRaw().contains("Missing endloop"), "missing closure diagnostic");
            check(SltExpander.INSTANCE.expand(file, "duckdb", Map.of()).isEmpty(), "unclosed body became runnable");
        }
        var valid = SltParser.INSTANCE.parse("closed.test", "loop i 0 2\n\nstatement ok\nSELECT ${i}\n\nendloop");
        check(valid.getRecords().get(0) instanceof SltLoop, "closed loop was rejected");
        var records = SltExpander.INSTANCE.expand(valid, "duckdb", Map.of());
        check(records.size() == 2 && records.get(0).getSql().equals("SELECT 0") && records.get(1).getSql().equals("SELECT 1"),
            "closed expansion changed");
        System.out.println("PASS loop termination Java " + System.getProperty("java.version"));
    }
}
