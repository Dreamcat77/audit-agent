import java.sql.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Synthetic test actor; generated password crosses only an anonymous pipe. */
public final class BudgetBackgroundRunner {
    public static void main(String[] args)throws Exception {
        Class.forName("dm.jdbc.driver.DmDriver");String id=UUID.randomUUID().toString(),name="synthetic_budget_"+id.substring(0,8),password=UUID.randomUUID()+"aA9!";
        try(var c=DriverManager.getConnection(System.getenv("AUDIT_DB_URL"),System.getenv("AUDIT_DB_USERNAME"),System.getenv("AUDIT_DB_PASSWORD"))) {
            c.setAutoCommit(false);
            try {
                try(var p=c.prepareStatement("INSERT INTO SYS_USER(ID,USERNAME,PASSWORD_HASH,REAL_NAME,STATUS,FAILED_LOGIN_COUNT,PASSWORD_CHANGED_AT,CREATED_AT,UPDATED_AT,VERSION_NO) VALUES(?,?,?,'SYNTHETIC BUDGET','ACTIVE',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)")) {
                    p.setString(1,id);p.setString(2,name);p.setString(3,"{bcrypt}"+new BCryptPasswordEncoder().encode(password));p.executeUpdate();
                }
                try(var p=c.prepareStatement("INSERT INTO SYS_USER_ROLE(USER_ID,ROLE_ID) SELECT ?,ID FROM SYS_ROLE WHERE ROLE_CODE='SYSTEM_ADMIN' AND STATUS='ACTIVE'")){p.setString(1,id);if(p.executeUpdate()!=1)throw new IllegalStateException("missing synthetic role");}
                c.commit();
                var command=new ArrayList<>(List.of("/opt/python/venvs/audit-ai/bin/python","-u",args[0]));command.addAll(Arrays.asList(args).subList(1,args.length));
                var proc=new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.INHERIT).redirectError(ProcessBuilder.Redirect.INHERIT).start();
                try(var out=proc.outputWriter()){out.write(new ObjectMapper().writeValueAsString(Map.of("id",id,"username",name,"password",password)));out.newLine();}
                if(proc.waitFor()!=0)throw new IllegalStateException("budget HTTP test failed");
            } finally {
                c.rollback();try(var p=c.prepareStatement("UPDATE SYS_USER SET STATUS='DISABLED',UPDATED_AT=CURRENT_TIMESTAMP,VERSION_NO=VERSION_NO+1 WHERE ID=? AND USERNAME=?")){p.setString(1,id);p.setString(2,name);p.executeUpdate();}c.commit();
                System.out.println("SYNTHETIC_BUDGET_ACTOR_DISABLED_AUDIT_AND_TASKS_RETAINED");
            }
        } catch(SQLException e){System.err.println("BUDGET_HTTP_DB_FAILED code="+e.getErrorCode()+" state="+e.getSQLState());System.exit(1);}
    }
}
