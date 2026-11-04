import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.regex.*;

/** Customer upgrade catalog guard. No business DML, reset or historical retry entry point. */
public final class CustomerContractSchema {
    static String table="NONE",item="NONE",stage="INPUT";
    static void need(boolean ok,String reason){if(!ok)throw new IllegalStateException(reason);}
    static List<Map<String,Object>> rows(Connection c,String sql,Object...args)throws SQLException {
        try(var p=c.prepareStatement(sql)){p.setQueryTimeout(25);for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);
            try(var r=p.executeQuery()){var out=new ArrayList<Map<String,Object>>();var m=r.getMetaData();while(r.next()){var row=new LinkedHashMap<String,Object>();for(int i=1;i<=m.getColumnCount();i++){Object value=r.getObject(i);if(value instanceof Clob clob){long length=clob.length();need(length<=65536,"CATALOG_TEXT_TOO_LONG");value=clob.getSubString(1,(int)length);}row.put(m.getColumnLabel(i).toUpperCase(Locale.ROOT),value);}out.add(row);}return out;}}
    }
    static String text(Object o){return Objects.toString(o,"").trim();}
    static String type(String s){return s.equals("INTEGER")?"INT":s.equals("DEC")||s.equals("NUMERIC")?"DECIMAL":s;}
    static String compact(String s){return s.replaceAll("[\\s\"]","");}
    // Token normalization changes operators only outside SQL literals. Boolean grouping is retained.
    static String expression(String source){
        var out=new StringBuilder();boolean quoted=false;
        for(int i=0;i<source.length();i++){char ch=source.charAt(i);if(ch=='\''){out.append(ch);if(quoted&&i+1<source.length()&&source.charAt(i+1)=='\''){out.append(source.charAt(++i));continue;}quoted=!quoted;continue;}
            if(quoted){out.append(ch);continue;}if(ch=='"')continue;
            if(ch=='<'&&i+1<source.length()&&source.charAt(i+1)=='>'){out.append("!=");i++;}else out.append(Character.toUpperCase(ch));}
        need(!quoted,"UNTERMINATED_CATALOG_LITERAL");
        // Protect literals before the bounded catalog NOT/NULL rewrite.
        var literals=new ArrayList<String>();var token=Pattern.compile("'(?:[^']|'')*'").matcher(out.toString());var protectedText=new StringBuffer();
        while(token.find()){String marker="\u0001"+literals.size()+"\u0002";literals.add(token.group());token.appendReplacement(protectedText,Matcher.quoteReplacement(marker));}token.appendTail(protectedText);
        String v=protectedText.toString().replaceAll("NOT\\s*\\(\\s*([A-Z0-9_]+)\\s+IS\\s+NULL\\s*\\)","$1 IS NOT NULL");
        for(int i=0;i<literals.size();i++)v=v.replace("\u0001"+i+"\u0002",literals.get(i));
        return bool(v.trim());
    }
    static String bool(String value){String v=value.trim();while(outer(v))v=v.substring(1,v.length()-1).trim();
        for(String op:List.of("OR","AND")){var pieces=new ArrayList<String>();int depth=0,start=0;boolean quote=false,between=false;
            for(int i=0;i<v.length();i++){char ch=v.charAt(i);if(ch=='\''){if(quote&&i+1<v.length()&&v.charAt(i+1)=='\''){i++;continue;}quote=!quote;continue;}if(quote)continue;if(ch=='(')depth++;if(ch==')')depth--;
                if(depth==0&&v.startsWith("BETWEEN",i)&&(i==0||!word(v.charAt(i-1)))&&(i+7==v.length()||!word(v.charAt(i+7))))between=true;
                if(depth==0&&v.startsWith(op,i)&&(i==0||!word(v.charAt(i-1)))&&(i+op.length()==v.length()||!word(v.charAt(i+op.length())))){if(op.equals("AND")&&between){between=false;continue;}pieces.add(v.substring(start,i));i+=op.length()-1;start=i+1;}}
            if(!pieces.isEmpty()){pieces.add(v.substring(start));return op+"("+String.join(",",pieces.stream().map(CustomerContractSchema::bool).map(x->x.startsWith(op+"(")?x.substring(op.length()+1,x.length()-1):x).toList())+")";}}
        String scalar=v.replaceAll("(?<=[=<>!])\\s*\\(([A-Z0-9_ .+*/-]*[+*/-][A-Z0-9_ .+*/-]*)\\)(?=\\s*(?:AND|OR|\\)|$))","$1");
        var out=new StringBuilder();boolean quote=false;for(int i=0;i<scalar.length();i++){char ch=scalar.charAt(i);if(ch=='\'')quote=!quote;if(quote||!Character.isWhitespace(ch))out.append(ch);}return out.toString();
    }
    static boolean word(char c){return Character.isLetterOrDigit(c)||c=='_';}
    static boolean outer(String v){if(!v.startsWith("(")||!v.endsWith(")"))return false;int depth=0;boolean q=false;for(int i=0;i<v.length();i++){char ch=v.charAt(i);if(ch=='\'')q=!q;if(q)continue;if(ch=='(')depth++;if(ch==')'&&--depth==0)return i==v.length()-1;}return false;}
    static List<String> names(JsonNode n){var out=new ArrayList<String>();n.forEach(v->out.add(v.asText()));return out;}
    static List<String> columns(Connection c,String constraint)throws SQLException{return rows(c,"SELECT COLUMN_NAME FROM USER_CONS_COLUMNS WHERE CONSTRAINT_NAME=? ORDER BY POSITION",constraint).stream().map(r->text(r.get("COLUMN_NAME"))).toList();}
    static boolean ownTable(Connection c,String name)throws SQLException {
        var objects=rows(c,"SELECT OBJECT_TYPE FROM USER_OBJECTS WHERE OBJECT_NAME=?",name);
        if(objects.isEmpty()){need(rows(c,"SELECT OWNER FROM ALL_SYNONYMS WHERE OWNER='PUBLIC' AND SYNONYM_NAME=?",name).isEmpty(),"PUBLIC_SYNONYM_CONFLICT");return false;}
        need(objects.size()==1&&"TABLE".equals(text(objects.get(0).get("OBJECT_TYPE"))),"NOT_OWN_TABLE");return true;
    }
    static void verify(Connection c,JsonNode spec,boolean baseline)throws Exception {
        table=spec.get("name").asText();stage="COLUMNS";
        need(ownTable(c,table),"REQUIRED_BASELINE_TABLE_MISSING");
        var actual=new HashMap<String,Map<String,Object>>();for(var r:rows(c,"SELECT COLUMN_NAME,DATA_TYPE,DATA_LENGTH,DATA_PRECISION,DATA_SCALE,NULLABLE,DATA_DEFAULT FROM USER_TAB_COLUMNS WHERE TABLE_NAME=?",table))actual.put(text(r.get("COLUMN_NAME")),r);
        if(!baseline)need(actual.size()==spec.get("columns").size(),"COLUMN_COUNT_DRIFT");
        for(var col:spec.get("columns")){item=col.get("name").asText();var r=actual.get(item);need(r!=null,"COLUMN_MISSING");String wanted=col.get("type").asText();
            need(type(text(r.get("DATA_TYPE"))).equals(wanted),"COLUMN_TYPE_DRIFT");
            if(wanted.equals("VARCHAR"))need(Integer.parseInt(text(r.get("DATA_LENGTH")))==col.get("length").asInt(),"COLUMN_WIDTH_DRIFT");
            if(wanted.equals("DECIMAL"))need(Integer.parseInt(text(r.get("DATA_PRECISION")))==col.get("precision").asInt()&&Integer.parseInt(text(r.get("DATA_SCALE")))==col.get("scale").asInt(),"DECIMAL_PRECISION_DRIFT");
            need(text(r.get("NULLABLE")).equals(col.get("nullable").asBoolean()?"Y":"N"),"COLUMN_NULLABILITY_DRIFT");
            String expected=col.path("default").asText("");need(expression(text(r.get("DATA_DEFAULT"))).equals(expression(expected)),"COLUMN_DEFAULT_DRIFT");
        }
        stage="CONSTRAINTS";var resolvedKeys=new HashSet<String>();
        for(var key:spec.get("constraints")){item=key.get("name").asText();
            if(key.path("matchByColumns").asBoolean()){
                need(key.get("kind").asText().equals("P")&&spec.path("boundedCaseGrammar").asBoolean(),"UNNAMED_KEY_SCOPE_INVALID");
                var candidates=rows(c,"SELECT CONSTRAINT_NAME FROM USER_CONSTRAINTS WHERE TABLE_NAME=? AND CONSTRAINT_TYPE='P'",table);
                var matches=new ArrayList<String>();for(var candidate:candidates){String name=text(candidate.get("CONSTRAINT_NAME"));if(columns(c,name).equals(names(key.get("columns"))))matches.add(name);}
                need(matches.size()==1,"PRIMARY_KEY_COLUMN_DRIFT");item=matches.get(0);
            }
            resolvedKeys.add(item);var observed=rows(c,"SELECT CONSTRAINT_TYPE,STATUS,SEARCH_CONDITION,R_OWNER,R_CONSTRAINT_NAME,DELETE_RULE FROM USER_CONSTRAINTS WHERE TABLE_NAME=? AND CONSTRAINT_NAME=?",table,item);need(observed.size()==1,"CONSTRAINT_MISSING");var r=observed.get(0);String kind=key.get("kind").asText();need(kind.equals(text(r.get("CONSTRAINT_TYPE")))&&"ENABLED".equals(text(r.get("STATUS"))),"CONSTRAINT_KIND_OR_STATUS_DRIFT");
            if(kind.equals("C")){String expected=key.get("expression").asText();String observedCondition=text(r.get("SEARCH_CONDITION"));boolean match;
                if(spec.path("boundedCaseGrammar").asBoolean())match=ContractAnalysisSchema.canonical(expected).equals(ContractAnalysisSchema.canonical(observedCondition));
                else if(SemanticBudgetExpression.scoped(table,item))match=SemanticBudgetExpression.equivalent(expected,observedCondition);
                else {String observedExpr=expression(observedCondition);match=observedExpr.equals(expression(expected));
                    if(key.has("oldExpression"))match|=observedExpr.equals(expression(key.get("oldExpression").asText()));}
                need(match,"CHECK_EXPRESSION_DRIFT");}
            else {need(columns(c,item).equals(names(key.get("columns"))),"KEY_COLUMN_ORDER_DRIFT");if(kind.equals("R")){String owner=text(r.get("R_OWNER"));need(owner.equals(c.getSchema())&&"NO ACTION".equals(text(r.get("DELETE_RULE"))),"FOREIGN_OWNER_OR_DELETE_DRIFT");var parent=rows(c,"SELECT TABLE_NAME,STATUS FROM USER_CONSTRAINTS WHERE CONSTRAINT_NAME=?",r.get("R_CONSTRAINT_NAME"));need(parent.size()==1&&text(parent.get(0).get("TABLE_NAME")).equals(key.get("parent").asText())&&text(parent.get(0).get("STATUS")).equals("ENABLED")&&columns(c,text(r.get("R_CONSTRAINT_NAME"))).equals(names(key.get("parentColumns"))),"FOREIGN_TARGET_DRIFT");}}
        }
        stage="INDEXES";for(var index:spec.get("indexes")){item=index.get("name").asText();var r=rows(c,"SELECT TABLE_NAME,UNIQUENESS,STATUS FROM USER_INDEXES WHERE INDEX_NAME=?",item);need(r.size()==1&&table.equals(text(r.get(0).get("TABLE_NAME")))&&"VALID".equals(text(r.get(0).get("STATUS")))&&index.get("uniqueness").asText().equals(text(r.get(0).get("UNIQUENESS"))),"INDEX_METADATA_DRIFT");need(rows(c,"SELECT COLUMN_NAME,DESCEND FROM USER_IND_COLUMNS WHERE INDEX_NAME=? ORDER BY COLUMN_POSITION",item).stream().map(x->text(x.get("COLUMN_NAME"))+":"+text(x.get("DESCEND"))).toList().equals(names(index.get("columns"))),"INDEX_COLUMN_ORDER_DRIFT");}
        if(!baseline){
            var expectedKeys=resolvedKeys;
            var actualKeys=new HashSet<String>();for(var key:rows(c,"SELECT CONSTRAINT_NAME FROM USER_CONSTRAINTS WHERE TABLE_NAME=?",table))actualKeys.add(text(key.get("CONSTRAINT_NAME")));
            need(actualKeys.equals(expectedKeys),"ADDITIVE_CONSTRAINT_SET_DRIFT");
            // DM8 may expose auxiliary FK indexes not explicitly named by the migration.
            for(var index:rows(c,"SELECT STATUS FROM USER_INDEXES WHERE TABLE_NAME=?",table))need("VALID".equals(text(index.get("STATUS"))),"ADDITIVE_INDEX_INVALID");
        }
    }
    static List<String> plan(Connection c,JsonNode manifest,boolean finalOnly)throws Exception {
        var plan=new ArrayList<String>();for(var spec:manifest.get("baseline"))verify(c,spec,true);
        for(var group:manifest.get("groups")){int present=0;for(var spec:group.get("tables")){table=spec.get("name").asText();if(ownTable(c,table))present++;}
            need(present==0||present==group.get("tables").size(),"PARTIAL_ADDITIVE_GROUP");
            if(present==0){need(!finalOnly,"NEW_TABLES_NOT_APPLIED");group.get("statements").forEach(s->plan.add(s.asText()));}
            else for(var spec:group.get("tables"))verify(c,spec,false);
        }
        for(var change:manifest.get("checks")){table=change.get("table").asText();item=change.get("name").asText();stage="CHECK_EXPANSION";
            need(rows(c,"SELECT CONSTRAINT_NAME FROM USER_CONSTRAINTS WHERE CONSTRAINT_NAME=?",item+"_UP10_GUARD").isEmpty(),"PARTIAL_GUARD_REQUIRES_INVESTIGATION");
            var r=rows(c,"SELECT STATUS,SEARCH_CONDITION FROM USER_CONSTRAINTS WHERE TABLE_NAME=? AND CONSTRAINT_NAME=?",table,item);need(r.size()==1&&text(r.get(0).get("STATUS")).equals("ENABLED"),"CHECK_MISSING_OR_DISABLED");String actual=expression(text(r.get(0).get("SEARCH_CONDITION")));String newer=change.get("new").asText();
            if(!actual.equals(expression(newer))){need(actual.equals(expression(change.get("old").asText())),"CHECK_EXPRESSION_DRIFT");need(!finalOnly,"CHECK_EXPANSION_NOT_APPLIED");String guard=item+"_UP10_GUARD";plan.add("ALTER TABLE "+table+" ADD CONSTRAINT "+guard+" CHECK ("+newer+")");plan.add("ALTER TABLE "+table+" DROP CONSTRAINT "+item);plan.add("ALTER TABLE "+table+" ADD CONSTRAINT "+item+" CHECK ("+newer+")");plan.add("ALTER TABLE "+table+" DROP CONSTRAINT "+guard);}
        }
        return plan;
    }
    public static void main(String[] args){try{
        need(args.length==3&&Set.of("check","apply","verify").contains(args[0]),"ARGUMENTS");
        var manifest=new ObjectMapper().readTree(Path.of(args[1]).toFile());boolean apply=args[0].equals("apply");
        Class.forName("dm.jdbc.driver.DmDriver");DriverManager.setLoginTimeout(15);
        try(var c=DriverManager.getConnection(System.getenv("AUDIT_DB_URL"),System.getenv("AUDIT_DB_USERNAME"),System.getenv("AUDIT_DB_PASSWORD"))){need(c.getMetaData().getDatabaseProductName().toUpperCase(Locale.ROOT).contains("DM"),"NOT_DM8");need(!Set.of("SYSDBA","SYSAUDITOR").contains(c.getMetaData().getUserName().toUpperCase(Locale.ROOT)),"ADMIN_ACCOUNT_FORBIDDEN");c.setReadOnly(true);var steps=plan(c,manifest,args[0].equals("verify"));System.out.println("CONTRACT_SCHEMA_PREFLIGHT=PASS plannedStatements="+steps.size());
            if(!apply||steps.isEmpty())return;
            Path record=Path.of(args[2]);need(!Files.exists(record),"EXISTING_JOURNAL_REQUIRES_INVESTIGATION");Files.createDirectory(record);Files.writeString(record.resolve("plan.sql"),String.join(";\n",steps)+";\n",StandardOpenOption.CREATE_NEW);
            c.setReadOnly(false);int ordinal=0;for(String step:steps){need(step.startsWith("CREATE TABLE ")||step.startsWith("CREATE INDEX ")||step.startsWith("ALTER TABLE "),"UNAPPROVED_STATEMENT");Files.writeString(record.resolve("journal.txt"),"INTENT "+(++ordinal)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);try(var s=c.createStatement()){s.setQueryTimeout(60);s.execute(step);}Files.writeString(record.resolve("journal.txt"),"ACK "+ordinal+"\n",StandardOpenOption.APPEND);}
            plan(c,manifest,true);Files.writeString(record.resolve("journal.txt"),"VERIFIED NO_BUSINESS_DML\n",StandardOpenOption.APPEND);System.out.println("CONTRACT_SCHEMA_APPLIED=PASS");}
        }catch(Exception e){SQLException sql=null;for(Throwable t=e;t!=null;t=t.getCause())if(t instanceof SQLException s)sql=s;String reason=e instanceof IllegalStateException&&text(e.getMessage()).matches("[A-Z0-9_]+")?e.getMessage():"DATABASE_OR_PLATFORM_ERROR";System.err.println("SCHEMA_BLOCKED table="+table+" constraintOrColumn="+item+" stage="+stage+" reasonCode="+reason+" dbCode="+(sql==null?0:sql.getErrorCode()));System.exit(1);}}
}
