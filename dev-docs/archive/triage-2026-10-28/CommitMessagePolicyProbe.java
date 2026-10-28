// Archived N13 witness: compare native and catalog enforcement of require_commit_message.
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.brikk.ducklake.catalog.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

public class CommitMessagePolicyProbe {
    public static void main(String[] args) throws Exception {
        Path dir=Files.createTempDirectory(Path.of(args[0]),"n13-");Path db=dir.resolve("metadata.db");
        Map<String,Object> result=new LinkedHashMap<>();
        try(Connection n=DriverManager.getConnection("jdbc:duckdb:");Statement s=n.createStatement()) {
            s.execute("LOAD ducklake");
            s.execute("ATTACH 'ducklake:"+db+"' AS lake (DATA_PATH '"+dir.resolve("data")+"',DUCKLAKE_VERSION '1.0')");
            s.execute("CALL ducklake_set_option('lake','require_commit_message',true)");
            try { s.execute("CREATE SCHEMA lake.native_without_message");result.put("native_rejected",false); }
            catch(SQLException e) { result.put("native_rejected",true);result.put("native_failure",e.getMessage()); }
            try(Statement detach=n.createStatement()) { detach.execute("DETACH lake"); }
        }
        DucklakeCatalogConfig config=new DucklakeCatalogConfig();config.setCatalogDatabaseUrl("jdbc:duckdb:"+db);
        config.setDataPath(dir.resolve("data").toString());config.setMaxCatalogConnections(1);
        try(JdbcDucklakeCatalog c=new JdbcDucklakeCatalog(config)) {
            try { c.createSchema("jvm_without_message");result.put("jvm_accepted",true); }
            catch(RuntimeException e) { result.put("jvm_accepted",false);result.put("jvm_failure",e.toString()); }
            var changes=c.listSnapshotChanges();
            if(!changes.isEmpty())result.put("latest_commit_message",changes.get(0).commitMessage());
        }
        System.out.println(new ObjectMapper().writeValueAsString(result));
    }
}
