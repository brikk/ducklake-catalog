import dev.brikk.ducklake.corpus.DuckDbText;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.util.HexFormat;
import java.util.Random;

/** Strict per-value native comparison; the digest additionally checks cross-JVM reproducibility. */
public final class NativeFloatFidelityProbe {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        int count = args.length == 0 ? 100_000 : Integer.parseInt(args[0]);
        check(count > 0 && count <= 1_000_000, "probe work budget");
        Method doubles = DuckDbText.class.getMethod("double", double.class);
        Method floats = DuckDbText.class.getMethod("float", float.class);
        MessageDigest nativeDigest = MessageDigest.getInstance("SHA-256");
        MessageDigest javaDigest = MessageDigest.getInstance("SHA-256");
        Random random = new Random(0x15f10a7L);
        try (var c = DriverManager.getConnection("jdbc:duckdb:"); var s = c.createStatement()) {
            try (var r = s.executeQuery("PRAGMA version")) {
                check(r.next() && r.getString(1).equals("v2.0.0") && r.getString(2).equals("d54cbbe3df"), "wrong native core");
            }
            try (var p = c.prepareStatement("SELECT ?::DOUBLE,?::FLOAT,CAST(?::DOUBLE AS VARCHAR),CAST(?::FLOAT AS VARCHAR)")) {
                for (int i = 0; i < count; i++) {
                    double d = Double.longBitsToDouble(random.nextLong());
                    float f = Float.intBitsToFloat(random.nextInt());
                    p.setDouble(1, d); p.setFloat(2, f); p.setDouble(3, d); p.setFloat(4, f);
                    try (var r = p.executeQuery()) {
                        check(r.next(), "missing native row");
                        if (Double.isNaN(d)) check(Double.isNaN(r.getDouble(1)), "NaN binding changed");
                        else check(Double.doubleToRawLongBits(d) == Double.doubleToRawLongBits(r.getDouble(1)), "DOUBLE binding changed");
                        if (Float.isNaN(f)) check(Float.isNaN(r.getFloat(2)), "NaN binding changed");
                        else check(Float.floatToRawIntBits(f) == Float.floatToRawIntBits(r.getFloat(2)), "FLOAT binding changed");
                        String actualD = (String) doubles.invoke(DuckDbText.INSTANCE, d);
                        String actualF = (String) floats.invoke(DuckDbText.INSTANCE, f);
                        String expectedD = r.getString(3), expectedF = r.getString(4);
                        check(expectedD.equals(actualD), "DOUBLE bits=" + Long.toHexString(Double.doubleToRawLongBits(d))
                            + " native=" + expectedD + " actual=" + actualD);
                        check(expectedF.equals(actualF), "FLOAT bits=" + Integer.toHexString(Float.floatToRawIntBits(f))
                            + " native=" + expectedF + " actual=" + actualF);
                        nativeDigest.update((expectedD + "\n" + expectedF + "\n").getBytes(StandardCharsets.UTF_8));
                        javaDigest.update((actualD + "\n" + actualF + "\n").getBytes(StandardCharsets.UTF_8));
                        check(!r.next(), "extra native row");
                    }
                }
            }
        }
        String expected = HexFormat.of().formatHex(nativeDigest.digest());
        String actual = HexFormat.of().formatHex(javaDigest.digest());
        check(expected.equals(actual), "digest mismatch");
        System.out.println("Java " + System.getProperty("java.version") + ": " + count + " paired raw-bit samples PASS sha256=" + actual);
    }
}
