import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.text.Normalizer;
import java.util.*;
import java.nio.file.*;

/** Preserve existing deployment identities; initialize a first installation once. */
public final class ClientIdentityBootstrap {
    public static void main(String[] args)throws Exception {
        if(args.length<1||args.length>2||!Set.of("check","apply").contains(args[0]))throw new IllegalArgumentException("mode");
        var approved=new LinkedHashSet<String>();
        if(args.length==2&&Files.isRegularFile(Path.of(args[1])))for(String line:Files.readAllLines(Path.of(args[1]),StandardCharsets.UTF_8))
            if(!line.isBlank())approved.add(validate(line));
        String aliases=System.getenv("AUDIT_CLIENT_ALIASES");
        if(aliases!=null&&!aliases.isBlank())for(String alias:aliases.split("[;；]"))if(!alias.isBlank())approved.add(validate(alias));
        if(approved.size()>63)throw new IllegalArgumentException("CLIENT_IDENTITY_NAME_COUNT");
        try(var c=DriverManager.getConnection(System.getenv("AUDIT_DB_URL"),System.getenv("AUDIT_DB_USERNAME"),System.getenv("AUDIT_DB_PASSWORD"))) {
            boolean exists;
            try(var s=c.prepareStatement("SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME='AUDIT_CLIENT_IDENTITY'");var r=s.executeQuery()){r.next();exists=r.getInt(1)>0;}
            if(!exists){if(args[0].equals("check")){System.out.println("CLIENT_IDENTITY_PREFLIGHT=PASS state=PLANNED_AFTER_SCHEMA");return;}throw new IllegalStateException("CLIENT_IDENTITY_TABLE_REQUIRED");}
            try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM AUDIT_CLIENT_IDENTITY WHERE ENABLED=1")){
                r.next();exists=r.getInt(1)>0;
            }
            if(exists){
                if(args[0].equals("check")){System.out.println("CLIENT_IDENTITY_PREFLIGHT=PASS approved_alias_count="+approved.size());return;}
                c.setAutoCommit(false);
                try{int added=applyAliases(c,approved);c.commit();System.out.println("CLIENT_IDENTITY=PASS existing_names_preserved=true aliases_added="+added);}
                catch(Exception e){c.rollback();throw e;}
                return;
            }
            if(args[0].equals("check")){System.out.println("CLIENT_IDENTITY_PREFLIGHT=PASS state=INITIAL_NAME_REQUIRED_ON_APPLY");return;}
            String name=System.getenv("AUDIT_CLIENT_FORMAL_NAME");
            if(name==null||name.isBlank()){
                System.out.println("首次配置服务对象，请输入正式单位名称（仅保存到本机数据库，不写日志）：");
                var console=System.console();
                if(console!=null)name=console.readLine();
                else try(var reader=new BufferedReader(new InputStreamReader(new FileInputStream("/dev/tty"),StandardCharsets.UTF_8))){name=reader.readLine();}
            }
            var names=new LinkedHashSet<String>();names.add(validate(name));
            names.addAll(approved);
            if(names.size()>64)throw new IllegalArgumentException("CLIENT_IDENTITY_NAME_COUNT");
            c.setAutoCommit(false);
            try {
                int i=0;
                for(String value:names)try(var p=c.prepareStatement("INSERT INTO AUDIT_CLIENT_IDENTITY(IDENTITY_ID,MATCH_NAME,NAME_KIND,ENABLED) VALUES(?,?,?,1)")){
                    p.setString(1,UUID.randomUUID().toString());p.setString(2,value);p.setString(3,i++==0?"FORMAL":"ALIAS");p.executeUpdate();
                }
                c.commit();System.out.println("CLIENT_IDENTITY=PASS configured_names="+names.size());
            }catch(Exception e){c.rollback();throw e;}
        }catch(Exception e){System.err.println("CLIENT_IDENTITY_SETUP_FAILED type="+e.getClass().getSimpleName());System.exit(1);}
    }
    static int applyAliases(Connection c,Collection<String> approved)throws SQLException{
        var existing=new HashSet<String>();int enabled=0;
        try(var p=c.prepareStatement("SELECT MATCH_NAME,ENABLED FROM AUDIT_CLIENT_IDENTITY");var r=p.executeQuery()){
            while(r.next()){existing.add(validate(r.getString(1)));if(r.getInt(2)==1)enabled++;}
        }
        long missing=approved.stream().filter(n->!existing.contains(n)).count();
        if(enabled+missing>64)throw new IllegalStateException("CLIENT_IDENTITY_NAME_COUNT");
        int added=0;
        for(String name:approved)if(!existing.contains(name))try(var p=c.prepareStatement("INSERT INTO AUDIT_CLIENT_IDENTITY(IDENTITY_ID,MATCH_NAME,NAME_KIND,ENABLED) VALUES(?,?,'ALIAS',1)")){
            p.setString(1,UUID.randomUUID().toString());p.setString(2,name);p.executeUpdate();existing.add(name);added++;
        }
        return added;
    }
    static String validate(String name){
        if(name==null)throw new IllegalArgumentException("CLIENT_IDENTITY_NAME_REQUIRED");
        String value=Normalizer.normalize(name,Normalizer.Form.NFKC).replaceAll("(?U)\\s+","");
        if(value.length()<2||value.length()>120||Set.of("甲方","乙方","我方","客户").contains(value))throw new IllegalArgumentException("CLIENT_IDENTITY_NAME_INVALID");
        return value;
    }
}
