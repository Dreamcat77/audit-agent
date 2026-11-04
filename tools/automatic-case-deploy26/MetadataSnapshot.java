import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/** Read-only metadata backup. Never reads business rows or executes DDL/DML. */
public final class MetadataSnapshot {
    public static void main(String[] args)throws Exception {
        Class.forName("dm.jdbc.driver.DmDriver");
        try(var c=DriverManager.getConnection(System.getenv("AUDIT_DB_URL"),System.getenv("AUDIT_DB_USERNAME"),System.getenv("AUDIT_DB_PASSWORD"))) {
            c.setReadOnly(true);
            StringBuilder out=new StringBuilder();
            for(String sql:List.of(
                "SELECT TABLE_NAME,COLUMN_NAME,DATA_TYPE,DATA_LENGTH,DATA_PRECISION,DATA_SCALE,NULLABLE FROM USER_TAB_COLUMNS ORDER BY TABLE_NAME,COLUMN_ID",
                "SELECT TABLE_NAME,CONSTRAINT_NAME,CONSTRAINT_TYPE,STATUS,R_OWNER,R_CONSTRAINT_NAME,SEARCH_CONDITION FROM USER_CONSTRAINTS ORDER BY TABLE_NAME,CONSTRAINT_NAME",
                "SELECT TABLE_NAME,INDEX_NAME,COLUMN_NAME,COLUMN_POSITION FROM USER_IND_COLUMNS ORDER BY TABLE_NAME,INDEX_NAME,COLUMN_POSITION")) {
                try(var s=c.createStatement();var r=s.executeQuery(sql)) {
                    s.setQueryTimeout(30);var m=r.getMetaData();
                    while(r.next()) {
                        for(int i=1;i<=m.getColumnCount();i++) {
                            if(i>1)out.append('\t');Object value=r.getObject(i);
                            if(value instanceof Clob clob)value=clob.getSubString(1,(int)clob.length());
                            out.append(Objects.toString(value,"").replace("\n"," ").replace("\r"," ").replace("\t"," "));
                        }
                        out.append('\n');
                    }
                }
            }
            Files.writeString(Path.of(args[0]),out.toString(),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
            System.out.println("SOURCE_METADATA_READ_ONLY=PASS lines="+out.toString().lines().count());
        }
    }
}
