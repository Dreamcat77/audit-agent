import java.sql.*;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Exact data digests under explicit SHARE locks; no DDL or DML on the source. */
class Dm8BackupManifest {
 static String hex(byte[] b){return HexFormat.of().formatHex(b);}
 static String q(String s){return "\""+s.replace("\"","\"\"")+"\"";}
 static void add(MessageDigest d,byte[] b){d.update(java.nio.ByteBuffer.allocate(8).putLong(b.length).array());d.update(b);}
 public static void main(String[] args)throws Exception {
  Class.forName("dm.jdbc.driver.DmDriver");
  try(var c=DriverManager.getConnection(System.getenv("AUDIT_DB_URL"),System.getenv("AUDIT_DB_USERNAME"),System.getenv("AUDIT_DB_PASSWORD"))) {
   c.setAutoCommit(false);
   String schema=System.getenv("AUDIT_MANIFEST_SCHEMA");
   if(schema==null)schema=c.getSchema()==null?System.getenv("AUDIT_DB_USERNAME"):c.getSchema();
   var tables=new ArrayList<String>();
   try(var p=c.prepareStatement("SELECT TABLE_NAME FROM ALL_TABLES WHERE OWNER=? ORDER BY TABLE_NAME")) {
    p.setString(1,schema);try(var r=p.executeQuery()){while(r.next())tables.add(r.getString(1));}
   }
   if(tables.isEmpty())throw new IllegalStateException("empty backup source");
   String extra=System.getenv("AUDIT_EXTRA_TABLE");
   if(extra!=null){if(!extra.matches("[A-Z0-9_]+\\.[A-Z0-9_]+"))throw new IllegalArgumentException();tables.add(extra);Collections.sort(tables);}
   if(args[0].equals("lock")) {
    for(String t:tables)try(var s=c.createStatement()){s.setQueryTimeout(5);s.execute("LOCK TABLE "+qualified(schema,t)+" IN SHARE MODE NOWAIT");}
    System.out.println("LOCKS_ACQUIRED tables="+tables.size());System.out.flush();
   }
   var manifest=new StringBuilder();
   for(String t:tables) {
    var rowHashes=new ArrayList<String>();var columns=new StringBuilder();
    try(var s=c.createStatement()) {
     s.setQueryTimeout(60);
     try(var r=s.executeQuery("SELECT * FROM "+qualified(schema,t))) {
      var m=r.getMetaData();
      for(int i=1;i<=m.getColumnCount();i++)columns.append(m.getColumnName(i)).append(':').append(m.getColumnTypeName(i)).append(':').append(m.getPrecision(i)).append(':').append(m.getScale(i)).append(';');
      while(r.next()) {
       var digest=MessageDigest.getInstance("SHA-256");
       for(int i=1;i<=m.getColumnCount();i++) {
        Object v=r.getObject(i);
        if(v==null){digest.update((byte)0);continue;}digest.update((byte)1);
        if(v instanceof Clob lob)add(digest,lob.getSubString(1,Math.toIntExact(lob.length())).getBytes(StandardCharsets.UTF_8));
        else if(v instanceof Blob lob)add(digest,lob.getBytes(1,Math.toIntExact(lob.length())));
        else if(v instanceof byte[] b)add(digest,b);
        else add(digest,r.getString(i).getBytes(StandardCharsets.UTF_8));
       }
       rowHashes.add(hex(digest.digest()));
      }
     }
    }
    Collections.sort(rowHashes);var d=MessageDigest.getInstance("SHA-256");
    add(d,columns.toString().getBytes(StandardCharsets.UTF_8));for(String h:rowHashes)add(d,h.getBytes(StandardCharsets.UTF_8));
    manifest.append(t).append('\t').append(rowHashes.size()).append('\t').append(hex(d.digest())).append('\n');
   }
   Files.writeString(Path.of(args[1]),manifest);
   System.out.println("MANIFEST_READY tables="+tables.size());System.out.flush();
   if(args[0].equals("lock"))new BufferedReader(new InputStreamReader(System.in)).readLine();
   c.rollback();
  }
 }
 static String qualified(String schema,String table){String[] parts=table.split("\\.");return parts.length==2?q(parts[0])+"."+q(parts[1]):q(schema)+"."+q(table);}
}
