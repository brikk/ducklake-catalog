import dev.brikk.ducklake.corpus.GoldenComparator;
import java.sql.DriverManager;
import java.util.List;

/** Public Java caller, compiled with --release17 against the sealed current replay/driver JARs. */
public final class TupleRenderingJvmSmoke {
    private static void check(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }
    public static void main(String[] args) throws Exception {
        try (var c = DriverManager.getConnection("jdbc:duckdb:"); var s = c.createStatement()) {
            String[][] cases = {
                {"row(1,'x')", "(1, x)"},
                {"[row(1,'x'),NULL]", "[(1, x), NULL]"},
                {"struct_pack(element1:=1,element2:='x')", "{'element1': 1, 'element2': x}"},
                {"row(NULL::INTEGER,'NULL','')", "(NULL, 'NULL', '')"},
                {"row(1e23::DOUBLE,1.1754943508222875e-38::FLOAT)", "(1e+23, 1.1754944e-38)"},
                {"MAP {row(1,'x'):'value'}", "{(1, x)=value}"},
                {"union_value(t:=row(1,'x'))", "(1, x)"},
            };
            for (String[] item : cases) {
                try (var r = s.executeQuery("SELECT " + item[0] + ",CAST(" + item[0] + " AS VARCHAR)")) {
                    check(r.next(), "missing independent fixture row");
                    check(item[1].equals(r.getString(2)), "invalid native fixture: " + item[0]);
                    Object value = r.getObject(1);
                    check(item[1].equals(GoldenComparator.INSTANCE.renderCell(value)), "public cell renderer: " + item[0]);
                    check(item[1].equals(GoldenComparator.INSTANCE.renderNested(value)), "public nested renderer: " + item[0]);
                }
            }
            String geo = "union_value(t:=row('POINT (1 2)'::GEOMETRY,7))";
            try (var r = s.executeQuery("SELECT " + geo + ",CAST(" + geo + " AS VARCHAR)")) {
                check(GoldenComparator.INSTANCE.readRows(r).equals(List.of(List.of("('POINT (1 2)', 7)", "('POINT (1 2)', 7)"))),
                    "native typed tuple geometry changed or became BLOB text");
            }
        }
        System.out.println("PASS public tuple rendering Java " + System.getProperty("java.version"));
    }
}
