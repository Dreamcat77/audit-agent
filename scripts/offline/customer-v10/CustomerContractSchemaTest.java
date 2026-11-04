public final class CustomerContractSchemaTest {
    static Object proxy(Class<?> type,java.lang.reflect.InvocationHandler handler){return java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler);}
    static Object defaultValue(Class<?> type){if(!type.isPrimitive())return null;if(type==boolean.class)return false;if(type==int.class)return 0;if(type==long.class)return 0L;return null;}
    static java.sql.ResultSet catalog(String sql,String name,String condition,String status){
        java.util.List<String> labels;java.util.List<java.util.List<Object>> rows;
        if(sql.contains("USER_OBJECTS")){labels=java.util.List.of("OBJECT_TYPE");rows=java.util.List.of(java.util.List.of("TABLE"));}
        else if(sql.contains("USER_TAB_COLUMNS")||sql.contains("USER_INDEXES")){labels=java.util.List.of("UNUSED");rows=java.util.List.of();}
        else if(sql.contains("CONSTRAINT_TYPE,STATUS")){labels=java.util.List.of("CONSTRAINT_TYPE","STATUS","SEARCH_CONDITION");rows=java.util.List.of(java.util.List.of("C",status,condition));}
        else if(sql.contains("SELECT CONSTRAINT_NAME FROM USER_CONSTRAINTS")){labels=java.util.List.of("CONSTRAINT_NAME");rows=java.util.List.of(java.util.List.of(name));}
        else throw new AssertionError("unhandled catalog query");
        int[] position={-1};var metadata=proxy(java.sql.ResultSetMetaData.class,(p,m,a)->switch(m.getName()){case "getColumnCount"->labels.size();case "getColumnLabel"->labels.get((int)a[0]-1);default->defaultValue(m.getReturnType());});
        return (java.sql.ResultSet)proxy(java.sql.ResultSet.class,(p,m,a)->switch(m.getName()){case "next"->++position[0]<rows.size();case "getMetaData"->metadata;case "getObject"->rows.get(position[0]).get((int)a[0]-1);default->defaultValue(m.getReturnType());});
    }
    static void integration(com.fasterxml.jackson.databind.JsonNode original,com.fasterxml.jackson.databind.JsonNode key,String condition,String status,String reason)throws Exception{
        var fixture=((com.fasterxml.jackson.databind.node.ObjectNode)original).deepCopy();fixture.putArray("columns");fixture.putArray("indexes");fixture.putArray("constraints").add(key);
        var connection=(java.sql.Connection)proxy(java.sql.Connection.class,(p,m,a)->{
            if(m.getName().equals("prepareStatement")){String sql=(String)a[0];return proxy(java.sql.PreparedStatement.class,(q,n,b)->n.getName().equals("executeQuery")?catalog(sql,key.get("name").asText(),condition,status):defaultValue(n.getReturnType()));}
            return defaultValue(m.getReturnType());
        });
        try{CustomerContractSchema.verify(connection,fixture,false);if(reason!=null)throw new AssertionError("verification accepted drift");}
        catch(IllegalStateException e){if(reason==null||!reason.equals(e.getMessage())||!CustomerContractSchema.table.equals(original.get("name").asText())||!CustomerContractSchema.item.equals(key.get("name").asText())||!CustomerContractSchema.stage.equals("CONSTRAINTS"))throw new AssertionError("verification diagnostic mismatch",e);}
    }
    static void same(String a,String b){if(!CustomerContractSchema.expression(a).equals(CustomerContractSchema.expression(b)))throw new AssertionError("equivalent expression rejected");}
    static void different(String a,String b){if(CustomerContractSchema.expression(a).equals(CustomerContractSchema.expression(b)))throw new AssertionError("drift accepted");}
    public static void main(String[] args)throws Exception {
        same("A <> 1 AND B IS NOT NULL","(A != 1) AND (NOT (B IS NULL))");
        same("(A=1 OR B=2) AND C=3","(((A=1) OR (B=2)) AND (C=3))");
        different("A=1 OR B=2 AND C=3","(A=1 OR B=2) AND C=3");
        different("X IN ('A','B')","X IN ('A','B','C')");
        different("X='x'","X='X'");different("X='A B'","X='AB'");
        different("X='A<>B'","X='A!=B'");different("A>0","A>=0");
        different("X='NOT (B IS NULL)'","X='B IS NOT NULL'");
        same("A=1 AND (B=2 AND C=3)","(A=1 AND B=2) AND C=3");
        same("X BETWEEN 1 AND 512 AND Y>=0","(X BETWEEN 1 AND 512) AND (Y>=0)");
        different("X BETWEEN 1 AND 512","X BETWEEN 1 AND 513");
        same("A = B + 1 AND C=0","A = (B + 1) AND C=0");
        different("A = (B + 1) * 2","A = B + 1 * 2");
        if(!SemanticBudgetExpression.scoped("CONTRACT_SEMANTIC_PLAN","CK_SEM_PLAN_BUDGET")||SemanticBudgetExpression.scoped("OTHER","CK_SEM_PLAN_BUDGET"))throw new AssertionError("scope");
        if(args.length==1){
            var manifest=new com.fasterxml.jackson.databind.ObjectMapper().readTree(java.nio.file.Path.of(args[0]).toFile());int checks=0,negative=0;
            for(var group:manifest.get("groups"))for(var table:group.get("tables"))for(var key:table.get("constraints")){
                if(!SemanticBudgetExpression.scoped(table.get("name").asText(),key.get("name").asText()))continue;
                checks++;String expected=key.get("expression").asText();
                String catalog=expected.replace("USED_CALLS + RESERVED_CALLS","(USED_CALLS + RESERVED_CALLS)").replace("USED_REPAIRS + RESERVED_REPAIRS","(USED_REPAIRS + RESERVED_REPAIRS)");
                if(!SemanticBudgetExpression.equivalent(expected,catalog))throw new AssertionError("numeric catalog parentheses");
                if(!SemanticBudgetExpression.equivalent(expected,"(("+catalog.toLowerCase(java.util.Locale.ROOT)+"))"))throw new AssertionError("case/grouping");
                String quoted=catalog.replaceAll("\\b([A-Z_][A-Z0-9_]*)\\b","\"$1\"").replace("\"AND\"","AND");
                if(!SemanticBudgetExpression.equivalent(expected,quoted))throw new AssertionError("quoted uppercase identifier");
                integration(table,key,catalog,"ENABLED",null);
                integration(table,key,catalog,"DISABLED","CONSTRAINT_KIND_OR_STATUS_DRIFT");
                if(expected.contains("USED_CALLS + RESERVED_CALLS")&&CustomerContractSchema.expression(expected).equals(CustomerContractSchema.expression(catalog)))throw new AssertionError("pre-fix regression not reproduced");
                var changes=new java.util.ArrayList<String>(java.util.List.of(expected+" OR 1=1",expected+";",expected.replace("FENCE > 0","FENCE >= 0"),expected.replace("VERSION_NO >= 0","VERSION_NO > 0"),expected.replace("FENCE","OTHER_COLUMN"),expected.replace("FENCE","\"fence\""),expected.replaceFirst("AND","OR"),expected.replaceFirst("AND","+"),expected.replaceFirst("[0-9]","9"),expected+" AND 1=1",expected.replace("FENCE > 0","FENCE > NULL")));
                if(expected.contains("USED_CALLS + RESERVED_CALLS")){changes.add(expected.replace("USED_CALLS + RESERVED_CALLS","USED_CALLS + OTHER_COLUMN"));changes.add(expected.replace("USED_CALLS + RESERVED_CALLS <= CALL_LIMIT","USED_CALLS + RESERVED_CALLS < CALL_LIMIT"));}
                for(String changed:changes){if(changed.equals(expected))throw new AssertionError("bad negative fixture");if(SemanticBudgetExpression.equivalent(expected,changed))throw new AssertionError("numeric drift accepted");integration(table,key,changed,"ENABLED","CHECK_EXPRESSION_DRIFT");negative++;}
                String[] predicates=expected.split("\\s+AND\\s+");for(int i=0;i<predicates.length;i++){var reduced=new java.util.ArrayList<String>(java.util.List.of(predicates));reduced.remove(i);String changed=String.join(" AND ",reduced);if(SemanticBudgetExpression.equivalent(expected,changed))throw new AssertionError("missing predicate accepted");integration(table,key,changed,"ENABLED","CHECK_EXPRESSION_DRIFT");negative++;}
            }
            if(checks!=2||negative<22)throw new AssertionError("coverage");
            System.out.println("SEMANTIC_NUMERIC_CHECK_SCOPE_GROUPING_QUOTES_FAIL_CLOSED=PASS checks="+checks+" negative="+negative);
            System.out.println("CATALOG_VERIFIER_JDBC_FAKE=PASS status_and_drift_diagnostics_checked=true real_dm8=NOT_VERIFIED");
        }
        System.out.println("CUSTOMER_CHECK_EQUIVALENCE_LITERAL_PRECEDENCE_DRIFT=PASS");
    }
}
