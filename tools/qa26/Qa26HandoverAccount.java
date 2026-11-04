import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.util.*;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

/** Handover only the owned disposable QA fixture identity, never a business DB user. */
public final class Qa26HandoverAccount {
    static void need(boolean ok,String reason){if(!ok)throw new IllegalStateException(reason);}
    public static void main(String[] args)throws Exception {
        need("QA26_FULL2".equals(System.getenv("AUDIT_DB_USERNAME"))&&"jdbc:dm://127.0.0.1:15287".equals(System.getenv("AUDIT_DB_URL")),"DISPOSABLE_QA_SCHEMA_REQUIRED");
        var input=new ObjectMapper().readTree(System.in);String id=System.getenv("QA_OWNED_FIXTURE_ID"),old=System.getenv("QA_OWNED_FIXTURE_USERNAME");
        String name=input.path("username").asText(),password=input.path("password").asText();
        need(id!=null&&old!=null&&name.matches("[A-Za-z0-9_]{3,32}")&&password.length()>=8,"HANDOVER_INPUT_INVALID");
        var encoder=PasswordEncoderFactories.createDelegatingPasswordEncoder();
        try(var c=DriverManager.getConnection(System.getenv("AUDIT_DB_URL"),System.getenv("AUDIT_DB_USERNAME"),System.getenv("AUDIT_DB_PASSWORD"))){
            c.setAutoCommit(false);
            try{
                try(var p=c.prepareStatement("SELECT ID FROM SYS_USER WHERE USERNAME=?")){p.setString(1,name);try(var r=p.executeQuery()){need(!r.next()||id.equals(r.getString(1)),"NAME_BELONGS_TO_ANOTHER_ACCOUNT");}}
                String current,hash;long version;
                try(var p=c.prepareStatement("SELECT USERNAME,PASSWORD_HASH,VERSION_NO,STATUS FROM SYS_USER WHERE ID=? FOR UPDATE")){p.setString(1,id);try(var r=p.executeQuery()){need(r.next(),"OWNED_FIXTURE_MISSING");current=r.getString(1);hash=r.getString(2);version=r.getLong(3);need("ACTIVE".equals(r.getString(4))&&!r.next(),"OWNED_FIXTURE_STATE_DRIFT");}}
                need(current.equals(old)||current.equals(name)&&encoder.matches(password,hash),"UNOWNED_FIXTURE_CHANGE");
                if(!current.equals(name)||!encoder.matches(password,hash)){
                    try(var p=c.prepareStatement("UPDATE SYS_USER SET USERNAME=?,PASSWORD_HASH=?,PASSWORD_CHANGED_AT=CURRENT_TIMESTAMP,UPDATED_AT=CURRENT_TIMESTAMP,VERSION_NO=VERSION_NO+1 WHERE ID=? AND VERSION_NO=?")){
                        p.setString(1,name);p.setString(2,encoder.encode(password));p.setString(3,id);p.setLong(4,version);need(p.executeUpdate()==1,"ACCOUNT_CAS_LOST");}
                    try(var p=c.prepareStatement("INSERT INTO AUDIT_OPERATION_LOG(ID,EVENT_TYPE,MODULE,ACTION,RESOURCE_TYPE,RESOURCE_ID,RESULT_STATUS,ACTION_DESC,DETAIL,OCCURRED_AT,CREATED_AT) VALUES(?,'QA_FIXTURE_HANDOVER','SECURITY','QA_FIXTURE_HANDOVER','SYS_USER',?,'SUCCESS','Isolated QA account handover','Owned fixture identity retained; no role changes or credential contents',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)")){
                        p.setString(1,UUID.randomUUID().toString());p.setString(2,id);need(p.executeUpdate()==1,"HANDOVER_AUDIT_FAILED");}
                }
                c.commit();System.out.println("QA26_ACCOUNT_HANDOVER=PASS owner_id_preserved=true roles_unchanged=true audit_persisted=true");
            }catch(Exception e){c.rollback();throw e;}
        }
    }
}
