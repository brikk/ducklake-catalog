// Archived review R01 witness. Reorders an identical metadata row set only in its own lake.
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.brikk.ducklake.catalog.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

public class HistoricalStatsTypeProbe {
    static List<List<String>> rows(Statement s,String sql) throws Exception {
        try(ResultSet r=s.executeQuery(sql)) {
            List<List<String>> out=new ArrayList<>();
            while(r.next()) { List<String> row=new ArrayList<>();for(int i=1;i<=r.getMetaData().getColumnCount();i++)row.add(r.getString(i));out.add(row); }
            return out;
        }
    }
    public static void main(String[] args) throws Exception {
        Path dir=Files.createTempDirectory(Path.of(args[0]),"r01-");Path db=dir.resolve("metadata.db");
        Map<String,Object> result=new LinkedHashMap<>();
        try(Connection n=DriverManager.getConnection("jdbc:duckdb:");Statement s=n.createStatement()) {
            s.execute("LOAD ducklake");
            s.execute("ATTACH 'ducklake:"+db+"' AS lake (DATA_PATH '"+dir.resolve("data")+"',DUCKLAKE_VERSION '1.0')");
            s.execute("CREATE TABLE lake.t(x FLOAT)");s.execute("ALTER TABLE lake.t ALTER x TYPE DOUBLE");
            s.execute("CALL ducklake_set_option('lake','data_inlining_row_limit',0)");
            s.execute("INSERT INTO lake.t VALUES (1.00000001)");s.execute("INSERT INTO lake.t VALUES (1.00000002)");
            result.put("before_max",rows(s,"SELECT max(x) FROM lake.t"));
            result.put("before_scan",rows(s,"SELECT x FROM lake.t ORDER BY x"));s.execute("DETACH lake");
        }
        try(Connection n=DriverManager.getConnection("jdbc:duckdb:"+db);Statement s=n.createStatement()) {
            result.put("column_history",rows(s,"SELECT column_id,column_type,begin_snapshot,end_snapshot FROM ducklake_column ORDER BY begin_snapshot"));
            s.execute("CREATE TEMP TABLE reordered_columns AS SELECT * FROM ducklake_column ORDER BY begin_snapshot DESC");
            s.execute("DELETE FROM ducklake_column");s.execute("INSERT INTO ducklake_column SELECT * FROM reordered_columns");
            result.put("physical_type_order",rows(s,"SELECT column_type FROM ducklake_column"));
        }
        DucklakeCatalogConfig config=new DucklakeCatalogConfig();config.setCatalogDatabaseUrl("jdbc:duckdb:"+db);
        config.setDataPath(dir.resolve("data").toString());config.setMaxCatalogConnections(1);
        try(JdbcDucklakeCatalog c=new JdbcDucklakeCatalog(config)) {
            long snapshot=c.getCurrentSnapshotId();long table=c.getTable("main","t",snapshot).tableId();
            result.put("visible_type",c.getTableColumns(table,snapshot).get(0).columnType());c.analyzeTable(table);
        }
        try(Connection n=DriverManager.getConnection("jdbc:duckdb:"+db);Statement s=n.createStatement()) {
            result.put("published_bounds",rows(s,"SELECT min_value,max_value FROM ducklake_table_column_stats"));
        }
        try(Connection n=DriverManager.getConnection("jdbc:duckdb:");Statement s=n.createStatement()) {
            s.execute("LOAD ducklake");s.execute("ATTACH 'ducklake:"+db+"' AS lake (DUCKLAKE_VERSION '1.0')");
            result.put("after_max",rows(s,"SELECT max(x) FROM lake.t"));
            result.put("after_scan",rows(s,"SELECT x FROM lake.t ORDER BY x"));s.execute("DETACH lake");
        }
        System.out.println(new ObjectMapper().writeValueAsString(result));
    }
}
