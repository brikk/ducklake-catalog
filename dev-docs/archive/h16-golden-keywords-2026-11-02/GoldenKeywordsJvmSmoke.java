import dev.brikk.ducklake.corpus.GoldenComparator;
import dev.brikk.ducklake.slt.SltQuery;
import dev.brikk.ducklake.slt.SortMode;
import java.util.List;

/** Public Java comparison overloads; no database or native SQL execution. */
public final class GoldenKeywordsJvmSmoke {
    private static SltQuery query(String types, String expected) {
        return new SltQuery(1, "SELECT 1", types, SortMode.NOSORT, null, null, List.of(expected), null);
    }
    private static void check(boolean value, String detail) {
        if (!value) throw new AssertionError(detail);
    }
    public static void main(String[] args) {
        var api = GoldenComparator.INSTANCE;
        var match = GoldenComparator.Comparison.Match.INSTANCE;
        String raw = "${FOO}";
        var rows = List.of(List.of(raw, "bar"));
        int[] calls = {0};
        check(api.compare(query("TT", raw + "\t" + raw), rows, List.of("VARCHAR", "VARCHAR"), expected -> {
            calls[0]++;
            return expected.replace(raw, "bar");
        }) == match, "mixed raw/expanded cells");
        check(calls[0] == 1, "raw matches must not invoke expansion");
        check(api.compare(query("T", raw), List.of(List.of(raw))) == match, "old two-arg descriptor");
        check(api.compare(query("T", raw), List.of(List.of(raw)), List.of("VARCHAR")) == match, "old three-arg descriptor");
        check(api.compare(query("I", "${N}"), List.of(List.of("1")), List.of("INTEGER"), expected -> "1.0")
            instanceof GoldenComparator.Comparison.Mismatch, "expanded numeric coercion must not activate");
        check(api.compare(query("T", "${PAT}"), List.of(List.of("wrong")), List.of("VARCHAR"), expected -> "<REGEX>:.*")
            instanceof GoldenComparator.Comparison.Mismatch, "expanded regex control must not activate");
        check(api.compare(query("I", "${B}"), List.of(List.of("true")), List.of("BOOLEAN"), expected -> "1") == match,
            "native Boolean literal provenance");
        check(api.compare(query("I", "${B}"), List.of(List.of("true")), List.of("BOOLEAN"), expected -> "true")
            instanceof GoldenComparator.Comparison.Mismatch, "expanded Boolean coercion must not activate");
        System.out.println("PASS golden keyword comparison Java " + System.getProperty("java.version"));
    }
}
