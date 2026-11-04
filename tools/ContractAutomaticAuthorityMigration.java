import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.regex.*;

/** Expands exactly three contract authority checks. No business DML or schema repair. */
public final class ContractAutomaticAuthorityMigration {
    static final String TABLE = "CONTRACT_CANDIDATE";
    static final List<String> KEYS = List.of("CK_CONTRACT_CANDIDATE_AUTHORITY_TUPLE",
            "CK_CONTRACT_CANDIDATE_AUTHORITY_SOURCE", "CK_CONTRACT_CANDIDATE_SOURCE_IDENTITY_TUPLE");
    static String table=TABLE, item="NONE", stage="INPUT";
    static void need(boolean value,String reason) { if(!value) throw new IllegalStateException(reason); }
    static Map<String,String> definitions(Path source)throws Exception {
        var result=new LinkedHashMap<String,String>();
        var pattern=Pattern.compile("ALTER TABLE CONTRACT_CANDIDATE ADD CONSTRAINT ([A-Z_]+) CHECK \\((.*?)\\);",Pattern.DOTALL);
        var matcher=pattern.matcher(Files.readString(source).replaceAll("(?m)^\\s*--[^\\n]*", ""));
        while(matcher.find())need(result.put(matcher.group(1),matcher.group(2).trim())==null,"DUPLICATE_DEFINITION");
        need(result.keySet().equals(new LinkedHashSet<>(KEYS)),"MIGRATION_SCOPE_INVALID");
        for(String value:result.values())need(value.contains("'SYSTEM_AUTO'"),"EXPECTED_AUTO_VALUE_MISSING");
        return result;
    }
    static String old(String value) {
        return value.replace("IN ('HUMAN_CONFIRMATION', 'SYSTEM_AUTO')", "= 'HUMAN_CONFIRMATION'")
                .replace("'HUMAN_CONFIRMATION', 'SYSTEM_AUTO', 'LEGACY_COMPAT'", "'HUMAN_CONFIRMATION', 'LEGACY_COMPAT'");
    }
    static String state(Connection c,String name,String expected)throws Exception {
        item=name;stage="CONSTRAINTS";
        var r=CustomerContractSchema.rows(c,"SELECT CONSTRAINT_TYPE,STATUS,SEARCH_CONDITION FROM USER_CONSTRAINTS WHERE TABLE_NAME=? AND CONSTRAINT_NAME=?",table,name);
        need(r.size()==1,"CONSTRAINT_MISSING");
        need("C".equals(CustomerContractSchema.text(r.get(0).get("CONSTRAINT_TYPE")))
                && "ENABLED".equals(CustomerContractSchema.text(r.get(0).get("STATUS"))),"CONSTRAINT_KIND_OR_STATUS_DRIFT");
        String actual=CustomerContractSchema.expression(CustomerContractSchema.text(r.get(0).get("SEARCH_CONDITION")));
        if(actual.equals(CustomerContractSchema.expression(expected)))return "NEW";
        need(actual.equals(CustomerContractSchema.expression(old(expected))),"CHECK_EXPRESSION_DRIFT");
        return "OLD";
    }
    static List<String> plan(Connection c,Map<String,String> definitions)throws Exception {
        need(CustomerContractSchema.ownTable(c,table),"REQUIRED_TABLE_MISSING");
        var result=new ArrayList<String>();
        for(var entry:definitions.entrySet()) {
            String name=entry.getKey(),guard=name+"_AUTO_GUARD";
            need(CustomerContractSchema.rows(c,"SELECT CONSTRAINT_NAME FROM USER_CONSTRAINTS WHERE TABLE_NAME=? AND CONSTRAINT_NAME=?",table,guard).isEmpty(),"PARTIAL_GUARD_REQUIRES_INVESTIGATION");
            if("OLD".equals(state(c,name,entry.getValue()))) {
                result.add("ALTER TABLE "+table+" ADD CONSTRAINT "+guard+" CHECK ("+entry.getValue()+")");
                result.add("ALTER TABLE "+table+" DROP CONSTRAINT "+name);
                result.add("ALTER TABLE "+table+" ADD CONSTRAINT "+name+" CHECK ("+entry.getValue()+")");
                result.add("ALTER TABLE "+table+" DROP CONSTRAINT "+guard);
            }
        }
        return result;
    }
    static void execute(Connection c,List<String> steps,Path journal)throws Exception {
        if(steps.isEmpty())return;
        need(!Files.exists(journal),"EXISTING_JOURNAL_REQUIRES_INVESTIGATION");
        Files.createDirectory(journal);
        Files.writeString(journal.resolve("plan.sql"),String.join(";\n",steps)+";\n",StandardOpenOption.CREATE_NEW);
        c.setReadOnly(false);int ordinal=0;
        for(String sql:steps) {
            need(sql.startsWith("ALTER TABLE "+table+" "),"UNAPPROVED_STATEMENT");
            Files.writeString(journal.resolve("journal.txt"),"INTENT "+(++ordinal)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
            try(var s=c.createStatement()){s.setQueryTimeout(60);s.execute(sql);}
            Files.writeString(journal.resolve("journal.txt"),"ACK "+ordinal+"\n",StandardOpenOption.APPEND);
        }
    }
    static void isolated(Connection c,Map<String,String> original,Path journal)throws Exception {
        String suffix=UUID.randomUUID().toString().replace("-", "").substring(0,12).toUpperCase(Locale.ROOT);
        table="CAA_"+suffix;var defs=new LinkedHashMap<String,String>();int index=0;
        for(var entry:original.entrySet())defs.put("CAK_"+suffix+"_"+(++index),entry.getValue());
        need(CustomerContractSchema.rows(c,"SELECT OBJECT_NAME FROM USER_OBJECTS WHERE OBJECT_NAME=?",table).isEmpty(),"TEMP_COLLISION");
        boolean created=false;
        try {
            String columns="SOURCE_PARSE_RESULT_ID VARCHAR(36), SOURCE_ROLE_DECISION_ID VARCHAR(36), SOURCE_IDENTITY_DECISION_ID VARCHAR(36), SOURCE_AUTHORITY_SOURCE VARCHAR(32), SOURCE_FACT_FINGERPRINT VARCHAR(64)";
            String checks=String.join(",",defs.entrySet().stream().map(e->"CONSTRAINT "+e.getKey()+" CHECK ("+old(e.getValue())+")").toList());
            try(var s=c.createStatement()){s.execute("CREATE TABLE "+table+" ("+columns+","+checks+")");created=true;}
            need(plan(c,defs).size()==12,"FIRST_PLAN_INVALID");
            execute(c,plan(c,defs),journal);
            need(plan(c,defs).isEmpty(),"REPEAT_NOT_NOOP");
            // Represents DDL success followed by code rollback: a new check connection must
            // recognize the catalog without a migration-history row or repeating any DDL.
            need(plan(c,defs).isEmpty(),"POST_ROLLBACK_CATALOG_REJECTED");
            try(var s=c.createStatement()) {
                s.executeUpdate("INSERT INTO "+table+" VALUES(NULL,NULL,NULL,NULL,NULL)");
                s.executeUpdate("INSERT INTO "+table+" VALUES('parse','role','identity','SYSTEM_AUTO','fingerprint')");
            }
            for(String values:List.of("'parse','role','identity',NULL,'fingerprint'", "'parse',NULL,'identity','SYSTEM_AUTO','fingerprint'", "'parse','role','identity','SYSTEM_AUTO',NULL")) {
                boolean rejected=false;
                try(var s=c.createStatement()){s.executeUpdate("INSERT INTO "+table+" VALUES("+values+")");}
                catch(SQLException expected){rejected=true;}
                need(rejected,"INVALID_AUTHORITY_TUPLE_ACCEPTED");
            }
            // Test real catalog drift, not only a fake comparison string.
            String key=defs.keySet().iterator().next(),expected=defs.get(key);
            try(var s=c.createStatement()) {
                s.execute("ALTER TABLE "+table+" DROP CONSTRAINT "+key);
                s.execute("ALTER TABLE "+table+" ADD CONSTRAINT "+key+" CHECK (("+expected+") OR 1=1)");
            }
            boolean rejected=false;
            try{plan(c,defs);}catch(IllegalStateException drift){rejected="CHECK_EXPRESSION_DRIFT".equals(drift.getMessage());}
            need(rejected,"REAL_DRIFT_ACCEPTED");
            System.out.println("AUTO_AUTHORITY_DM8_FIRST_REPEAT_ROLLBACK_NULL_AND_DRIFT=PASS");
        } finally {
            if(created){need(table.matches("CAA_[A-F0-9]{12}"),"CLEANUP_TARGET_INVALID");try(var s=c.createStatement()){s.execute("DROP TABLE "+table);}}
        }
        need(!CustomerContractSchema.ownTable(c,table),"TEMP_CLEANUP_FAILED");
        System.out.println("AUTO_AUTHORITY_TEMP_TABLE_REMOVED=YES BUSINESS_TABLES_UNCHANGED=YES");
    }
    public static void main(String[] args) {
        try {
            need(args.length==3&&Set.of("check","apply","isolated").contains(args[0]),"ARGUMENTS");
            var definitions=definitions(Path.of(args[1]));
            Class.forName("dm.jdbc.driver.DmDriver");DriverManager.setLoginTimeout(15);
            try(var c=DriverManager.getConnection(System.getenv("AUDIT_DB_URL"),System.getenv("AUDIT_DB_USERNAME"),System.getenv("AUDIT_DB_PASSWORD"))) {
                need(c.getMetaData().getDatabaseProductName().toUpperCase(Locale.ROOT).contains("DM"),"NOT_DM8");
                need(!Set.of("SYSDBA","SYSAUDITOR").contains(c.getMetaData().getUserName().toUpperCase(Locale.ROOT)),"ADMIN_ACCOUNT_FORBIDDEN");
                if(args[0].equals("isolated")){isolated(c,definitions,Path.of(args[2]));return;}
                c.setReadOnly(true);var steps=plan(c,definitions);
                System.out.println("AUTO_AUTHORITY_PREFLIGHT=PASS plannedStatements="+steps.size());
                if(!args[0].equals("apply")||steps.isEmpty())return;
                execute(c,steps,Path.of(args[2]));
                need(plan(c,definitions).isEmpty(),"FINAL_SCHEMA_MISMATCH");
                Files.writeString(Path.of(args[2]).resolve("journal.txt"),"VERIFIED NO_BUSINESS_DML\n",StandardOpenOption.APPEND);
                System.out.println("AUTO_AUTHORITY_SCHEMA=PASS NO_BUSINESS_DML=YES");
            }
        } catch(Exception failure) {
            String reason=failure instanceof IllegalStateException&&Objects.toString(failure.getMessage(),"").matches("[A-Z0-9_]+")?failure.getMessage():"DATABASE_OR_PLATFORM_ERROR";
            System.err.println("SCHEMA_BLOCKED table="+table+" constraintOrColumn="+item+" stage="+stage+" reasonCode="+reason+" dbCode="+(failure instanceof SQLException sql?sql.getErrorCode():0));
            System.exit(1);
        }
    }
}
