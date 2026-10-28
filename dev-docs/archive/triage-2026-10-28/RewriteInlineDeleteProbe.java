// Archived N18 witness: materialize, native inline DELETE, then ordinary catalog rewrite.
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.brikk.ducklake.catalog.*;
import java.nio.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

public class RewriteInlineDeleteProbe {
    static List<Integer> ids(Statement s) throws Exception {
        try(ResultSet r=s.executeQuery("SELECT id FROM lake.t ORDER BY id")) {
            List<Integer> out=new ArrayList<>();while(r.next())out.add(r.getInt(1));return out;
        }
    }
    public static void main(String[] args) throws Exception {
        Path dir=Files.createTempDirectory(Path.of(args[0]),"n18-");Path db=dir.resolve("metadata.db");
        Path output=dir.resolve("materialized.parquet");Map<String,Object> result=new LinkedHashMap<>();
        DucklakeCatalogConfig config=new DucklakeCatalogConfig();config.setCatalogDatabaseUrl("jdbc:duckdb:"+db);
        config.setDataPath(dir.resolve("data").toString());config.setMaxCatalogConnections(1);
        try(Connection n=DriverManager.getConnection("jdbc:duckdb:");Statement s=n.createStatement()) {
            s.execute("LOAD ducklake");
            s.execute("ATTACH 'ducklake:"+db+"' AS lake (DATA_PATH '"+dir.resolve("data")+"',DUCKLAKE_VERSION '1.0')");
            s.execute("CREATE TABLE lake.t(id INTEGER)");
            s.execute("CALL ducklake_set_option('lake','data_inlining_row_limit',0)");
            s.execute("INSERT INTO lake.t VALUES (1),(2)");
            s.execute("CALL ducklake_set_option('lake','data_inlining_row_limit',10)");
            s.execute("DETACH lake");
        }
        long planned,table,field;DucklakeDataFile source;
        try(JdbcDucklakeCatalog c=new JdbcDucklakeCatalog(config)) {
            planned=c.getCurrentSnapshotId();table=c.getTable("main","t",planned).tableId();
            source=c.getDataFiles(table,planned).get(0);field=c.getTableColumns(table,planned).get(0).columnId();
        }
        try(Connection n=DriverManager.getConnection("jdbc:duckdb:");Statement s=n.createStatement()) {
                s.execute("LOAD ducklake");
                s.execute("ATTACH 'ducklake:"+db+"' AS lake (DUCKLAKE_VERSION '1.0')");
                s.execute("COPY (SELECT id,rowid AS _ducklake_internal_row_id,"+source.beginSnapshot()+
                    "::BIGINT AS _ducklake_internal_snapshot_id FROM lake.t) TO '"+output+
                    "' (FORMAT PARQUET,FIELD_IDS {id:"+field+",_ducklake_internal_row_id:2147483540,_ducklake_internal_snapshot_id:2147483539})");
                s.execute("DELETE FROM lake.t WHERE id=2");
                result.put("native_rows_after_delete",ids(s));
                s.execute("DETACH lake");
        }
        try(JdbcDucklakeCatalog c=new JdbcDucklakeCatalog(config)) {
                result.put("inlined_deletes",c.getInlinedDeletes(table,c.getCurrentSnapshotId()));
                result.put("delete_file_path",String.valueOf(c.getDataFiles(table,c.getCurrentSnapshotId()).get(0).deleteFilePath()));
                byte[] bytes=Files.readAllBytes(output);
                long footer=ByteBuffer.wrap(bytes,bytes.length-8,4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                DucklakeWriteFragment fragment=new DucklakeWriteFragment(output.toString(),false,"parquet",bytes.length,
                    footer,2L,List.of(),Map.of(),null,null);
                try { c.rewriteDataFiles(table,Set.of(source.dataFileId()),List.of(fragment),planned);result.put("rewrite_accepted",true); }
                catch(RuntimeException failure) { result.put("rewrite_rejection",failure.toString()); }
        }
        try(Connection n=DriverManager.getConnection("jdbc:duckdb:");Statement s=n.createStatement()) {
            s.execute("LOAD ducklake");s.execute("ATTACH 'ducklake:"+db+"' AS lake (DUCKLAKE_VERSION '1.0')");
            result.put("native_rows_after_rewrite",ids(s));
            s.execute("DETACH lake");
        }
        System.out.println(new ObjectMapper().writeValueAsString(result));
    }
}
