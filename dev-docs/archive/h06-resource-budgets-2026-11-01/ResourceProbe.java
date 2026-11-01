import dev.brikk.ducklake.slt.*;
import java.util.*;

/** Run only in a separately bounded JVM: never reproduce heap/stack exhaustion in Gradle. */
public final class ResourceProbe {
    public static void main(String[] args) {
        if (Set.of("expansion", "product", "cycle").contains(args[0])) {
            SltFile file;
            Map<String, String> env = Map.of();
            if (args[0].equals("expansion")) {
                file = SltParser.INSTANCE.parse("expansion.test", "statement ok\nSELECT '" + "{X}".repeat(1000) + "'\n\n");
                env = Map.of("X", "x".repeat(100_000));
            } else if (args[0].equals("product")) {
                file = SltParser.INSTANCE.parse("product.test", "loop i 0 1000\n\nloop j 0 1000\n\nstatement ok\nSELECT 1\n\nendloop\n\nendloop\n");
            } else {
                var body = new ArrayList<SltRecord>();
                body.add(new SltLoop(1, "i", List.of("0"), body));
                file = new SltFile("cycle.test", body);
            }
            try {
                SltExpander.INSTANCE.expand(file, "duckdb", env);
                throw new AssertionError("Expected explicit expansion resource refusal");
            } catch (IllegalArgumentException e) {
                if (!e.getMessage().contains("SLT resource limit")) throw e;
                System.out.println(e.getMessage());
            }
            return;
        }
        String input = switch (args[0]) {
            case "range" -> "loop i 0 1000000000\n\nstatement ok\nSELECT 1\n\nendloop\n";
            case "nesting" -> "onlyif duckdb\n".repeat(20000) + "statement ok\nSELECT 1\n\n";
            default -> throw new IllegalArgumentException(args[0]);
        };
        var file = SltParser.INSTANCE.parse("resource-probe.test", input);
        if (file.getRecords().size() != 1 || !(file.getRecords().get(0) instanceof SltUnsupported)) {
            throw new AssertionError("Expected whole-file resource refusal");
        }
        System.out.println(((SltUnsupported) file.getRecords().get(0)).getRaw());
    }
}
