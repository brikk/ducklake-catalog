import dev.brikk.ducklake.slt.*;
import java.nio.file.*;
import java.util.*;

/** Read-only census of every pristine source, including previously curated-out files. No SQL. */
public final class CorpusBudgetCensus {
    private static int files, maxChars, maxLines, maxDepth, maxValues, maxRecords;
    private static long maxVisits;
    private static int records;
    private static long walk(List<SltRecord> input, int depth) {
        long work = 0;
        for (var record : input) {
            records++;
            maxDepth = Math.max(maxDepth, depth);
            if (record instanceof SltLoop loop) {
                maxValues = Math.max(maxValues, loop.getValues().size());
                work += 1 + (long) loop.getValues().size() * (1 + walk(loop.getBody(), depth + 1));
            } else if (record instanceof SltConditional conditional) {
                work += 1 + walk(List.of(conditional.getRecord()), depth + 1);
            } else work++;
        }
        return work;
    }
    public static void main(String[] args) throws Exception {
        var root = Path.of(args[0]);
        try (var paths = Files.walk(root)) {
            for (var path : paths.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".test") || p.toString().endsWith(".test_slow")).sorted().toList()) {
                String content = Files.readString(path);
                var file = SltParser.INSTANCE.parse(root.relativize(path).toString(), content);
                for (var record : file.getRecords()) {
                    if (record instanceof SltUnsupported unsupported && unsupported.getDirective().equals("resource-limit")) {
                        throw new AssertionError(path + ": " + unsupported.getRaw());
                    }
                }
                files++;
                maxChars = Math.max(maxChars, content.length());
                maxLines = Math.max(maxLines, content.split("\\R", -1).length);
                records = 0;
                maxVisits = Math.max(maxVisits, walk(file.getRecords(), 1));
                maxRecords = Math.max(maxRecords, records);
            }
        }
        System.out.printf(Locale.ROOT,
            "files=%d resourceRefusals=0 maxChars=%d maxLines=%d maxRecords=%d maxDepth=%d maxLoopValues=%d maxVisits=%d%n",
            files, maxChars, maxLines, maxRecords, maxDepth, maxValues, maxVisits);
    }
}
