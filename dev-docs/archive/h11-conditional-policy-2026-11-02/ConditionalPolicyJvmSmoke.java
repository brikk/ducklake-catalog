import dev.brikk.ducklake.slt.SltConditions;
import java.util.Map;

/** Public Java descriptors and explicit guard truth tables; no JDBC or native engine required. */
public final class ConditionalPolicyJvmSmoke {
    private static void check(boolean value, String detail) {
        if (!value) throw new AssertionError(detail);
    }
    public static void main(String[] args) {
        var api = SltConditions.INSTANCE;
        for (int i = 0; i < 2; i++) for (int j = 0; j < 2; j++) {
            var bindings = Map.of("i", Integer.toString(i), "j", Integer.toString(j));
            check(api.shouldRun("i=0&&j=0", "duckdb", bindings, true) == (i == 1 && j == 1), "skipif truth table");
            check(api.shouldRun("i=0&&j=0", "duckdb", bindings, false) == (i == 0 && j == 0), "onlyif truth table");
            check(api.evaluate("i=0&&j=0", "duckdb", bindings) == (i == 0 && j == 0), "existing positive API changed");
        }
        check(api.shouldRun("POSTGRES", "duckdb", Map.of(), true), "system skip policy");
        check(api.shouldRun("DuCkDb", "duckdb", Map.of(), false), "system only policy");
        check(!api.shouldRun("i=0&&missing=1", "duckdb", Map.of("i", "0"), true), "unreachable binding policy");
        try {
            api.shouldRun("i=0&&not_a_comparison", "duckdb", Map.of("i", "0"), true);
            throw new AssertionError("malformed syntax was hidden");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("must be in the form"), "wrong malformed-syntax failure");
        }
        System.out.println("PASS conditional policy Java " + System.getProperty("java.version"));
    }
}
