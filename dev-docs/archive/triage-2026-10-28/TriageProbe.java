// Archived triage witness. Uses only disposable, probe-owned fixtures; not a maintained passing test.
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.brikk.ducklake.catalog.*;
import dev.brikk.ducklake.corpus.*;
import dev.brikk.ducklake.slt.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Disposable witnesses against unchanged compiled production classes, not fixes or new goldens. */
public class TriageProbe {
    static Path root;
    static final Map<String,Object> results = new LinkedHashMap<>();
    interface Probe { Object run() throws Exception; }
    static void probe(String id, Probe p) {
        try { results.put(id,p.run()); }
        catch (Throwable e) { results.put(id,Map.of("probe_error",e.toString())); e.printStackTrace(System.err); }
    }
    static SltFile parse(String sql) { return SltParser.INSTANCE.parse("triage.test",sql+"\n"); }
    static SltQuery query(String types,String expected) {
        return new SltQuery(1,"SELECT 1",types,SortMode.NOSORT,null,null,List.of(expected),null);
    }
    static List<List<String>> rows(String sql) throws Exception {
        try (Connection c=DriverManager.getConnection("jdbc:duckdb:"); Statement s=c.createStatement(); ResultSet r=s.executeQuery(sql)) {
            return GoldenComparator.INSTANCE.readRows(r);
        }
    }
    static FileResult replay(String text) { return new ReplayDriver().replay(parse(text)); }
    static String attach(String alias) {
        return "statement ok\nATTACH 'ducklake:{TEST_DIR}/"+alias+".db' AS "+alias+
            " (DATA_PATH '{TEST_DIR}/"+alias+"-data', DUCKLAKE_VERSION '1.0', AUTOMATIC_MIGRATION FALSE)\n\n";
    }
    static String prefix() { return "statement ok\nLOAD ducklake\n\n"+attach("lake"); }
    static class Engine implements ReplayReadEngine {
        final List<String> attachments=new ArrayList<>();
        List<List<String>> output=List.of(List.of("1"));
        int queries; boolean accept=true,failConnect;
        public String getName() { return "disposable-triage"; }
        public void connect(OracleAttachment a) {
            if(failConnect) TriageProbe.<RuntimeException>sneaky(new SQLException("adapter connect fault"));
            attachments.add(a.getCatalogAlias());
        }
        public boolean accepts(String sql) { return accept; }
        public List<List<String>> executeQuery(String sql) { queries++; return output; }
        public void close() {}
    }
    @SuppressWarnings("unchecked") static <E extends Throwable> void sneaky(Throwable e) throws E { throw (E)e; }
    static FileResult mirror(Engine engine,String text) {
        return new ReplayDriver(engine,null,null,40).replay(parse(text));
    }
    static Map<String,Object> outcome(FileResult f) {
        return Map.of("passed",f.getPassed(),"failed",f.getFailed().size(),"skipped",f.getSkipped().size(),
            "failure_reasons",f.getFailed().stream().map(RecordOutcome.Fail::getReason).toList());
    }
    public static void main(String[] args) throws Exception {
        if(args.length>0 && args[0].equals("huge-loop")) {
            try { parse("loop i 0 1000000000\n\nquery I\nSELECT 1\n----\n1\n\nendloop\n");
                System.out.println("unexpectedly_returned"); }
            catch(OutOfMemoryError e) { System.out.println("bounded_child_heap_exhausted_before_rejecting_loop"); }
            return;
        }
        root=Path.of(args[0]); Files.createDirectories(root);
        results.put("java",System.getProperty("java.version"));
        if(args.length>1 && args[1].equals("format-only")) {
            format(); System.out.println(new ObjectMapper().writeValueAsString(results)); return;
        }
        probe("H01",()->{
            Path parent=Files.createTempDirectory(root,"h01-parent-");
            Path target=Files.createTempDirectory(root,"h01-target-");
            Path a=Files.writeString(parent.resolve("preexisting.txt"),"owned only by this disposable probe");
            Path b=Files.writeString(target.resolve("external-to-parent.txt"),"also probe-owned");
            Files.createSymbolicLink(parent.resolve("symlink"),target);
            try(DuckDbOracle o=new DuckDbOracle(parent,null)) {}
            return Map.of("supplied_parent_survives",Files.exists(parent),"preexisting_marker_survives",Files.exists(a),
                "symlink_target_marker_survives",Files.exists(b));
        });
        probe("H02",()->{
            var big=rows("SELECT 10000000001::BIGINT");
            var boolText=rows("SELECT 'true'::VARCHAR");
            var two=rows("SELECT 1,2");
            return Map.of("wrong_bigint",GoldenComparator.INSTANCE.compare(query("I","10000000000"),big).toString(),
                "varchar_as_boolean",GoldenComparator.INSTANCE.compare(query("T","1"),boolText).toString(),
                "two_columns_declared_one",GoldenComparator.INSTANCE.compare(query("I","1\t2"),two).toString(),
                "wrong_bigint_control",GoldenComparator.INSTANCE.compare(query("I","7"),big).toString());
        });
        probe("H03",()->{
            Engine e=new Engine();e.output=List.of(List.of("NULL"));
            FileResult nulls=mirror(e,prefix()+"query T\nSELECT NULL::VARCHAR\n----\nNULL\n");
            Engine empty=new Engine();empty.output=List.of(List.of("(empty)"));
            FileResult empties=mirror(empty,prefix()+"query T\nSELECT ''\n----\n(empty)\n");
            Engine sep=new Engine();sep.output=List.of(List.of("a\u0001b","c"));
            FileResult separators=mirror(sep,prefix()+"query TT\nSELECT 'a','b'||chr(1)||'c'\n----\na\tb\u0001c\n");
            Engine negative=new Engine();negative.output=List.of(List.of("different"));
            FileResult control=mirror(negative,prefix()+"query T\nSELECT NULL::VARCHAR\n----\nNULL\n");
            return Map.of("null_vs_literal",outcome(nulls),"empty_vs_literal",outcome(empties),
                "delimiter_collision",outcome(separators),"noncolliding_control",outcome(control));
        });
        probe("H04",()->{
            Path dir=Files.createTempDirectory(root,"h04-");
            Path file=dir.resolve("owned-timeout.test");
            Files.writeString(file,"query I\nSELECT 1\n----\n2\n\n"+attach("later"));
            CountDownLatch release=new CountDownLatch(1);
            AtomicReference<Thread> worker=new AtomicReference<>();
            kotlin.jvm.functions.Function1<String,String> block=value->{
                worker.set(Thread.currentThread());
                try { release.await(); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
                throw new IllegalStateException("stop this probe's worker before any ATTACH");
            };
            CorpusReport report;
            try { report=new CorpusRunner(dir,Map.of(),null,block,1).run(List.of(file)); }
            finally { release.countDown(); if(worker.get()!=null)worker.get().join(5000); }
            return Map.of("failure_then_timeout_failures_reported",report.getFailures().size(),
                "timeout_reported_as_file_skip",report.getSkippedFiles().get(0).getFileSkipReason(),
                "worker_released_and_joined",worker.get()!=null&&!worker.get().isAlive(),
                "without_timeout_control",outcome(replay("query I\nSELECT 1\n----\n2\n")));
        });
        probe("H05",()->{
            Engine e=new Engine();e.accept=false;
            FileResult f=mirror(e,prefix()+"query I\nSELECT 1\n----\n1\n");
            return Map.of("outcome",outcome(f),"engine_queries",e.queries,"connections",e.attachments);
        });
        probe("H07",()->{
            Engine e=new Engine();
            FileResult f=mirror(e,"statement ok\nLOAD ducklake\n\n"+attach("aa")+attach("bb")+attach("cc")+
                "query I\nSELECT 1\n----\n1\n");
            Engine failed=new Engine();
            FileResult ff=mirror(failed,prefix()+"statement error\nDETACH lake unexpected_extra_token\n----\n\n"+
                "query I\nSELECT 1\n----\n1\n");
            Engine quoted=new Engine();
            FileResult fq=mirror(quoted,prefix()+"statement ok\nDETACH \"lake\"\n\nquery I\nSELECT 1\n----\n1\n");
            return Map.of("abc_connections",e.attachments,"abc_outcome",outcome(f),
                "failed_detach_queries",failed.queries,"failed_detach_outcome",outcome(ff),
                "quoted_detach_queries",quoted.queries,"quoted_detach_outcome",outcome(fq));
        });
        probe("H08",()->{
            Engine e=new Engine();e.failConnect=true;
            String text="statement ok\nLOAD ducklake\n\nstatement error\n"+
                "ATTACH 'ducklake:{TEST_DIR}/h08.db' AS lake (DATA_PATH '{TEST_DIR}/h08-data', DUCKLAKE_VERSION '1.0')\n"+
                "----\nadapter connect fault\n";
            return outcome(mirror(e,text));
        });
        probe("H09",()->{
            AtomicBoolean first=new AtomicBoolean(true);
            ResultSetMetaData meta=(ResultSetMetaData)Proxy.newProxyInstance(TriageProbe.class.getClassLoader(),
                new Class[]{ResultSetMetaData.class},(o,m,a)->switch(m.getName()) {
                    case "getColumnCount" -> 1;case "getColumnTypeName" -> "VARCHAR";default -> null;
                });
            ResultSet rs=(ResultSet)Proxy.newProxyInstance(TriageProbe.class.getClassLoader(),new Class[]{ResultSet.class},
                (o,m,a)->switch(m.getName()) {
                    case "getMetaData" -> meta;case "next" -> first.getAndSet(false);
                    case "getObject" -> throw new SQLException("injected getter conversion failure");default -> null;
                });
            var values=GoldenComparator.INSTANCE.readRows(rs);
            return Map.of("values",values,"regex_acceptance",GoldenComparator.INSTANCE.compare(query("T","<REGEX>:.*"),values).toString());
        });
        probe("H10",()->Map.of("pre_epoch",rows("SELECT TIMESTAMP_NS '1969-12-31 23:59:59.123456789'"),
            "one_nanosecond_later",rows("SELECT TIMESTAMP_NS '1969-12-31 23:59:59.123456790'")));
        probe("H11",()->{
            var f=parse("loop i 0 1\n\nskipif i=0&&i=1\nquery I\nSELECT 9\n----\n9\n\nendloop\n");
            return Map.of("expanded_statements",SltExpander.INSTANCE.expand(f,"duckdb",Map.of()).size());
        });
        probe("H12",()->{
            var f=parse("loop i 0 1\n\nquery I\nSELECT 1\n----\n1\n");
            return Map.of("parsed_as",f.getRecords().get(0).getClass().getSimpleName(),
                "expanded_statements",SltExpander.INSTANCE.expand(f,"duckdb",Map.of()).size());
        });
        probe("H13",()->{
            var f=parse("loop i 1 2\n\nloop i 2 3\n\nquery I\nSELECT ${i}\n----\n1\n\nendloop\n\nendloop\n");
            return Map.of("expanded_sql",SltExpander.INSTANCE.expand(f,"duckdb",Map.of()).get(0).getSql());
        });
        probe("H14",()->outcome(replay("statement ok\nCREATE TABLE t(i INTEGER)\n\nquery I\n"+
            "INSERT INTO t VALUES (7) RETURNING i\n----\n7\n\nquery I\nSELECT count(*) FROM t\n----\n1\n")));
        format();
        probe("H16",()->outcome(replay("test-env FOO bar\n\nquery T\n"+
            "SELECT chr(36)||chr(123)||'FOO'||chr(125)\n----\n${FOO}\n")));
        probe("N05",()->{
            Path directory=Files.createTempDirectory(root,"n05-");Path db=directory.resolve("metadata.db");
            try(Connection c=DriverManager.getConnection("jdbc:duckdb:");Statement s=c.createStatement()) {
                s.execute("LOAD ducklake");
                s.execute("ATTACH 'ducklake:"+db+"' AS lake (DATA_PATH '"+directory.resolve("data")+"',DUCKLAKE_VERSION '1.0')");
                s.execute("CREATE TABLE lake.t(i INTEGER)");s.execute("INSERT INTO lake.t VALUES (1)");s.execute("DETACH lake");
            }
            DucklakeCatalogConfig config=new DucklakeCatalogConfig();config.setCatalogDatabaseUrl("jdbc:duckdb:"+db);
            config.setDataPath(root.toString());config.setMaxCatalogConnections(1);
            try(JdbcDucklakeCatalog c=new JdbcDucklakeCatalog(config)) {
                var result=new LinkedHashMap<String,Object>();
                result.put("versions_only",c.listExpirableSnapshots(null,Set.of(1L)));
                result.put("both_accepted",c.listExpirableSnapshots(Instant.parse("2020-01-01T00:00:00Z"),Set.of(1L)));
                try { result.put("timestamp_only",c.listExpirableSnapshots(Instant.parse("2020-01-01T00:00:00Z"),null)); }
                catch(RuntimeException e) { result.put("timestamp_only_error",e.getMessage()); }
                return result;
            }
        });
        probe("N03",()->{
            Path directory=Files.createTempDirectory(root,"n03-");Path db=directory.resolve("metadata.db");
            try(Connection c=DriverManager.getConnection("jdbc:duckdb:");Statement s=c.createStatement()) {
                s.execute("LOAD ducklake");
                s.execute("ATTACH 'ducklake:"+db+"' AS lake (DATA_PATH '"+directory.resolve("data")+"', DUCKLAKE_VERSION '1.0')");
                s.execute("CREATE TABLE lake.t (s STRUCT(\"a,b\" INTEGER, \"x:y\" INTEGER))");s.execute("DETACH lake");
            }
            DucklakeCatalogConfig config=new DucklakeCatalogConfig();config.setCatalogDatabaseUrl("jdbc:duckdb:"+db);
            config.setDataPath(directory.resolve("data").toString());config.setMaxCatalogConnections(1);
            try(JdbcDucklakeCatalog c=new JdbcDucklakeCatalog(config)) {
                long snapshot=c.getCurrentSnapshotId();long table=c.getTable("main","t",snapshot).tableId();
                return Map.of("legacy_root_type",c.getTableColumns(table,snapshot).get(0).columnType(),
                    "structured_child_names",c.getAllColumnsWithParentage(table,snapshot).stream()
                        .filter(column->column.parentColumn()!=null).map(DucklakeColumn::columnName).toList());
            }
        });
        probe("N19",()->Map.of("boolean_hash",GoldenComparator.INSTANCE.resultHash(query("I",""),rows("SELECT true")),
            "integer_hash",GoldenComparator.INSTANCE.resultHash(query("I",""),rows("SELECT 1"))));
        System.out.println(new ObjectMapper().writeValueAsString(results));
    }
    static void format() {
        probe("H15",()->Map.of("java_double",DuckDbText.class.getMethod("double",double.class).invoke(DuckDbText.INSTANCE,1e23),
            "native_double",rows("SELECT CAST(1e23::DOUBLE AS VARCHAR)"),
            "java_float_min_normal",DuckDbText.class.getMethod("float",float.class).invoke(DuckDbText.INSTANCE,Float.MIN_NORMAL),
            "native_float_min_normal",rows("SELECT CAST(1.1754943508222875e-38::FLOAT AS VARCHAR)")));
    }
}
