import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.regex.*;

/** Exact catalog contract for the sole approved semantic checkpoint DDL. */
public final class SemanticCheckpointCatalog {
    static final String DDL="db/dm8/CONTRACT__contract_semantic_checkpoints.sql";
    static final List<String> TABLES=List.of("CONTRACT_SEMANTIC_PLAN","CONTRACT_SEMANTIC_UNIT");
    static final String INDEX="IX_SEMANTIC_UNIT_PLAN";
    record Column(String name,String type,boolean nullable,boolean zeroDefault) {}
    record Plan(List<String> statements,Map<String,List<Column>> columns,Map<String,Map<String,String>> constraints) {}
    static void fail(String gate){throw new IllegalStateException(gate);}
    static Plan plan(Path root)throws Exception{return parse(Files.readString(root.resolve(DDL)));}
    static Plan parse(String source){
        String sql=source.replaceAll("(?m)--[^\\r\\n]*","");List<String> statements=Arrays.stream(sql.split(";",-1)).map(String::trim).filter(s->!s.isEmpty()).toList();
        if(statements.size()!=3)fail("SEMANTIC_DDL_REQUIRES_EXACT_THREE_STATEMENTS");
        Map<String,List<Column>> columns=new LinkedHashMap<>();Map<String,Map<String,String>> constraints=new LinkedHashMap<>();
        for(int i=0;i<2;i++){
            String table=TABLES.get(i),ddl=statements.get(i);if(!ddl.startsWith("CREATE TABLE "+table+" (")||!ddl.endsWith(")"))fail("SEMANTIC_DDL_TABLE_ORDER_OR_KIND");
            List<Column> cols=new ArrayList<>();Map<String,String> keys=new TreeMap<>();
            for(String part:ContractLocalMigration.parts(ddl.substring(ddl.indexOf('(')+1,ddl.lastIndexOf(')')))){
                if(part.startsWith("CONSTRAINT ")){Matcher key=Pattern.compile("CONSTRAINT ([A-Z][A-Z0-9_]*) (.+)",Pattern.DOTALL).matcher(part);if(!key.matches()||keys.putIfAbsent(key.group(1),key.group(2))!=null)fail("SEMANTIC_DDL_DUPLICATE_CONSTRAINT");continue;}
                Matcher col=Pattern.compile("([A-Z][A-Z0-9_]*) (VARCHAR\\(\\d+\\)|INTEGER|BIGINT|CLOB|TIMESTAMP)( DEFAULT 0)?( NOT NULL)?").matcher(part);
                if(!col.matches()||cols.stream().anyMatch(c->c.name().equals(col.group(1))))fail("SEMANTIC_DDL_COLUMN_UNSUPPORTED");
                cols.add(new Column(col.group(1),col.group(2),col.group(4)==null,col.group(3)!=null));
            }
            if(cols.isEmpty()||keys.isEmpty())fail("SEMANTIC_DDL_METADATA_EMPTY");columns.put(table,List.copyOf(cols));constraints.put(table,Map.copyOf(keys));
        }
        if(!statements.get(2).matches("CREATE INDEX "+INDEX+" ON CONTRACT_SEMANTIC_UNIT\\(PLAN_ID,UNIT_ORDINAL,EXECUTION_NO\\)"))fail("SEMANTIC_DDL_INDEX_NOT_APPROVED");
        return new Plan(statements,Map.copyOf(columns),Map.copyOf(constraints));
    }
    static boolean typeMatches(String expected,String dictionary,int jdbc,String jdbcType) {
        if(expected.equals("INTEGER"))return Set.of("INT","INTEGER").contains(dictionary)&&jdbc==Types.INTEGER&&Set.of("INT","INTEGER").contains(jdbcType);
        if(expected.equals("BIGINT"))return dictionary.equals("BIGINT")&&jdbc==Types.BIGINT&&jdbcType.equals("BIGINT");
        if(expected.equals("CLOB"))return dictionary.equals("CLOB")&&jdbc==Types.CLOB&&jdbcType.equals("CLOB");
        return ContractLocalMigration.columnTypeMatches(expected,dictionary,jdbc,jdbcType);
    }
    static List<Word97Maintenance.Fact> facts(Connection c)throws Exception{
        String[][] queries={
            {"TABLE","SELECT TABLE_NAME FROM USER_TABLES ORDER BY TABLE_NAME"},
            {"COLUMN","SELECT TABLE_NAME,COLUMN_NAME,DATA_TYPE,DATA_LENGTH,DATA_PRECISION,DATA_SCALE,NULLABLE,DATA_DEFAULT,COLUMN_ID FROM USER_TAB_COLUMNS ORDER BY TABLE_NAME,COLUMN_ID"},
            {"CONSTRAINT","SELECT TABLE_NAME,CONSTRAINT_NAME,CONSTRAINT_TYPE,R_OWNER,R_CONSTRAINT_NAME,DELETE_RULE,STATUS,SEARCH_CONDITION FROM USER_CONSTRAINTS ORDER BY TABLE_NAME,CONSTRAINT_NAME"},
            {"CONSTRAINT_COLUMN","SELECT TABLE_NAME,CONSTRAINT_NAME,COLUMN_NAME,POSITION FROM USER_CONS_COLUMNS ORDER BY TABLE_NAME,CONSTRAINT_NAME,POSITION"},
            {"INDEX","SELECT TABLE_NAME,INDEX_NAME,UNIQUENESS,STATUS,INDEX_TYPE FROM USER_INDEXES ORDER BY TABLE_NAME,INDEX_NAME"},
            {"INDEX_GENERATION","SELECT TABLE_NAME,INDEX_NAME,GENERATED FROM USER_INDEXES WHERE TABLE_NAME IN ('CONTRACT_SEMANTIC_PLAN','CONTRACT_SEMANTIC_UNIT') ORDER BY TABLE_NAME,INDEX_NAME"},
            {"INDEX_COLUMN","SELECT TABLE_NAME,INDEX_NAME,COLUMN_NAME,COLUMN_POSITION,DESCEND FROM USER_IND_COLUMNS ORDER BY TABLE_NAME,INDEX_NAME,COLUMN_POSITION"},
            {"TRIGGER",Word97Maintenance.triggerCatalogSql()}
        };List<Word97Maintenance.Fact> facts=new ArrayList<>();
        for(var query:queries)for(var row:ResetReviewTestData.query(c,query[1])){
            if(query[0].equals("INDEX_GENERATION")&&(row.size()!=3||!TABLES.contains(row.get(0))))fail("SEMANTIC_INDEX_GENERATION_SCOPE_UNKNOWN");
            facts.add(new Word97Maintenance.Fact(query[0],Collections.unmodifiableList(new ArrayList<>(row))));
        }
        return List.copyOf(facts);
    }
    static List<List<String>> rows(List<Word97Maintenance.Fact> facts,String kind,String table){return facts.stream().filter(f->f.kind().equals(kind)&&f.values().get(0).equals(table)).map(Word97Maintenance.Fact::values).toList();}
    static List<String> keyColumns(List<Word97Maintenance.Fact> facts,String table,String key){return rows(facts,"CONSTRAINT_COLUMN",table).stream().filter(r->key.equals(r.get(1))).sorted(Comparator.comparingInt(r->Integer.parseInt(r.get(3)))).map(r->r.get(2)).toList();}
    static List<String> indexColumns(List<Word97Maintenance.Fact> facts,String table,String index){
        var raw=rows(facts,"INDEX_COLUMN",table).stream().filter(r->index.equals(r.get(1))).toList();
        if(raw.stream().anyMatch(r->r.size()!=5||r.get(3)==null||!r.get(3).matches("[1-9][0-9]{0,2}")||!"ASC".equals(r.get(4))))fail("SEMANTIC_INDEX_DIRECTION_OR_POSITION_UNPROVEN");
        var ordered=raw.stream().sorted(Comparator.comparingInt(r->Integer.parseInt(r.get(3)))).toList();
        for(int i=0;i<ordered.size();i++)if(!Integer.toString(i+1).equals(ordered.get(i).get(3)))fail("SEMANTIC_INDEX_POSITION_DRIFT");
        return ordered.stream().map(r->r.get(2)).toList();
    }
    static int prefix(List<Word97Maintenance.Fact> facts,Plan plan,String schema){
        for(String table:TABLES)if(rows(facts,"TABLE",table).isEmpty()&&facts.stream().anyMatch(f->Set.of("INDEX","INDEX_COLUMN","INDEX_GENERATION").contains(f.kind())&&table.equals(f.values().get(0))))fail("SEMANTIC_ORPHAN_INDEX_METADATA");
        boolean first=!rows(facts,"TABLE",TABLES.get(0)).isEmpty(),second=!rows(facts,"TABLE",TABLES.get(1)).isEmpty();
        List<List<String>> index=facts.stream().filter(f->f.kind().equals("INDEX")&&INDEX.equals(f.values().get(1))).map(Word97Maintenance.Fact::values).toList();
        if(!first){if(second||!index.isEmpty())fail("SEMANTIC_SCHEMA_NOT_EXACT_PREFIX");return 0;}
        table(facts,plan,TABLES.get(0),schema);if(!second){if(!index.isEmpty())fail("SEMANTIC_SCHEMA_NOT_EXACT_PREFIX");return 1;}
        table(facts,plan,TABLES.get(1),schema);if(index.isEmpty())return 2;
        if(index.size()!=1||!index.get(0).get(0).equals(TABLES.get(1))||!"NONUNIQUE".equals(index.get(0).get(2))||!"VALID".equals(index.get(0).get(3))
            ||!indexColumns(facts,TABLES.get(1),INDEX).equals(List.of("PLAN_ID","UNIT_ORDINAL","EXECUTION_NO")))fail("SEMANTIC_INDEX_DEFINITION_DRIFT");return 3;
    }
    static void table(List<Word97Maintenance.Fact> facts,Plan plan,String table,String schema){
        if(rows(facts,"TABLE",table).size()!=1||!rows(facts,"TRIGGER",table).isEmpty())fail("SEMANTIC_TABLE_OR_TRIGGER_UNPROVEN");
        var real=rows(facts,"COLUMN",table);var expected=plan.columns().get(table);if(real.size()!=expected.size())fail("SEMANTIC_COLUMN_SET_DRIFT");
        for(int i=0;i<expected.size();i++){
            var col=expected.get(i);var r=real.get(i);String type=col.type();String normalized=type.startsWith("VARCHAR")?"VARCHAR":type;
            int jdbc=switch(normalized){case "VARCHAR"->Types.VARCHAR;case "INTEGER"->Types.INTEGER;case "BIGINT"->Types.BIGINT;case "CLOB"->Types.CLOB;default->Types.TIMESTAMP;};
            if(!col.name().equals(r.get(1))||Integer.parseInt(r.get(8))!=i+1||!typeMatches(type,r.get(2),jdbc,r.get(2))||!Objects.equals(col.nullable()?"Y":"N",r.get(6)))fail("SEMANTIC_COLUMN_TYPE_ORDER_OR_NULL_DRIFT");
            if(type.startsWith("VARCHAR")&&Integer.parseInt(type.substring(8,type.length()-1))!=Integer.parseInt(r.get(3))||type.equals("INTEGER")&&!"4".equals(r.get(3))||type.equals("BIGINT")&&!"8".equals(r.get(3)))fail("SEMANTIC_COLUMN_WIDTH_DRIFT");
            if(col.zeroDefault()?r.get(7)==null||!"0".equals(ContractLocalMigration.canonical(r.get(7))):r.get(7)!=null)fail("SEMANTIC_COLUMN_DEFAULT_DRIFT");
        }
        var keys=rows(facts,"CONSTRAINT",table);var approved=plan.constraints().get(table);if(keys.size()!=approved.size())fail("SEMANTIC_CONSTRAINT_SET_DRIFT");
        for(var key:keys){String definition=approved.get(key.get(1));if(definition==null||!"ENABLED".equals(key.get(6)))fail("SEMANTIC_DISABLED_OR_UNAPPROVED_CONSTRAINT");
            String kind=definition.startsWith("PRIMARY KEY")?"P":definition.startsWith("UNIQUE")?"U":definition.startsWith("FOREIGN KEY")?"R":"C";if(!kind.equals(key.get(2)))fail("SEMANTIC_CONSTRAINT_KIND_DRIFT");
            if(kind.equals("C")){if(!condition(definition.substring(5)).equals(condition(key.get(7))))fail("SEMANTIC_CHECK_DEFINITION_DRIFT");}
            else {if(!keyColumns(facts,table,key.get(1)).equals(ContractLocalMigration.columns(definition.substring(definition.indexOf('(')+1,definition.indexOf(')')))))fail("SEMANTIC_KEY_COLUMNS_DRIFT");
                if(kind.equals("R")){Matcher ref=Pattern.compile(".*REFERENCES ([A-Z0-9_]+)\\(([^)]+)\\)",Pattern.DOTALL).matcher(definition);
                    var parents=facts.stream().filter(f->f.kind().equals("CONSTRAINT")&&Objects.equals(f.values().get(1),key.get(4))).map(Word97Maintenance.Fact::values).toList();
                    if(!ref.matches()||!schema.equals(key.get(3))||!"NO ACTION".equals(key.get(5))||parents.size()!=1||!Set.of("P","U").contains(parents.get(0).get(2))||!ref.group(1).equals(parents.get(0).get(0))||!"ENABLED".equals(parents.get(0).get(6))
                        ||!keyColumns(facts,parents.get(0).get(0),key.get(4)).equals(ContractLocalMigration.columns(ref.group(2))))fail("SEMANTIC_FOREIGN_KEY_TARGET_DRIFT");
                }
            }
        }
        indexes(facts,table,keys);
    }
    /** Only these newly created ordinary DM8 tables: one default ROWID cluster,
     * one generated NORMAL per P/U and one generated VIRTUAL per own FK columns.
     * Never infer an FK's own index from USER_CONSTRAINTS.INDEX_NAME (the parent).
     * Existing tables' INDEX facts and protected projection are unchanged. */
    static void indexes(List<Word97Maintenance.Fact> facts,String table,List<List<String>> keys){
        if(!TABLES.contains(table))fail("SEMANTIC_INDEX_SCOPE_UNKNOWN");
        var indexes=rows(facts,"INDEX",table);var generations=rows(facts,"INDEX_GENERATION",table);
        Map<String,String> generated=new HashMap<>();Set<String> names=new HashSet<>();
        for(var row:generations)if(row.size()!=3||row.get(1)==null||generated.containsKey(row.get(1)))fail("SEMANTIC_INDEX_GENERATION_AMBIGUOUS");
            else generated.put(row.get(1),row.get(2));
        for(var row:indexes)if(row.size()!=5||row.get(1)==null||!names.add(row.get(1)))fail("SEMANTIC_DUPLICATE_OR_UNKNOWN_INDEX");
        if(!names.equals(generated.keySet())||rows(facts,"INDEX_COLUMN",table).stream().anyMatch(row->!names.contains(row.get(1))))fail("SEMANTIC_INDEX_METADATA_NOT_ONE_TO_ONE");
        Map<List<String>,Integer> unique=new HashMap<>(),foreign=new HashMap<>();
        for(var key:keys){
            if(Set.of("P","U").contains(key.get(2)))unique.merge(keyColumns(facts,table,key.get(1)),1,Integer::sum);
            if("R".equals(key.get(2)))foreign.merge(keyColumns(facts,table,key.get(1)),1,Integer::sum);
        }
        int clusters=0;
        for(var index:indexes){
            String name=index.get(1),kind=index.get(4);var columns=indexColumns(facts,table,name);
            if(!"VALID".equals(index.get(3)))fail("SEMANTIC_INDEX_NOT_VALID");
            if(INDEX.equals(name)){
                if(!TABLES.get(1).equals(table)||!"N".equals(generated.get(name))||!"NORMAL".equals(kind)||!"NONUNIQUE".equals(index.get(2))
                    ||!columns.equals(List.of("PLAN_ID","UNIT_ORDINAL","EXECUTION_NO")))fail("SEMANTIC_EXPLICIT_INDEX_DRIFT");
                continue;
            }
            if(!"Y".equals(generated.get(name)))fail("SEMANTIC_GENERATED_INDEX_UNPROVEN");
            if("CLUSTER".equals(kind)){
                if(!"NONUNIQUE".equals(index.get(2))||!columns.isEmpty()||++clusters!=1)fail("SEMANTIC_ROWID_CLUSTER_DRIFT");
            }else{
                Map<List<String>,Integer> required;
                if("NORMAL".equals(kind)&&"UNIQUE".equals(index.get(2)))required=unique;
                else if("VIRTUAL".equals(kind)&&"NONUNIQUE".equals(index.get(2)))required=foreign;
                else {fail("SEMANTIC_UNAPPROVED_INDEX_KIND");return;}
                int remaining=required.getOrDefault(columns,0);
                if(remaining<=0)fail("SEMANTIC_INDEX_NOT_EXACT_CONSTRAINT_COLUMNS");required.put(columns,remaining-1);
            }
        }
        if(clusters!=1||unique.values().stream().anyMatch(n->n!=0)||foreign.values().stream().anyMatch(n->n!=0))fail("SEMANTIC_REQUIRED_GENERATED_INDEX_MISSING");
    }
    /** Narrow equivalent of the application preflight grammar, not generic SQL
     * normalization. String literal spelling is data; quoted identifiers must
     * name the exact uppercase columns in the sole approved DDL. */
    static String condition(String source){
        try{return new Check(source).parse();}
        catch(IllegalArgumentException unreadable){throw new IllegalStateException("SEMANTIC_CHECK_GRAMMAR_UNPROVEN");}
    }
    private static final class Check {
        final List<String> tokens=new ArrayList<>();int position,depth;
        Check(String source){
            if(source==null||source.length()>32768)throw new IllegalArgumentException();
            Matcher matcher=Pattern.compile("'(?:''|[^'])*'|\"[A-Za-z_][A-Za-z0-9_]*\"|[A-Za-z_][A-Za-z0-9_]*|[0-9]+|<>|!=|<=|>=|[=<>+(),]").matcher(source);
            int end=0;
            while(matcher.find()){
                if(!source.substring(end,matcher.start()).isBlank())throw new IllegalArgumentException();
                String token=matcher.group();
                if(token.startsWith("\"")){
                    token=token.substring(1,token.length()-1);
                    if(!token.matches("[A-Z_][A-Z0-9_]*"))throw new IllegalArgumentException();
                }
                tokens.add(token.startsWith("'")?token:token.equals("!=")?"<>":token.toUpperCase(Locale.ROOT));end=matcher.end();
            }
            if(!source.substring(end).isBlank())throw new IllegalArgumentException();
        }
        String parse(){String value=or();if(position!=tokens.size())throw new IllegalArgumentException();return value;}
        String or(){List<String> items=new ArrayList<>();items.add(and());while(eat("OR"))items.add(and());return join("OR",items);}
        String and(){List<String> items=new ArrayList<>();items.add(compare());while(eat("AND"))items.add(compare());return join("AND",items);}
        String compare(){
            if(eat("NOT")){
                if(++depth>128)throw new IllegalArgumentException();
                require("(");String inner=or();require(")");depth--;
                // DM8 displays IS NOT NULL as NOT (identifier IS NULL).
                // Do not admit arbitrary unary NOT or rewrite compound logic.
                if(!inner.matches("NULL\\([A-Z_][A-Z0-9_]*\\)"))throw new IllegalArgumentException();
                return "NOT"+inner;
            }
            String left=add();
            if(eat("IS")){boolean not=eat("NOT");require("NULL");return (not?"NOTNULL(":"NULL(")+left+")";}
            if(eat("IN")){require("(");List<String> values=new ArrayList<>();values.add(add());while(eat(","))values.add(add());require(")");return "IN("+left+","+String.join(",",values)+")";}
            if(position<tokens.size()&&Set.of("=","<>","<",">","<=",">=").contains(tokens.get(position))){String op=tokens.get(position++);return op+"("+left+","+add()+")";}
            return left;
        }
        String add(){String value=primary();while(eat("+"))value="+("+value+","+primary()+")";return value;}
        String primary(){
            if(eat("(")){if(++depth>128)throw new IllegalArgumentException();String value=or();require(")");depth--;return value;}
            if(position>=tokens.size())throw new IllegalArgumentException();String token=tokens.get(position++);
            if(!token.startsWith("'")&&!token.matches("[A-Z_][A-Z0-9_]*|[0-9]+"))throw new IllegalArgumentException();return token;
        }
        boolean eat(String token){if(position<tokens.size()&&tokens.get(position).equals(token)){position++;return true;}return false;}
        void require(String token){if(!eat(token))throw new IllegalArgumentException();}
        static String join(String op,List<String> items){
            if(items.size()==1)return items.get(0);
            return op+"("+String.join(",",items.stream().map(item->item.startsWith(op+"(")?item.substring(op.length()+1,item.length()-1):item).toList())+")";
        }
    }
    static void verify(Connection c,Path root)throws Exception{
        var facts=facts(c);var plan=plan(root);int stage=prefix(facts,plan,c.getSchema());if(stage!=3)fail("SEMANTIC_REQUIRED_TWO_TABLE_CATALOG_RECEIPTS_MISSING");
        for(String table:TABLES)try(var s=c.createStatement();var r=s.executeQuery("SELECT * FROM "+ResetReviewTestData.quote(table)+" WHERE 1=0")){
            var m=r.getMetaData();var columns=plan.columns().get(table);var dictionary=rows(facts,"COLUMN",table);
            if(m.getColumnCount()!=columns.size())fail("SEMANTIC_JDBC_COLUMN_SET_DRIFT");
            for(int i=1;i<=columns.size();i++){
                if(!columns.get(i-1).name().equals(m.getColumnName(i))||!typeMatches(columns.get(i-1).type(),dictionary.get(i-1).get(2),m.getColumnType(i),m.getColumnTypeName(i)))fail("SEMANTIC_JDBC_COLUMN_TYPE_DRIFT");
                Word97Maintenance.nullability(m.isNullable(i),dictionary.get(i-1).get(6),columns.get(i-1).nullable());
            }
        }
        for(String table:TABLES){
            List<List<Object>> observed=facts.stream().filter(f->f.values().get(0).equals(table)).map(f->{List<Object> row=new ArrayList<>();row.add(f.kind());row.addAll(f.values());return row;}).toList();
            System.out.println("SEMANTIC_CATALOG="+table+" EXACT_COLUMNS_KEYS_CHECKS_VERIFIED COLUMNS="+rows(facts,"COLUMN",table).size()
                    +" CONSTRAINTS="+rows(facts,"CONSTRAINT",table).size()+" CATALOG_SHA256="+Word97Maintenance.hash(ResetReviewTestData.fingerprint(observed)));
        }
        System.out.println("SEMANTIC_CHECKPOINT_SCHEMA=VERIFIED_TWO_TABLES_AND_INDEX");
    }
}
