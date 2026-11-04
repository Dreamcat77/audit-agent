import java.sql.*;
import java.util.*;

/** Read-only environment/privilege inspection. Connection secrets come only from environment. */
class Dm8EnvironmentProbe {
    public static void main(String[] args) throws Exception {
        Class.forName("dm.jdbc.driver.DmDriver");
        try (Connection c=DriverManager.getConnection(System.getenv("AUDIT_DB_URL"),System.getenv("AUDIT_DB_USERNAME"),System.getenv("AUDIT_DB_PASSWORD"))) {
            c.setReadOnly(true);c.setAutoCommit(false);
            var m=c.getMetaData();
            System.out.println("DB_PRODUCT="+m.getDatabaseProductName());
            System.out.println("DB_VERSION="+m.getDatabaseProductVersion());
            System.out.println("DRIVER_VERSION="+m.getDriverVersion());
            for (String sql:List.of("SELECT * FROM SESSION_PRIVS","SELECT GRANTED_ROLE FROM USER_ROLE_PRIVS",
                    "SELECT PRIVILEGE FROM USER_SYS_PRIVS","SELECT COUNT(*) FROM USER_TABLES",
                    "SELECT * FROM V$VERSION","SELECT STATUS$ FROM V$DATABASE",
                    "SELECT COUNT(*) FROM USER_TRIGGERS","SELECT COUNT(*) FROM USER_VIEWS",
                    "SELECT COUNT(*) FROM USER_PROCEDURES")) {
                try (Statement s=c.createStatement()) {
                    s.setQueryTimeout(10);
                    try (ResultSet r=s.executeQuery(sql)) {
                        System.out.println("QUERY="+sql);
                        while(r.next()) {
                            var row=new ArrayList<String>();
                            for(int i=1;i<=r.getMetaData().getColumnCount();i++)row.add(r.getString(i));
                            System.out.println(String.join(" | ",row));
                        }
                    }
                } catch(SQLException e){System.out.println("QUERY_CODE="+e.getErrorCode()+" QUERY="+sql);}
            }
            c.rollback();
        }
    }
}
