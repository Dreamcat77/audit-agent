import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.regex.*;

/** Necessary invariants for the new case table only. No business DML and no repair of existing tables. */
public final class ContractAnalysisSchema {
    static final String TABLE="CONTRACT_ANALYSIS_CASE";
    static String table=TABLE,item="NONE",stage="INPUT";
    static void need(boolean value,String reason){if(!value)throw new IllegalStateException(reason);}
    static List<List<String>> rows(Connection c,String sql,String...args)throws SQLException {
        try(var p=c.prepareStatement(sql)){p.setQueryTimeout(25);for(int i=0;i<args.length;i++)p.setString(i+1,args[i]);
            try(var r=p.executeQuery()){var out=new ArrayList<List<String>>();while(r.next()){var row=new ArrayList<String>();for(int i=1;i<=r.getMetaData().getColumnCount();i++)row.add(Objects.toString(r.getString(i),""));out.add(row);}return out;}}
    }
    /** Small SQL boolean grammar. Grouping/precedence is preserved; unsupported grammar is rejected. */
    static final class Expression {
        private final List<String> tokens=new ArrayList<>();private int at;
        Expression(String sql){
            need(sql!=null&&sql.length()<=16000,"CHECK_GRAMMAR_UNKNOWN");
            var matcher=Pattern.compile("\\s+|\"[A-Za-z_][A-Za-z_0-9]*\"|'(?:[^']|'')*'|[A-Za-z_][A-Za-z_0-9]*|[0-9]+|<>|!=|<=|>=|[=<>(),]").matcher(sql);
            int end=0;while(matcher.find()){need(matcher.start()==end,"CHECK_GRAMMAR_UNKNOWN");String token=matcher.group();end=matcher.end();
                if(!token.isBlank())tokens.add(token.startsWith("'")?token:token.replace("\"","").toUpperCase(Locale.ROOT));}
            need(end==sql.length()&&tokens.size()<=2000,"CHECK_GRAMMAR_UNKNOWN");
        }
        boolean eat(String value){if(at<tokens.size()&&tokens.get(at).equals(value)){at++;return true;}return false;}
        String take(){need(at<tokens.size(),"CHECK_GRAMMAR_UNKNOWN");return tokens.get(at++);}
        void require(String value){need(eat(value),"CHECK_GRAMMAR_UNKNOWN");}
        String parse(){String value=or();need(at==tokens.size(),"CHECK_GRAMMAR_UNKNOWN");return value;}
        String or(){var terms=new ArrayList<String>();terms.add(and());while(eat("OR"))terms.add(and());return join("OR",terms);}
        String and(){var terms=new ArrayList<String>();terms.add(atom());while(eat("AND"))terms.add(atom());return join("AND",terms);}
        String atom(){
            if(eat("(")){String result=or();require(")");return result;}
            if(eat("NOT")){String inner=atom();if(inner.startsWith("NULL("))return "NOTNULL("+inner.substring(5);if(inner.startsWith("NOTNULL("))return "NULL("+inner.substring(8);return "NOT("+inner+")";}
            String id=take();need(id.matches("[A-Z_][A-Z_0-9]*"),"CHECK_GRAMMAR_UNKNOWN");
            if(eat("IS")){boolean not=eat("NOT");require("NULL");return (not?"NOTNULL(":"NULL(")+id+")";}
            if(eat("IN")){require("(");var values=new ArrayList<String>();do{values.add(literal());}while(eat(","));require(")");Collections.sort(values);return "IN("+id+","+String.join(",",values)+")";}
            String op=take();need(Set.of("=","!=","<>",">=","<=",">","<").contains(op),"CHECK_GRAMMAR_UNKNOWN");
            return id+(op.equals("<>")?"!=":op)+literal();
        }
        String literal(){String value=take();need(value.matches("[0-9]+|'(?:[^']|'')*'"),"CHECK_GRAMMAR_UNKNOWN");return value;}
        static String join(String op,List<String> values){
            if(values.size()==1)return values.get(0);
            var flat=new ArrayList<String>();for(String v:values){if(v.startsWith(op+"["))flat.addAll(splitChildren(v.substring(op.length()+1,v.length()-1)));else flat.add(v);}Collections.sort(flat);
            return op+"["+String.join(";",flat)+"]";
        }
        static List<String> splitChildren(String value){var result=new ArrayList<String>();int depth=0,start=0;boolean quoted=false;
            for(int i=0;i<value.length();i++){char c=value.charAt(i);if(c=='\''){if(quoted&&i+1<value.length()&&value.charAt(i+1)=='\''){i++;continue;}quoted=!quoted;}if(quoted)continue;
                if(c=='['||c=='(')depth++;if(c==']'||c==')')depth--;if(c==';'&&depth==0){result.add(value.substring(start,i));start=i+1;}}
            result.add(value.substring(start));return result;}
    }
    static String canonical(String sql){return new Expression(sql).parse();}
    static Map<String,String> checks(){return Map.of(
        "CK_CONTRACT_ANALYSIS_STATE","STATUS IN ('QUEUED','RUNNING','COMPLETED','PARTIAL','DATA_INSUFFICIENT','FAILED','UNKNOWN')",
        "CK_CONTRACT_ANALYSIS_VERSION","ROW_VERSION >= 0",
        "CK_CONTRACT_ANALYSIS_LEASE","(STATUS = 'RUNNING' AND LEASE_TOKEN IS NOT NULL AND LEASE_UNTIL IS NOT NULL) OR (STATUS <> 'RUNNING' AND LEASE_TOKEN IS NULL AND LEASE_UNTIL IS NULL)");}
    static List<String> sql(Path path)throws Exception{return Arrays.stream(Files.readString(path).replaceAll("(?m)^\\s*--.*$","").split(";")).map(String::strip).filter(s->!s.isEmpty()).toList();}
    static void verify(Connection c,String name)throws Exception {
        table=name;stage="OWNERSHIP";need(name.matches("[A-Z0-9_]+"),"IDENTIFIER_INVALID");
        var objects=rows(c,"SELECT OBJECT_TYPE FROM USER_OBJECTS WHERE OBJECT_NAME=?",name);need(objects.size()==1&&objects.get(0).get(0).equals("TABLE"),"NOT_OWN_TABLE");
        var expected=new LinkedHashMap<String,String>();
        expected.put("ID","VARCHAR:36:N");expected.put("PROJECT_ID","VARCHAR:36:N");expected.put("OWNER_ID","VARCHAR:100:N");expected.put("INPUT_DIGEST","VARCHAR:64:N");
        expected.put("STATUS","VARCHAR:24:N");expected.put("ROW_VERSION","BIGINT:0:N");expected.put("LEASE_TOKEN","VARCHAR:64:Y");expected.put("LEASE_UNTIL","TIMESTAMP:0:Y");
        expected.put("CREATED_AT","TIMESTAMP:0:N");expected.put("DEADLINE","TIMESTAMP:0:N");expected.put("SNAPSHOT_JSON","CLOB:0:N");
        stage="COLUMNS";var actual=rows(c,"SELECT COLUMN_NAME,DATA_TYPE,DATA_LENGTH,NULLABLE,DATA_DEFAULT FROM USER_TAB_COLUMNS WHERE TABLE_NAME=?",name);
        for(var entry:expected.entrySet()){item=entry.getKey();var observed=actual.stream().filter(r->r.get(0).equals(entry.getKey())).findFirst().orElseThrow(()->new IllegalStateException("COLUMN_MISSING"));var spec=entry.getValue().split(":");
            need(observed.get(1).equals(spec[0])||spec[0].equals("VARCHAR")&&observed.get(1).equals("VARCHAR2"),"COLUMN_TYPE_DRIFT");
            if(spec[0].equals("VARCHAR"))need(Integer.parseInt(observed.get(2))>=Integer.parseInt(spec[1]),"COLUMN_WIDTH_DRIFT");need(observed.get(3).equals(spec[2]),"COLUMN_NULLABILITY_DRIFT");
            if(item.equals("ROW_VERSION"))need(observed.get(4).replaceAll("[\\s()]","").equals("0"),"COLUMN_DEFAULT_DRIFT");}
        stage="CONSTRAINTS";
        for(var check:checks().entrySet()){item=check.getKey();var value=rows(c,"SELECT CONSTRAINT_TYPE,STATUS,SEARCH_CONDITION FROM USER_CONSTRAINTS WHERE TABLE_NAME=? AND CONSTRAINT_NAME=?",name,item);
            need(value.size()==1&&value.get(0).get(0).equals("C")&&value.get(0).get(1).equals("ENABLED"),"CONSTRAINT_MISSING_OR_DISABLED");
            need(canonical(check.getValue()).equals(canonical(value.get(0).get(2))),"CHECK_EXPRESSION_DRIFT");}
        for(String kind:List.of("P","U")){item=kind.equals("P")?"PRIMARY_KEY":"PROJECT_OWNER_UNIQUE";var keys=rows(c,"SELECT CONSTRAINT_NAME FROM USER_CONSTRAINTS WHERE TABLE_NAME=? AND CONSTRAINT_TYPE=? AND STATUS='ENABLED'",name,kind);boolean matched=false;
            for(var key:keys){var cols=rows(c,"SELECT COLUMN_NAME FROM USER_CONS_COLUMNS WHERE CONSTRAINT_NAME=? ORDER BY POSITION",key.get(0)).stream().map(r->r.get(0)).toList();if(cols.equals(kind.equals("P")?List.of("ID"):List.of("PROJECT_ID","OWNER_ID")))matched=true;}need(matched,"KEY_COLUMNS_DRIFT");}
    }
    static List<String> plan(Connection c,Path file)throws Exception {
        table=TABLE;item="NONE";stage="OWNERSHIP";var ddl=sql(file);need(ddl.size()==3&&ddl.get(0).startsWith("CREATE TABLE "+TABLE+" ("),"DDL_INVENTORY_INVALID");
        var objects=rows(c,"SELECT OBJECT_TYPE FROM USER_OBJECTS WHERE OBJECT_NAME=?",TABLE);
        if(objects.isEmpty()){need(rows(c,"SELECT OWNER FROM ALL_SYNONYMS WHERE SYNONYM_NAME=? AND OWNER='PUBLIC'",TABLE).isEmpty(),"PUBLIC_SYNONYM_CONFLICT");return ddl;}
        verify(c,TABLE);var missing=new ArrayList<String>();stage="INDEXES";
        for(int i=1;i<ddl.size();i++){var matcher=Pattern.compile("CREATE INDEX ([A-Z0-9_]+) ON "+TABLE+" \\(([^)]+)\\)").matcher(ddl.get(i));need(matcher.matches(),"DDL_INVENTORY_INVALID");item=matcher.group(1);
            var index=rows(c,"SELECT TABLE_NAME,STATUS FROM USER_INDEXES WHERE INDEX_NAME=?",item);
            if(index.isEmpty()){missing.add(ddl.get(i));continue;}need(index.size()==1&&index.get(0).equals(List.of(TABLE,"VALID")),"INDEX_METADATA_DRIFT");
            var cols=rows(c,"SELECT COLUMN_NAME,DESCEND FROM USER_IND_COLUMNS WHERE INDEX_NAME=? ORDER BY COLUMN_POSITION",item);need(cols.stream().map(r->r.get(0)).toList().equals(Arrays.stream(matcher.group(2).split(",")).map(String::trim).toList())&&cols.stream().allMatch(r->r.get(1).equals("ASC")),"INDEX_COLUMN_ORDER_DRIFT");}
        return List.copyOf(missing);
    }
    public static void main(String[] args){try{
        need(args.length==2&&Set.of("check","apply").contains(args[0]),"ARGUMENTS");
        try(var c=DriverManager.getConnection(System.getenv("AUDIT_DB_URL"),System.getenv("AUDIT_DB_USERNAME"),System.getenv("AUDIT_DB_PASSWORD"))){
            need(c.getMetaData().getDatabaseProductName().toUpperCase(Locale.ROOT).contains("DM"),"NOT_DM8");need(!Set.of("SYSDBA","SYSAUDITOR").contains(c.getMetaData().getUserName().toUpperCase(Locale.ROOT)),"BUSINESS_ACCOUNT_REQUIRED");
            var path=Path.of(args[1]);var plan=plan(c,path);System.out.println("CONTRACT_ANALYSIS_PREFLIGHT=PASS plannedStatements="+plan.size());
            if(args[0].equals("apply")){for(int i=0;i<plan.size();i++){System.out.println("CONTRACT_ANALYSIS_DDL_INTENT="+(i+1));try(var s=c.createStatement()){s.setQueryTimeout(30);s.execute(plan.get(i));}System.out.println("CONTRACT_ANALYSIS_DDL_ACK="+(i+1));}need(plan(c,path).isEmpty(),"FINAL_SCHEMA_INCOMPLETE");System.out.println("CONTRACT_ANALYSIS_SCHEMA=PASS businessDml=false");}
        }
    }catch(Exception failure){System.err.println("SCHEMA_BLOCKED table="+table+" constraintOrColumn="+item+" stage="+stage+" reasonCode="+(failure instanceof IllegalStateException?failure.getMessage():"DATABASE_OR_PLATFORM_ERROR")+" dbCode="+(failure instanceof SQLException sql?sql.getErrorCode():0));System.exit(1);}}
}
