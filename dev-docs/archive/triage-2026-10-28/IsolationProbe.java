// Archived review R04 witness: inject isolation-setting failure on a disposable PostgreSQL server.
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.brikk.ducklake.catalog.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;

public class IsolationProbe {
    static Object invoke(Method m,Object target,Object[] args) throws Throwable {
        try { return m.invoke(target,args); } catch(InvocationTargetException e) { throw e.getCause(); }
    }
    static void inject(JdbcDucklakeCatalog c,AtomicInteger faults) throws Exception {
        Field field=JdbcDucklakeCatalog.class.getDeclaredField("dataSource");field.setAccessible(true);
        DataSource original=(DataSource)field.get(c);
        DataSource proxy=(DataSource)Proxy.newProxyInstance(IsolationProbe.class.getClassLoader(),new Class[]{DataSource.class},(o,m,a)->{
            Object result=invoke(m,original,a);
            if(!m.getName().equals("getConnection"))return result;
            Connection connection=(Connection)result;
            return Proxy.newProxyInstance(IsolationProbe.class.getClassLoader(),new Class[]{Connection.class},(co,cm,ca)->{
                if(cm.getName().equals("setTransactionIsolation")) {
                    faults.incrementAndGet();throw new SQLException("injected failure to establish REPEATABLE READ","08006");
                }
                return invoke(cm,connection,ca);
            });
        });
        field.set(c,proxy);
    }
    static DucklakeCatalogConfig config(TestingDucklakePostgreSqlCatalogServer server,Path dir,boolean strict) {
        DucklakeCatalogConfig config=new DucklakeCatalogConfig();config.setCatalogDatabaseUrl(server.getJdbcUrl());
        config.setCatalogDatabaseUser(server.getUser());config.setCatalogDatabasePassword(server.getPassword());
        config.setDataPath(dir.toString());config.setMaxCatalogConnections(2);config.setEnableFinal11Reads(strict);return config;
    }
    public static void main(String[] args) throws Exception {
        Path dir=Files.createTempDirectory(Path.of(args[0]),"r04-");Map<String,Object> result=new LinkedHashMap<>();
        try(TestingDucklakePostgreSqlCatalogServer server=new TestingDucklakePostgreSqlCatalogServer()) {
            try(Connection n=DriverManager.getConnection("jdbc:duckdb:");Statement s=n.createStatement()) {
                s.execute("LOAD ducklake; LOAD postgres");
                s.execute("ATTACH '"+server.getDuckDbAttachUri()+"' AS lake (DATA_PATH '"+dir+"',DUCKLAKE_VERSION '1.0')");
                s.execute("DETACH lake");
            }
            AtomicInteger faults=new AtomicInteger();
            try(JdbcDucklakeCatalog c=new JdbcDucklakeCatalog(config(server,dir,false));
                JdbcDucklakeCatalog competitor=new JdbcDucklakeCatalog(config(server,dir,false))) {
                inject(c,faults);
                long[] seen=c.readSession(()->{
                    long before=c.getCurrentSnapshotId();competitor.createSchema("competing");
                    return new long[]{before,c.getCurrentSnapshotId()};
                });
                result.put("legacy_isolation_faults",faults.get());result.put("legacy_snapshot_before",seen[0]);
                result.put("legacy_snapshot_after",seen[1]);
            }
            try(JdbcDucklakeCatalog c=new JdbcDucklakeCatalog(config(server,dir,true))) {
                inject(c,new AtomicInteger());
                try { c.readSession(()->c.getCurrentSnapshotId());result.put("strict_rejected_fault",false); }
                catch(Exception e) { result.put("strict_rejected_fault",true);result.put("strict_failure",e.getClass().getName()); }
            }
        }
        System.out.println(new ObjectMapper().writeValueAsString(result));
    }
}
