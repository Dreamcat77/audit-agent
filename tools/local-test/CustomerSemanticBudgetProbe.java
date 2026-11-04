import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.sql.*;
import java.nio.file.*;
import java.util.*;

/** Only isolated UUID tables; never alters real checkpoint tables or inserts business rows. */
public final class CustomerSemanticBudgetProbe {
    static void need(boolean ok,String reason){if(!ok)throw new IllegalStateException(reason);}
    public static void main(String[] args)throws Exception {
        var mapper=new ObjectMapper();JsonNode manifest=mapper.readTree(Path.of(args[0]).toFile());
        ObjectNode group=(ObjectNode)manifest.get("groups").get(1).deepCopy();
        String suffix=UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(Locale.ROOT);
        var mapping=new LinkedHashMap<String,String>();
        int seq=0;for(var table:group.get("tables")){
            mapping.put(table.get("name").asText(),"AVB_"+suffix+"_"+(seq++));
            for(var key:table.get("constraints"))mapping.put(key.get("name").asText(),"AVK_"+suffix+"_"+(seq++));
            for(var index:table.get("indexes"))mapping.put(index.get("name").asText(),"AVI_"+suffix+"_"+(seq++));
        }
        String serialized=mapper.writeValueAsString(group);
        for(var entry:mapping.entrySet())serialized=serialized.replace(entry.getKey(),entry.getValue());
        var isolated=mapper.readTree(serialized);var created=new ArrayList<String>();
        Class.forName("dm.jdbc.driver.DmDriver");DriverManager.setLoginTimeout(15);
        try(var c=DriverManager.getConnection(System.getenv("AUDIT_DB_URL"),System.getenv("AUDIT_DB_USERNAME"),System.getenv("AUDIT_DB_PASSWORD"))){
            for(var spec:isolated.get("tables"))need(CustomerContractSchema.rows(c,"SELECT OBJECT_NAME FROM USER_OBJECTS WHERE OBJECT_NAME=?",spec.get("name").asText()).isEmpty(),"TEMP_COLLISION");
            try {
                for(var statement:isolated.get("statements")){
                    String sql=statement.asText();need(sql.startsWith("CREATE TABLE AVB_")||sql.startsWith("CREATE INDEX AVI_"),"UNEXPECTED_STATEMENT");
                    try(var s=c.createStatement()){s.setQueryTimeout(60);s.execute(sql);}
                    if(sql.startsWith("CREATE TABLE "))created.add(sql.split(" ")[2]);
                }
                for(int i=0;i<group.get("tables").size();i++){
                    var original=group.get("tables").get(i);var spec=isolated.get("tables").get(i);
                    for(var key:original.get("constraints"))if(SemanticBudgetExpression.scoped(original.get("name").asText(),key.get("name").asText())){
                        String table=spec.get("name").asText(),name=mapping.get(key.get("name").asText());
                        var rows=CustomerContractSchema.rows(c,"SELECT SEARCH_CONDITION FROM USER_CONSTRAINTS WHERE TABLE_NAME=? AND CONSTRAINT_NAME=?",table,name);
                        need(rows.size()==1,"CATALOG_MISSING");String actual=CustomerContractSchema.text(rows.get(0).get("SEARCH_CONDITION")),expected=key.get("expression").asText();
                        System.out.println("CATALOG_CHECK="+key.get("name").asText()+" expression="+actual);
                        System.out.println("V10_STRING_MATCH="+CustomerContractSchema.expression(actual).equals(CustomerContractSchema.expression(expected)));
                        need(SemanticBudgetExpression.equivalent(expected,actual),"EQUIVALENT_CATALOG_REJECTED");
                        // Guard verification uses the real canonical table/constraint names, but isolated JDBC rows.
                        for(var changed:List.of(expected.replace("USED_CALLS + RESERVED_CALLS <= CALL_LIMIT","USED_CALLS + RESERVED_CALLS < CALL_LIMIT"),expected+" OR 1=1",expected.replace("FENCE > 0","FENCE >= 0")))
                            if(!changed.equals(expected))need(!SemanticBudgetExpression.equivalent(expected,changed),"DRIFT_ACCEPTED");
                        System.out.println("DM8_ISOLATED_EQUIVALENCE_AND_DRIFT="+key.get("name").asText()+" PASS");
                    }
                }
                for(var table:isolated.get("tables")){
                    String name=table.get("name").asText();
                    var r=CustomerContractSchema.rows(c,"SELECT COUNT(*) CNT FROM "+name);need(((Number)r.get(0).get("CNT")).longValue()==0,"UNEXPECTED_TEMP_ROWS");
                }
            } finally {
                Collections.reverse(created);for(String name:created){need(name.matches("AVB_[A-F0-9]{12}_[0-9]+"),"CLEANUP_SCOPE");try(var s=c.createStatement()){s.setQueryTimeout(60);s.execute("DROP TABLE "+name);}}
            }
            for(var spec:isolated.get("tables"))need(CustomerContractSchema.rows(c,"SELECT OBJECT_NAME FROM USER_OBJECTS WHERE OBJECT_NAME=?",spec.get("name").asText()).isEmpty(),"TEMP_CLEANUP_FAILED");
            System.out.println("DM8_ISOLATED_PROBE=PASS TEMP_TABLES_REMOVED=YES BUSINESS_TABLES_UNCHANGED=YES");
        }
    }
}
