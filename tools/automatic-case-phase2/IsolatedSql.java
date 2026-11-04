import java.sql.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Administrative SQL restricted to the dedicated newly created loopback instance. */
class IsolatedSql {
 public static void main(String[] args)throws Exception {
  String url=System.getenv("AUDIT_DB_URL");
  if(url==null||!url.matches("jdbc:dm://127\\.0\\.0\\.1:15287(?:[/?;].*)?"))throw new IllegalArgumentException("isolated instance required");
  Class.forName("dm.jdbc.driver.DmDriver");
  try(var c=DriverManager.getConnection(url,System.getenv("AUDIT_DB_USERNAME"),System.getenv("AUDIT_DB_PASSWORD"));
      var lines=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8))) {
   for(String line;(line=lines.readLine())!=null;) {
    if(line.isBlank())continue;
    try(var s=c.createStatement()) {
     s.setQueryTimeout(30);
     if(s.execute(line))try(var r=s.getResultSet()) {
      while(r.next()){for(int i=1;i<=r.getMetaData().getColumnCount();i++)System.out.print((i==1?"":" | ")+r.getString(i));System.out.println();}
     } else System.out.println("SQL_OK rows="+s.getUpdateCount());
    }catch(SQLException e){System.out.println("SQL_ERROR code="+e.getErrorCode());System.exit(2);}
   }
  }
 }
}
