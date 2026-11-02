import dev.brikk.ducklake.slt.SltExpander;
import dev.brikk.ducklake.slt.SltParser;
import java.util.Map;

/** Public unchanged parser/expander entry points exercise both replacement order and condition lookup. */
public final class LoopShadowingJvmSmoke {
    private static void check(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }
    private static String expandOne(String source) {
        var records = SltExpander.INSTANCE.expand(SltParser.INSTANCE.parse("shadow.test", source), "duckdb", Map.of());
        check(records.size() == 1, "wrong command count");
        return records.get(0).getSql();
    }
    public static void main(String[] args) {
        String suffix = "\n\nendloop\n\nendloop\n";
        check(expandOne("foreach i one\n\nforeach i two\n\nonlyif i=two\nstatement ok\nSELECT '{i}'" + suffix)
            .equals("SELECT 'one'"), "text must use outer frame while condition uses inner frame");
        check(expandOne("foreach i {i}-outer\n\nforeach i inner\n\nstatement ok\nSELECT '{i}'" + suffix)
            .equals("SELECT 'inner-outer'"), "all frames must survive for fresh placeholders");
        check(expandOne("foreach i {i}-outer\n\nforeach i inner\n\nstatement ok\nSELECT '${i}'" + suffix)
            .equals("SELECT 'inner-outer-outer'"), "per-frame dollar/brace order changed");
        System.out.println("PASS loop frames Java " + System.getProperty("java.version"));
    }
}
