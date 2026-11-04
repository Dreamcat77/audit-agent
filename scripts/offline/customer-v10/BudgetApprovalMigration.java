import java.nio.file.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.regex.*;

/** Add-only repository V016 baseline; never replace existing rules or historical results. */
public final class BudgetApprovalMigration {
    static final Set<String> TABLES=Set.of("BUDGET_APPROVAL_RULE_CONFIG","BUDGET_APPROVAL_VALIDATION");
    static String context="INPUT";
    static final class Drift extends IllegalStateException {
        final String reason;
        Drift(String reason){super("approval baseline drift");this.reason=reason;}
    }
    static void require(boolean ok){require(ok,"METADATA_ASSERTION");}
    static void require(boolean ok,String reason){if(!ok)throw new Drift(reason);}
    static void stage(String value){context=value;}
    static String safeIdentifier(String value){return value!=null&&value.matches("[A-Z0-9_]+")?value:"NONE";}
    static String diagnosticFields(String reason){
        String table="NONE",constraint="NONE",stage=context.startsWith("CONSTRAINT_")?"CHECK_CONSTRAINT":context.startsWith("COLUMN_")?"CHECK_COLUMN":context.startsWith("INDEX_")?"CHECK_INDEX":context.startsWith("TABLE_")?"CHECK_TABLE":context.startsWith("PARENT_")?"CHECK_PARENT":"INPUT_OR_APPLY";
        for(String candidate:TABLES){
            if(context.startsWith("CONSTRAINT_"+candidate+"_")){
                table=candidate;constraint=safeIdentifier(context.substring(("CONSTRAINT_"+candidate+"_").length()));break;
            }
            if(context.equals("TABLE_"+candidate)||context.startsWith("COLUMN_"+candidate+"_")||context.startsWith("INDEX_"+candidate+"_")){table=candidate;break;}
        }
        String reasonCode=reason.split("_expected_|_fingerprint_",2)[0];
        return "table="+table+" constraint="+constraint+" stage="+stage+" reasonCode="+safeIdentifier(reasonCode);
    }
    /** Only the repository cumulative constraint may use this bounded expression grammar.
     * Parentheses and AND/OR precedence are retained; literal contents are never rewritten.
     */
    static boolean equivalentCheck(String table,String name,String expected,String actual){
        if(actual==null)return false;
        if(table.equals("BUDGET_APPROVAL_RULE_CONFIG")&&name.equals("CK_BUDGET_APPROVAL_CUMULATIVE"))
            return CumulativeExpression.matches(expected)&&CumulativeExpression.matches(actual);
        return normalized(expected).equals(normalized(actual));
    }
    static final class CumulativeExpression {
        static final String CANONICAL="OR(GROUP_NE,AND(MIN_NOT_NULL,MAX_NULL))";
        final List<String> tokens=new ArrayList<>();int cursor=0,depth=0;
        CumulativeExpression(String text){
            if(text==null||text.length()>2048)throw new IllegalArgumentException();
            for(int i=0;i<text.length();){
                char ch=text.charAt(i);
                if(Character.isWhitespace(ch)){i++;continue;}
                if(ch=='('||ch==')'){tokens.add(String.valueOf(ch));i++;}
                else if(i+1<text.length()&&(text.startsWith("!=",i)||text.startsWith("<>",i))){tokens.add("NE");i+=2;}
                else if(ch=='\''){
                    String literal="'PROCEDURE_REQUIREMENT'";
                    if(!text.startsWith(literal,i))throw new IllegalArgumentException();
                    tokens.add(literal);i+=literal.length();
                }
                else if(ch=='\"'){
                    int end=text.indexOf('\"',i+1);if(end<0)throw new IllegalArgumentException();
                    String identifier=text.substring(i+1,end);
                    if(!Set.of("RULE_GROUP_CODE","MIN_AMOUNT","MAX_AMOUNT").contains(identifier))throw new IllegalArgumentException();
                    tokens.add(identifier);i=end+1;
                }
                else if(ch>='A'&&ch<='Z'||ch>='a'&&ch<='z'||ch=='_'){
                    int end=i+1;
                    while(end<text.length()&&(Character.isLetterOrDigit(text.charAt(end))||text.charAt(end)=='_'))end++;
                    String token=text.substring(i,end).toUpperCase(Locale.ROOT);
                    if(!Set.of("RULE_GROUP_CODE","MIN_AMOUNT","MAX_AMOUNT","IS","NOT","NULL","AND","OR").contains(token))throw new IllegalArgumentException();
                    tokens.add(token);i=end;
                }else throw new IllegalArgumentException();
                if(tokens.size()>128)throw new IllegalArgumentException();
            }
        }
        boolean take(String token){if(cursor<tokens.size()&&tokens.get(cursor).equals(token)){cursor++;return true;}return false;}
        void expect(String token){if(!take(token))throw new IllegalArgumentException();}
        String expression(){String value=conjunction();while(take("OR"))value="OR("+value+","+conjunction()+")";return value;}
        String conjunction(){String value=atom();while(take("AND"))value="AND("+value+","+atom()+")";return value;}
        String atom(){
            if(++depth>32)throw new IllegalArgumentException();
            try{
                if(take("(")){String value=expression();expect(")");return value;}
                if(take("NOT")){
                    expect("(");String value=expression();expect(")");
                    if(!value.equals("MIN_NULL"))throw new IllegalArgumentException();
                    return "MIN_NOT_NULL";
                }
                if(take("RULE_GROUP_CODE")){expect("NE");expect("'PROCEDURE_REQUIREMENT'");return "GROUP_NE";}
                if(take("MIN_AMOUNT")){expect("IS");boolean negated=take("NOT");expect("NULL");return negated?"MIN_NOT_NULL":"MIN_NULL";}
                if(take("MAX_AMOUNT")){expect("IS");expect("NULL");return "MAX_NULL";}
                throw new IllegalArgumentException();
            }finally{depth--;}
        }
        static boolean matches(String text){
            try{var parser=new CumulativeExpression(text);String tree=parser.expression();return parser.cursor==parser.tokens.size()&&tree.equals(CANONICAL);}
            catch(IllegalArgumentException e){return false;}
        }
    }
    static String fingerprint(String value){
        if(value==null)return "NULL";
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException();}
    }
    // Match numeric zero defaults only; never execute a stored expression or print its text.
    static boolean zeroDefault(String value){
        if(value==null)return false;
        String v=value.strip();
        while(v.startsWith("(")&&v.endsWith(")"))v=v.substring(1,v.length()-1).strip();
        if(!v.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?"))return false;
        try{return new BigDecimal(v).compareTo(BigDecimal.ZERO)==0;}catch(NumberFormatException e){return false;}
    }
    static long scalar(Connection c,String sql)throws SQLException {
        try(var s=c.createStatement();var r=s.executeQuery(sql)){require(r.next());return r.getLong(1);}
    }
    static String normalized(String s){return s.replaceAll("[\\s\\\"()]", "").toUpperCase(Locale.ROOT);}
    static String type(String s){return switch(s){case "INT","INTEGER"->"INTEGER";case "DECIMAL","NUMERIC"->"DECIMAL";default->s;};}
    static Map<String,String> definitions(String sql){
        var result=new LinkedHashMap<String,String>();
        var m=Pattern.compile("(?ms)^CREATE TABLE ([A-Z_]+) \\((.*?)^\\);").matcher(sql);
        while(m.find()){require(TABLES.contains(m.group(1)));result.put(m.group(1),m.group(2));}
        require(result.keySet().equals(TABLES));return result;
    }
    static boolean present(Connection c,String table)throws SQLException {
        stage("TABLE_"+table);
        long owned=scalar(c,"SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME='"+table+"'");
        require(owned==0||owned==1,"OWNED_TABLE_COUNT_INVALID");
        if(owned==0){
            // A view/synonym or inaccessible schema object is not a proven missing owned table.
            require(scalar(c,"SELECT COUNT(*) FROM USER_OBJECTS WHERE OBJECT_NAME='"+table+"'")==0,"SAME_NAME_OBJECT_CONFLICT");
            require(scalar(c,"SELECT COUNT(*) FROM USER_SYNONYMS WHERE SYNONYM_NAME='"+table+"'")==0,"PRIVATE_SYNONYM_CONFLICT");
            require(scalar(c,"SELECT COUNT(*) FROM ALL_SYNONYMS WHERE SYNONYM_NAME='"+table+"' AND OWNER='PUBLIC'")==0,"PUBLIC_SYNONYM_CONFLICT");
        }
        return owned==1;
    }
    static List<String> columns(Connection c,String constraint)throws SQLException {
        var result=new ArrayList<String>();
        try(var s=c.createStatement();var r=s.executeQuery("SELECT COLUMN_NAME FROM USER_CONS_COLUMNS WHERE CONSTRAINT_NAME='"+constraint+"' ORDER BY POSITION")){
            while(r.next())result.add(r.getString(1));
        }
        return result;
    }
    static void verifyTable(Connection c,String table,String definition)throws SQLException {
        var expected=new LinkedHashMap<String,String[]>();
        var fields=Pattern.compile("(?m)^\\s*(\\w+) (VARCHAR|DECIMAL|SMALLINT|INT|BIGINT|TIMESTAMP)(?:\\((\\d+)(?:,(\\d+))?\\))? ([^,\\n]*)(?:,|$)").matcher(definition);
        while(fields.find())expected.put(fields.group(1),new String[]{fields.group(2),fields.group(3),fields.group(4),fields.group(5)});
        require(!expected.isEmpty());var seen=new HashSet<String>();
        try(var s=c.createStatement();var r=s.executeQuery("SELECT COLUMN_NAME,DATA_TYPE,DATA_LENGTH,DATA_PRECISION,DATA_SCALE,NULLABLE,DATA_DEFAULT FROM USER_TAB_COLUMNS WHERE TABLE_NAME='"+table+"'")){
            while(r.next()){
                String name=r.getString(1);String[] e=expected.get(name);
                stage("TABLE_"+table);require(e!=null,"UNEXPECTED_COLUMN");seen.add(name);
                stage("COLUMN_"+table+"_"+name);
                String actual=r.getString(2);require(actual!=null&&(type(actual).equals(type(e[0]))||e[0].equals("TIMESTAMP")&&actual.startsWith("TIMESTAMP")),"COLUMN_TYPE_MISMATCH");
                if(e[0].equals("VARCHAR"))require(r.getInt(3)==Integer.parseInt(e[1]),"VARCHAR_LENGTH_MISMATCH_expected_"+e[1]+"_actual_"+r.getInt(3));
                if(e[0].equals("DECIMAL"))require(r.getInt(4)==Integer.parseInt(e[1])&&r.getInt(5)==Integer.parseInt(e[2]),"DECIMAL_PRECISION_SCALE_MISMATCH_expected_"+e[1]+"_"+e[2]+"_actual_"+r.getInt(4)+"_"+r.getInt(5));
                require(Objects.equals(r.getString(6),e[3].contains("NOT NULL")?"N":"Y"),"COLUMN_NULLABILITY_MISMATCH");
                if(e[3].contains("DEFAULT 0")){
                    String value=r.getString(7);
                    require(zeroDefault(value),"DEFAULT_ZERO_MISMATCH_fingerprint_"+fingerprint(value));
                }
            }
        }
        stage("TABLE_"+table);require(seen.equals(expected.keySet()),"REQUIRED_COLUMN_MISSING");
        var constraints=Pattern.compile("(?s)CONSTRAINT (\\w+) (.*?)(?=,\\s*CONSTRAINT |\\z)").matcher(definition);
        int count=0;
        while(constraints.find()){
            count++;String name=constraints.group(1),body=constraints.group(2).strip();
            stage("CONSTRAINT_"+table+"_"+name);
            String kind=body.startsWith("PRIMARY")?"P":body.startsWith("UNIQUE")?"U":body.startsWith("FOREIGN")?"R":"C";
            try(var s=c.createStatement();var r=s.executeQuery("SELECT CONSTRAINT_TYPE,STATUS,SEARCH_CONDITION,R_CONSTRAINT_NAME FROM USER_CONSTRAINTS WHERE TABLE_NAME='"+table+"' AND CONSTRAINT_NAME='"+name+"'")){
                require(r.next(),"CONSTRAINT_MISSING");
                require(kind.equals(r.getString(1)),"CONSTRAINT_TYPE_MISMATCH");
                require("ENABLED".equals(r.getString(2)),"CONSTRAINT_NOT_ENABLED");
                if(kind.equals("C")){
                    String wanted=body.substring(5), value=r.getString(3);
                    require(equivalentCheck(table,name,wanted,value),"CHECK_EXPRESSION_MISMATCH_expected_"+fingerprint(wanted)+"_actual_"+fingerprint(value));
                }
                else {
                    var match=Pattern.compile("\\(([^)]+)\\)").matcher(body);require(match.find());
                    require(columns(c,name).equals(Arrays.stream(match.group(1).split(",")).map(String::strip).toList()),"CONSTRAINT_COLUMN_ORDER_MISMATCH");
                    if(kind.equals("R")){
                        var ref=Pattern.compile("REFERENCES (\\w+) \\(([^)]+)\\)").matcher(body);require(ref.find());
                        String refName=r.getString(4);require(refName!=null&&refName.matches("[A-Z0-9_$]+"),"FOREIGN_KEY_REFERENCE_METADATA_INVALID");
                        require(scalar(c,"SELECT COUNT(*) FROM USER_CONSTRAINTS WHERE CONSTRAINT_NAME='"+refName+"' AND TABLE_NAME='"+ref.group(1)+"' AND STATUS='ENABLED'")==1,"FOREIGN_KEY_PARENT_MISMATCH");
                        require(columns(c,refName).equals(Arrays.stream(ref.group(2).split(",")).map(String::strip).toList()),"FOREIGN_KEY_PARENT_COLUMN_MISMATCH");
                    }
                }
            }
        }
        require(count>0);
    }
    static void run(Connection c,String mode,String sql)throws Exception {
        stage("INPUT");
        require(Set.of("check","apply","verify").contains(mode));
        sql=sql.replaceAll("(?m)^--.*$","");var defs=definitions(sql);
        var exists=new HashMap<String,Boolean>();var counts=new HashMap<String,Long>();
        for(var table:defs.keySet()){
            boolean found=present(c,table);exists.put(table,found);
            if(found){verifyTable(c,table,defs.get(table));counts.put(table,scalar(c,"SELECT COUNT(*) FROM "+table));}
        }
        for(String parent:List.of("BUDGET_REVIEW_TASK","BUDGET_REVIEW_ITEM","SYS_USER")){
            stage("PARENT_"+parent);
            try(var s=c.createStatement();var r=s.executeQuery("SELECT ID FROM "+parent+" WHERE 1=0")){}
        }
        if(mode.equals("verify"))require(exists.values().stream().allMatch(Boolean::booleanValue));
        var indices=Pattern.compile("(?s)^CREATE INDEX (\\w+)\\s+ON (\\w+) \\(([^)]+)\\)$");
        // Verify all existing index definitions before any writes.
        for(String raw:sql.split(";")){
            var index=indices.matcher(raw.strip());if(!index.matches())continue;
            verifyIndex(c,index.group(1),index.group(2),index.group(3),mode.equals("verify"));
        }
        if(mode.equals("check")){
            System.out.println("BUDGET_APPROVAL_PREFLIGHT=PASS missing_owned_tables="+exists.values().stream().filter(v->!v).count());return;
        }
        if(mode.equals("apply")){
            for(String raw:sql.split(";")){
                String statement=raw.strip();if(statement.isEmpty()||statement.startsWith("INSERT ")||statement.startsWith("COMMENT "))continue;
                var table=Pattern.compile("(?s)^CREATE TABLE (\\w+) .*?").matcher(statement);
                if(table.matches()&&Boolean.TRUE.equals(exists.get(table.group(1))))continue;
                var index=indices.matcher(statement);
                if(index.matches()&&scalar(c,"SELECT COUNT(*) FROM USER_INDEXES WHERE INDEX_NAME='"+index.group(1)+"'")==1)continue;
                require(statement.startsWith("CREATE TABLE ")||index.matches());
                stage("APPLY_SCHEMA");
                try(var s=c.createStatement()){s.execute(statement);}
            }
        }
        for(var table:defs.keySet()){require(present(c,table));verifyTable(c,table,defs.get(table));
            if(counts.containsKey(table))require(counts.get(table)==scalar(c,"SELECT COUNT(*) FROM "+table),"ROW_COUNT_CHANGED");}
        for(String raw:sql.split(";")){var index=indices.matcher(raw.strip());if(index.matches())verifyIndex(c,index.group(1),index.group(2),index.group(3),true);}
        // Repository demo seeds are deliberately NOT applied to customer rule configuration.
        System.out.println("BUDGET_APPROVAL_SCHEMA=PASS existing_rows_preserved=true rule_seed_imported=false");
        if(scalar(c,"SELECT COUNT(*) FROM BUDGET_APPROVAL_RULE_CONFIG")==0)
            System.out.println("BUDGET_APPROVAL_RULES=NOT_CONFIGURED; F04 approval rules require confirmed customer configuration; material parsing is independent");
    }
    static void verifyIndex(Connection c,String name,String table,String cols,boolean mandatory)throws SQLException {
        stage("INDEX_"+table+"_"+name);
        long n=scalar(c,"SELECT COUNT(*) FROM USER_INDEXES WHERE INDEX_NAME='"+name+"'");require(n==0||n==1,"INDEX_COUNT_INVALID");
        if(n==0){require(!mandatory,"INDEX_MISSING");return;}
        require(scalar(c,"SELECT COUNT(*) FROM USER_INDEXES WHERE INDEX_NAME='"+name+"' AND TABLE_NAME='"+table+"'")==1,"INDEX_TABLE_MISMATCH");
        var actual=new ArrayList<String>();
        try(var s=c.createStatement();var r=s.executeQuery("SELECT COLUMN_NAME FROM USER_IND_COLUMNS WHERE INDEX_NAME='"+name+"' ORDER BY COLUMN_POSITION")){while(r.next())actual.add(r.getString(1));}
        require(actual.equals(Arrays.stream(cols.split(",")).map(String::strip).toList()),"INDEX_COLUMN_ORDER_MISMATCH");
    }
    public static void main(String[] args){
        try{
            require(args.length==2);Class.forName("dm.jdbc.driver.DmDriver");DriverManager.setLoginTimeout(10);
            try(var c=DriverManager.getConnection(System.getenv("AUDIT_DB_URL"),System.getenv("AUDIT_DB_USERNAME"),System.getenv("AUDIT_DB_PASSWORD"))){run(c,args[0],Files.readString(Path.of(args[1])));}
        }catch(SQLException e){System.err.println("BUDGET_APPROVAL_SQL_FAILED "+diagnosticFields("SQL_ERROR")+" code="+e.getErrorCode()+" state="+e.getSQLState());System.exit(1);}
        catch(Drift e){System.err.println("BUDGET_APPROVAL_FAILED "+diagnosticFields(e.reason));System.exit(1);}
        catch(Exception e){System.err.println("BUDGET_APPROVAL_FAILED "+diagnosticFields("UNEXPECTED_EXCEPTION")+" type="+e.getClass().getSimpleName());System.exit(1);}
    }
}
