import dev.brikk.ducklake.corpus.GoldenComparator;
import dev.brikk.ducklake.slt.SltQuery;
import dev.brikk.ducklake.slt.SortMode;
import java.util.Arrays;
import java.util.List;

/** Public typed/untyped descriptors and independent hashes, with no JDBC query execution. */
public final class BooleanHashJvmSmoke {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static SltQuery query(SortMode mode, String types) {
        return new SltQuery(1, "SELECT TRUE", types, mode, null, null, List.of(), null);
    }
    public static void main(String[] args) {
        var c = GoldenComparator.INSTANCE;
        var truth = List.of(List.of("true"));
        check(c.resultHash(query(SortMode.NOSORT, "I"), truth, List.of("BOOLEAN"))
            .equals("1 values hashing to b026324c6904b2a9cb4b88d6d61c81d1"), "typed Boolean hash");
        check(c.resultHash(query(SortMode.NOSORT, "I"), truth)
            .equals("1 values hashing to 74d9a83219cabaab06a69fd318873f33"), "legacy text hash");
        check(c.resultHash(query(SortMode.NOSORT, "T"), truth, List.of("VARCHAR"))
            .equals("1 values hashing to 74d9a83219cabaab06a69fd318873f33"), "VARCHAR changed");
        List<List<String>> rows = List.of(List.of("true", "9"), List.of("false", "true"), Arrays.asList(null, "false"));
        var types = List.of("BOOLEAN", "VARCHAR");
        check(c.resultHash(query(SortMode.NOSORT, "IT"), rows, types)
            .equals("6 values hashing to 5efb26c1b2452e75ae993e36f7931249"), "nosort");
        check(c.resultHash(query(SortMode.ROWSORT, "IT"), rows, types)
            .equals("6 values hashing to 3caa583fb6e97db58d6e459e0be3ba32"), "rowsort");
        check(c.resultHash(query(SortMode.VALUESORT, "IT"), rows, types)
            .equals("6 values hashing to 6d544ba3f4e91363e4c718e42dd827a8"), "valuesort");
        check(rows.get(0).get(0).equals("true"), "raw rows mutated");
        try {
            c.resultHash(query(SortMode.NOSORT, "I"), List.of(List.of("invalid")), List.of("BOOLEAN"));
            throw new AssertionError("invalid Boolean accepted");
        } catch (IllegalArgumentException expected) { }
        System.out.println("Java " + System.getProperty("java.version") + ": typed/untyped Boolean hashes and sorting PASS");
    }
}
