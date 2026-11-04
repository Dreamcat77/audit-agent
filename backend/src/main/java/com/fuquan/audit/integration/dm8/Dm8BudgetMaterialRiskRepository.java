package com.fuquan.audit.integration.dm8;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.budget.application.BudgetMaterialRisk.Result;
import com.fuquan.audit.budget.application.BudgetMaterialRisk.Source;
import com.fuquan.audit.budget.application.BudgetMaterialRisk.Position;
import com.fuquan.audit.budget.application.port.BudgetMaterialRiskRepository;
import java.sql.*;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.support.SqlLobValue;
import org.springframework.stereotype.Repository;
@Repository @Profile({"dev29","customer","local-dev"})
public class Dm8BudgetMaterialRiskRepository implements BudgetMaterialRiskRepository {
    private final JdbcTemplate jdbc;private final ObjectMapper json;
    public Dm8BudgetMaterialRiskRepository(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    private Result read(ResultSet r,int n)throws SQLException {try{return json.readValue(r.getString("RESULT_JSON"),Result.class);}catch(Exception e){throw new SQLException("budget risk snapshot invalid",e);}}
    public Optional<Result> find(UUID id,String actor){return jdbc.query("SELECT RESULT_JSON FROM BUDGET_MATERIAL_RISK_RESULT WHERE ID=? AND ACTOR_ID=?",this::read,id.toString(),actor).stream().findFirst();}
    public void insert(Result r){
        try{jdbc.update("INSERT INTO BUDGET_MATERIAL_RISK_RESULT(ID,ACTOR_ID,CREATED_AT,RESULT_JSON) VALUES(?,?,?,?)",new Object[]{r.id().toString(),r.actorId(),Timestamp.from(java.time.Instant.parse(r.createdAt())),new SqlLobValue(json.writeValueAsString(r))},new int[]{Types.VARCHAR,Types.VARCHAR,Types.TIMESTAMP,Types.CLOB});}
        catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("budget risk serialization failed",e);}
        catch(org.springframework.dao.DataAccessException e){
            // This insert has exactly one unique constraint (the result ID). DM8's
            // -6602 is not consistently classified as DuplicateKeyException by Spring.
            for(Throwable cause=e;cause!=null;cause=cause.getCause())if(cause instanceof SQLException sql&&sql.getErrorCode()==-6602)
                throw new org.springframework.dao.DuplicateKeyException("budget risk operation exists",e);
            throw e;
        }
    }
    public List<Result> history(String actor,int offset,int limit){return jdbc.query("SELECT RESULT_JSON FROM (SELECT RESULT_JSON,ROW_NUMBER() OVER(ORDER BY CREATED_AT DESC,ID) RN FROM BUDGET_MATERIAL_RISK_RESULT WHERE ACTOR_ID=?) WHERE RN>? AND RN<=? ORDER BY RN",this::read,actor,offset,offset+limit);}
    public List<Source> sources(String actor,Position position,String keyword,int limit) {
        var args=new ArrayList<Object>();args.add(actor);
        String where="J.ACTOR_ID=? AND J.STATUS='PARSED' AND S.RESULT_JSON IS NOT NULL";
        if(position!=null){where+=" AND (T.CREATED_TIME<? OR (T.CREATED_TIME=? AND T.ID<=?))";args.add(Timestamp.from(position.createdAt()));args.add(Timestamp.from(position.createdAt()));args.add(position.taskId().toString());}
        if(keyword!=null){where+=" AND (INSTR(T.FILE_NAME,?)>0 OR INSTR(S.RESULT_JSON,?)>0)";args.add(keyword);args.add(keyword);}
        args.add(limit);
        return jdbc.query("SELECT ID,FILE_NAME,CREATED_TIME,RESULT_JSON FROM (SELECT T.ID,T.FILE_NAME,T.CREATED_TIME,S.RESULT_JSON,ROW_NUMBER() OVER(ORDER BY T.CREATED_TIME DESC,T.ID DESC) RN FROM BUDGET_REVIEW_TASK T JOIN BUDGET_MATERIAL_JOB J ON J.ID=T.ID JOIN BUDGET_MATERIAL_SOURCE S ON S.TASK_ID=T.ID WHERE "+where+") WHERE RN<=? ORDER BY RN",
                (r,n)->new Source(UUID.fromString(r.getString("ID")),r.getString("FILE_NAME"),r.getTimestamp("CREATED_TIME").toInstant(),r.getString("RESULT_JSON")),args.toArray());
    }
    private String historyWhere(String actor,String keyword,java.time.Instant from,java.time.Instant before,List<Object> args) {
        String where="ACTOR_ID=?";args.add(actor);
        if(keyword!=null){where+=" AND INSTR(RESULT_JSON,?)>0";args.add(keyword);}
        if(from!=null){where+=" AND CREATED_AT>=?";args.add(Timestamp.from(from));}
        if(before!=null){where+=" AND CREATED_AT<?";args.add(Timestamp.from(before));}
        return where;
    }
    public List<Result> searchHistory(String actor,String keyword,java.time.Instant from,java.time.Instant before,int offset,int limit) {
        var args=new ArrayList<Object>();String where=historyWhere(actor,keyword,from,before,args);args.add(offset);args.add(offset+limit);
        return jdbc.query("SELECT RESULT_JSON FROM (SELECT RESULT_JSON,ROW_NUMBER() OVER(ORDER BY CREATED_AT DESC,ID) RN FROM BUDGET_MATERIAL_RISK_RESULT WHERE "+where+") WHERE RN>? AND RN<=? ORDER BY RN",this::read,args.toArray());
    }
    public long countHistory(String actor,String keyword,java.time.Instant from,java.time.Instant before) {
        var args=new ArrayList<Object>();String where=historyWhere(actor,keyword,from,before,args);
        return jdbc.queryForObject("SELECT COUNT(*) FROM BUDGET_MATERIAL_RISK_RESULT WHERE "+where,Long.class,args.toArray());
    }
}
