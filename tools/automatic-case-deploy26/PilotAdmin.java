import java.sql.*;
import java.time.*;
import java.util.*;
import com.fuquan.audit.procurement.application.TenderRuleCatalog;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

/** Bootstrap only the independent pilot's real DM8 users and existing F10 catalog. */
public final class PilotAdmin {
    static void require(boolean ok,String reason){if(!ok)throw new IllegalStateException(reason);}
    public static void main(String[] args)throws Exception {
        String url=System.getenv("AUDIT_DB_URL");
        require("ISOLATED_AUTOCASE2".equals(System.getenv("AUDIT_CASE_TEST_DB_CONFIRM")),"ISOLATION_CONFIRMATION_REQUIRED");
        require("jdbc:dm://127.0.0.1:15287".equals(url),"ISOLATED_ENDPOINT_REQUIRED");
        Class.forName("dm.jdbc.driver.DmDriver");
        try(var c=DriverManager.getConnection(url,System.getenv("AUDIT_DB_USERNAME"),System.getenv("AUDIT_DB_PASSWORD"))) {
            require("AUTOCASE_APP".equals(c.getSchema()),"ISOLATED_SCHEMA_REQUIRED");
            c.setAutoCommit(false);
            try {
                if(args[0].equals("bootstrap")) {
                    String role="10000000-0000-0000-0000-000000000003";
                    try(var p=c.prepareStatement("SELECT ID FROM SYS_ROLE WHERE ROLE_CODE='AUDITOR'");var r=p.executeQuery()) {
                        if(r.next())require(role.equals(r.getString(1)),"ROLE_ID_DRIFT");
                        else try(var i=c.prepareStatement("INSERT INTO SYS_ROLE(ID,ROLE_CODE,ROLE_NAME,STATUS,CREATED_AT,UPDATED_AT) VALUES(?,'AUDITOR','Auditor','ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)")){i.setString(1,role);i.executeUpdate();}
                    }
                    var encoder=PasswordEncoderFactories.createDelegatingPasswordEncoder();
                    for(String prefix:List.of("PRIMARY","SECONDARY")) {
                        String id=System.getenv("AUDIT_PILOT_"+prefix+"_ID"),name=System.getenv("AUDIT_PILOT_"+prefix+"_USERNAME"),password=System.getenv("AUDIT_PILOT_"+prefix+"_PASSWORD");
                        require(id!=null&&name!=null&&password!=null&&password.length()>=24,"STRONG_BOOTSTRAP_CREDENTIAL_REQUIRED");
                        try(var p=c.prepareStatement("SELECT ID,PASSWORD_HASH FROM SYS_USER WHERE USERNAME=?")) {
                            p.setString(1,name);try(var r=p.executeQuery()) {
                                if(r.next())require(id.equals(r.getString(1))&&encoder.matches(password,r.getString(2)),"EXISTING_ACCOUNT_DRIFT");
                                else {
                                    try(var i=c.prepareStatement("INSERT INTO SYS_USER(ID,USERNAME,PASSWORD_HASH,REAL_NAME,STATUS,FAILED_LOGIN_COUNT,PASSWORD_CHANGED_AT,CREATED_AT,UPDATED_AT,VERSION_NO) VALUES(?,?,?,?,'ACTIVE',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)")) {
                                        i.setString(1,id);i.setString(2,name);i.setString(3,encoder.encode(password));i.setString(4,prefix.equals("PRIMARY")?"采购试点审核员":"隔离权限验证员");i.executeUpdate();
                                    }
                                    try(var i=c.prepareStatement("INSERT INTO SYS_USER_ROLE(USER_ID,ROLE_ID) VALUES(?,?)")){i.setString(1,id);i.setString(2,role);i.executeUpdate();}
                                }
                            }
                        }
                    }
                    int inserted=0;
                    for(var rule:TenderRuleCatalog.defaults(Instant.now())) {
                        try(var p=c.prepareStatement("SELECT ID FROM PROCUREMENT_TENDER_RULE WHERE RULE_CODE=?")) {
                            p.setString(1,rule.ruleCode());try(var r=p.executeQuery()) {
                                if(r.next()){require(rule.id().toString().equals(r.getString(1)),"RULE_ID_DRIFT");continue;}
                            }
                        }
                        try(var p=c.prepareStatement("INSERT INTO PROCUREMENT_TENDER_RULE(ID,RULE_CODE,RULE_NAME,RISK_CATEGORY,MATCH_TYPE,MATCH_CONTENT,RISK_LEVEL,REMEDIATION_SUGGESTION,LEGAL_BASIS,RULE_EXPLANATION,SOURCE,ENABLED,CREATED_TIME,UPDATED_TIME) VALUES(?,?,?,?,?,?,?,?,?,?,?,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)")) {
                            String[] values={rule.id().toString(),rule.ruleCode(),rule.ruleName(),rule.riskCategory(),rule.matchType(),rule.matchContent(),rule.riskLevel(),rule.remediationSuggestion(),rule.legalBasis(),rule.ruleExplanation(),rule.source()};
                            for(int i=0;i<values.length;i++)p.setString(i+1,values[i]);p.executeUpdate();inserted++;
                        }
                    }
                    c.commit();System.out.println("PILOT_AUTH_BOOTSTRAP=PASS users=2 insertedBuiltinF10Rules="+inserted);
                } else if(args[0].equals("counts")) {
                    for(String table:List.of("AUDIT_CASE","AUDIT_CASE_FILE","AUDIT_FACT","AUDIT_QUOTE_LINE","SYS_USER","AUTH_LOGIN_AUDIT","AUDIT_OPERATION_LOG","PROCUREMENT_TENDER_RULE"))
                        try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM "+table)){r.next();System.out.println(table+"="+r.getLong(1));}
                    c.rollback();
                } else throw new IllegalArgumentException("unknown action");
            } catch(Exception error){c.rollback();throw error;}
        }
    }
}
