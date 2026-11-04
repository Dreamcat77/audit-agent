package com.fuquan.audit.integration.dm8;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.budget.application.port.BudgetMaterialBatchRepository;
import com.fuquan.audit.budget.application.port.BudgetHistoryFilter;
import com.fuquan.audit.budget.domain.BudgetMaterialBatch;
import com.fuquan.audit.budget.domain.BudgetMaterialBatch.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.support.SqlLobValue;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository @Profile({"dev29","customer","local-dev"})
public class Dm8BudgetMaterialBatchRepository implements BudgetMaterialBatchRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public Dm8BudgetMaterialBatchRepository(JdbcTemplate jdbc,ObjectMapper json) {this.jdbc=jdbc;this.json=json;}
    private BudgetMaterialBatch read(ResultSet r,int n) throws SQLException {
        try {return new BudgetMaterialBatch(UUID.fromString(r.getString("ID")),r.getString("ACTOR_ID"),
            r.getString("BUDGET_YEAR"),r.getString("DEPARTMENT_CODE"),json.readValue(r.getString("MEMBERS_JSON"),new TypeReference<List<Member>>(){}),
            r.getString("STATUS"),r.getString("LEASE_TOKEN"),r.getTimestamp("LEASE_UNTIL")==null?null:r.getTimestamp("LEASE_UNTIL").toInstant(),r.getTimestamp("CREATED_AT").toInstant());}
        catch(com.fasterxml.jackson.core.JsonProcessingException e) {throw new SQLException("invalid budget batch snapshot",e);}
    }
    private static final RowMapper<Source> SOURCE=(r,n)->new Source(UUID.fromString(r.getString("TASK_ID")),UUID.fromString(r.getString("BATCH_ID")),r.getString("FACTS_JSON"),r.getString("RESULT_JSON"));
    public Optional<BudgetMaterialBatch> find(UUID id) {return jdbc.query("SELECT * FROM BUDGET_MATERIAL_BATCH WHERE ID=?",this::read,id.toString()).stream().findFirst();}
    public List<BudgetMaterialBatchRepository.HistoryReference> findHistoryPage(String actorId, BudgetHistoryFilter filter, int offset, int limit) {
        var args = new ArrayList<Object>();
        String union = historyUnion(actorId, filter, args);
        var query = "SELECT BATCH_ID,TASK_ID,CREATED_AT FROM (SELECT H.BATCH_ID,H.TASK_ID,H.CREATED_AT,ROW_NUMBER() OVER (ORDER BY H.CREATED_AT DESC,COALESCE(H.BATCH_ID,H.TASK_ID) DESC) RN FROM ("
                + union + ") H) WHERE RN>? AND RN<=(?+?) ORDER BY RN";
        args.add(offset);
        args.add(offset);
        args.add(limit);
        return jdbc.query(query, (r, n) -> {
            Instant createdAt = r.getTimestamp("CREATED_AT").toInstant();
            String batchId = r.getString("BATCH_ID");
            return batchId == null
                    ? BudgetMaterialBatchRepository.HistoryReference.task(UUID.fromString(r.getString("TASK_ID")), createdAt)
                    : BudgetMaterialBatchRepository.HistoryReference.batch(UUID.fromString(batchId), createdAt);
        }, args.toArray());
    }
    public long countHistory(String actorId, BudgetHistoryFilter filter) {
        var args = new ArrayList<Object>();
        String union = historyUnion(actorId, filter, args);
        return jdbc.queryForObject("SELECT COUNT(1) FROM (" + union + ") H", Long.class, args.toArray());
    }
    private static String historyUnion(String actorId, BudgetHistoryFilter filter, List<Object> args) {
        var batch = new StringBuilder("SELECT B.ID AS BATCH_ID,CAST(NULL AS VARCHAR(36)) AS TASK_ID,B.CREATED_AT FROM BUDGET_MATERIAL_BATCH B WHERE B.ACTOR_ID=?");
        var legacy = new StringBuilder("SELECT CAST(NULL AS VARCHAR(36)) AS BATCH_ID,T.ID AS TASK_ID,T.CREATED_TIME AS CREATED_AT FROM BUDGET_REVIEW_TASK T JOIN BUDGET_MATERIAL_JOB J ON J.ID=T.ID AND J.ACTOR_ID=? WHERE NOT EXISTS (SELECT 1 FROM BUDGET_MATERIAL_SOURCE S WHERE S.TASK_ID=T.ID) AND (T.ERROR_DETAIL IS NULL OR T.ERROR_DETAIL NOT IN ('MATERIAL_CALL_TIMEOUT','MATERIAL_TIME_LIMIT','MATERIAL_QUEUE_TIMEOUT'))");
        var batchArgs = new ArrayList<Object>();
        batchArgs.add(actorId);
        var legacyArgs = new ArrayList<Object>();
        legacyArgs.add(actorId);
        appendHistoryFilter(batch, batchArgs, filter, "B", true);
        appendHistoryFilter(legacy, legacyArgs, filter, "T", false);
        args.addAll(batchArgs);
        args.addAll(legacyArgs);
        return batch + " UNION ALL " + legacy;
    }
    private static void appendHistoryFilter(StringBuilder query, List<Object> args, BudgetHistoryFilter filter,
            String alias, boolean batch) {
        if (filter.fileName() != null) {
            if (batch) query.append(" AND EXISTS (SELECT 1 FROM BUDGET_MATERIAL_SOURCE S JOIN BUDGET_REVIEW_TASK T ON T.ID=S.TASK_ID WHERE S.BATCH_ID=B.ID AND INSTR(LOWER(T.FILE_NAME),LOWER(?))>0)");
            else query.append(" AND INSTR(LOWER(T.FILE_NAME),LOWER(?))>0");
            args.add(filter.fileName());
        }
        if (filter.createdFrom() != null) {
            query.append(" AND " + alias + (batch ? ".CREATED_AT>=?" : ".CREATED_TIME>=?"));
            args.add(Timestamp.from(filter.createdFrom()));
        }
        if (filter.createdBefore() != null) {
            query.append(" AND " + alias + (batch ? ".CREATED_AT<?" : ".CREATED_TIME<?"));
            args.add(Timestamp.from(filter.createdBefore()));
        }
    }
    public void insert(BudgetMaterialBatch b) {
        try {jdbc.update("INSERT INTO BUDGET_MATERIAL_BATCH(ID,ACTOR_ID,BUDGET_YEAR,DEPARTMENT_CODE,MEMBERS_JSON,MEMBER_COUNT,STATUS,CREATED_AT) VALUES(?,?,?,?,?,?,?,?)",
                new Object[]{b.id().toString(),b.actorId(),b.budgetYear(),b.departmentCode(),new SqlLobValue(json.writeValueAsString(b.members())),b.members().size(),b.status(),Timestamp.from(b.createdAt())},
                new int[]{Types.VARCHAR,Types.VARCHAR,Types.VARCHAR,Types.VARCHAR,Types.CLOB,Types.INTEGER,Types.VARCHAR,Types.TIMESTAMP});}
        catch(com.fasterxml.jackson.core.JsonProcessingException e) {throw new IllegalStateException("budget batch serialization failed",e);}
    }
    public List<BudgetMaterialBatch> pending(Instant now,int limit) {
        return jdbc.query("SELECT * FROM (SELECT B.*,ROW_NUMBER() OVER (ORDER BY CREATED_AT,ID) RN FROM BUDGET_MATERIAL_BATCH B WHERE STATUS<>'REVIEWED' AND (LEASE_UNTIL IS NULL OR LEASE_UNTIL<=?) AND MEMBER_COUNT=(SELECT COUNT(*) FROM BUDGET_MATERIAL_SOURCE S JOIN BUDGET_MATERIAL_JOB J ON J.ID=S.TASK_ID WHERE S.BATCH_ID=B.ID AND J.STATUS IN ('PARSED','FAILED','UNKNOWN'))) WHERE RN<=? ORDER BY RN",this::read,Timestamp.from(now),limit);
    }
    public boolean claim(UUID id,String token,Instant now,Instant until) {
        return jdbc.update("UPDATE BUDGET_MATERIAL_BATCH SET STATUS='PROCESSING',LEASE_TOKEN=?,LEASE_UNTIL=? WHERE ID=? AND STATUS<>'REVIEWED' AND (LEASE_UNTIL IS NULL OR LEASE_UNTIL<=?)",token,Timestamp.from(until),id.toString(),Timestamp.from(now))==1;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void requireLease(UUID id,String token) {
        var rows=jdbc.queryForList("SELECT LEASE_TOKEN FROM BUDGET_MATERIAL_BATCH WHERE ID=? AND STATUS='PROCESSING' FOR UPDATE",String.class,id.toString());
        if(rows.size()!=1||!Objects.equals(token,rows.get(0))) throw new IllegalStateException("budget batch lease lost");
    }
    public void complete(UUID id,String token) {
        if(jdbc.update("UPDATE BUDGET_MATERIAL_BATCH SET STATUS='REVIEWED',LEASE_UNTIL=NULL WHERE ID=? AND STATUS='PROCESSING' AND LEASE_TOKEN=?",id.toString(),token)!=1)throw new IllegalStateException("budget batch lease lost");
    }
    public void attach(Source s) {jdbc.update("INSERT INTO BUDGET_MATERIAL_SOURCE(TASK_ID,BATCH_ID) VALUES(?,?)",s.taskId().toString(),s.batchId().toString());}
    public Optional<Source> source(UUID id) {return jdbc.query("SELECT * FROM BUDGET_MATERIAL_SOURCE WHERE TASK_ID=?",SOURCE,id.toString()).stream().findFirst();}
    public List<Source> sources(UUID id) {return jdbc.query("SELECT * FROM BUDGET_MATERIAL_SOURCE WHERE BATCH_ID=? ORDER BY TASK_ID",SOURCE,id.toString());}
    public void extracted(UUID id,String data) {
        if(jdbc.update("UPDATE BUDGET_MATERIAL_SOURCE SET FACTS_JSON=? WHERE TASK_ID=? AND FACTS_JSON IS NULL",new Object[]{new SqlLobValue(data),id.toString()},new int[]{Types.CLOB,Types.VARCHAR})!=1)
            throw new IllegalStateException("immutable budget source");
    }
    public void result(UUID id,String data) {if(jdbc.update("UPDATE BUDGET_MATERIAL_SOURCE SET RESULT_JSON=? WHERE TASK_ID=?",new Object[]{new SqlLobValue(data),id.toString()},new int[]{Types.CLOB,Types.VARCHAR})!=1)throw new IllegalStateException("missing budget source");}
}
