import dev.brikk.ducklake.corpus.DuckDbText;
import java.util.List;

/** Native-expected text checked through public methods, including the actual Java17.0.2 floor. */
public final class FloatTextJvmSmoke {
    private record Case(String method, Class<?> type, Object value, String expected) { }
    public static void main(String[] args) throws Exception {
        var cases = List.of(
            new Case("double", double.class, 1e23, "1e+23"),
            new Case("double", double.class, -1e23, "-1e+23"),
            new Case("float", float.class, Float.MIN_NORMAL, "1.1754944e-38"),
            new Case("float", float.class, Float.intBitsToFloat(0xca762bff), "-4033279.75"),
            new Case("double", double.class, Double.MIN_VALUE, "5e-324"),
            new Case("double", double.class, 2 * Double.MIN_VALUE, "1e-323"),
            new Case("float", float.class, Float.MIN_VALUE, "1e-45"),
            new Case("float", float.class, 2 * Float.MIN_VALUE, "3e-45"),
            new Case("double", double.class, -0.0, "-0.0"),
            new Case("float", float.class, -0.0f, "-0.0"),
            new Case("double", double.class, Double.NaN, "nan"),
            new Case("double", double.class, Double.longBitsToDouble(0xfff8000000000001L), "-nan"),
            new Case("float", float.class, Float.intBitsToFloat(0xffc00001), "-nan"),
            new Case("double", double.class, Double.POSITIVE_INFINITY, "inf"),
            new Case("float", float.class, Float.NEGATIVE_INFINITY, "-inf"));
        int failures = 0;
        for (var c : cases) {
            String actual = (String) DuckDbText.class.getMethod(c.method, c.type).invoke(DuckDbText.INSTANCE, c.value);
            if (!actual.equals(c.expected)) {
                failures++;
                System.out.println(c.method + " expected=" + c.expected + " actual=" + actual);
            }
        }
        System.out.println("Java " + System.getProperty("java.version") + ": " + cases.size() + " floating cases, " + failures + " failures");
        if (failures != 0) throw new AssertionError("native text mismatch");
    }
}
