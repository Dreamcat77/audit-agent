package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.budget.application.port.BudgetMaterialJobRepository;
import com.fuquan.audit.budget.domain.BudgetMaterialJob;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
@Profile({"dev29","customer","local-dev"})
public class Dm8BudgetMaterialJobRepository implements BudgetMaterialJobRepository {
    private final JdbcTemplate jdbc;
    private static final RowMapper<BudgetMaterialJob> ROW=(r,n)->new BudgetMaterialJob(
        UUID.fromString(r.getString("ID")),r.getString("ACTOR_ID"),r.getString("OPERATION_KEY"),r.getString("SHA256"),
        r.getString("STATUS"),r.getString("LEASE_TOKEN"),r.getTimestamp("LEASE_UNTIL")==null?null:r.getTimestamp("LEASE_UNTIL").toInstant(),r.getTimestamp("UPDATED_AT").toInstant());
    public Dm8BudgetMaterialJobRepository(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    public Optional<BudgetMaterialJob> find(UUID id) {return jdbc.query("SELECT * FROM BUDGET_MATERIAL_JOB WHERE ID=?",ROW,id.toString()).stream().findFirst();}
    public Optional<BudgetMaterialJob> operation(String actor,String key) {
        return jdbc.query("SELECT * FROM BUDGET_MATERIAL_JOB WHERE ACTOR_ID=? AND OPERATION_KEY=?",ROW,actor,key).stream().findFirst();
    }
    public List<BudgetMaterialJob> pending(Instant now,int limit) {
        return jdbc.query("SELECT * FROM (SELECT J.*,ROW_NUMBER() OVER (ORDER BY UPDATED_AT,ID) RN FROM BUDGET_MATERIAL_JOB J WHERE STATUS='QUEUED' OR (STATUS='PROCESSING' AND LEASE_UNTIL<=?)) WHERE RN<=? ORDER BY RN",ROW,Timestamp.from(now),limit);
    }
    public void insert(BudgetMaterialJob j) {
        jdbc.update("INSERT INTO BUDGET_MATERIAL_JOB(ID,ACTOR_ID,OPERATION_KEY,SHA256,STATUS,LEASE_TOKEN,LEASE_UNTIL,UPDATED_AT) VALUES(?,?,?,?,?,?,?,?)",
            j.id().toString(),j.actorId(),j.operationKey(),j.sha256(),j.status(),j.leaseToken(),j.leaseUntil()==null?null:Timestamp.from(j.leaseUntil()),Timestamp.from(j.updatedAt()));
    }
    public boolean claim(UUID id,String token,Instant now,Instant until) {
        return jdbc.update("UPDATE BUDGET_MATERIAL_JOB SET STATUS='PROCESSING',LEASE_TOKEN=?,LEASE_UNTIL=?,UPDATED_AT=? WHERE ID=? AND (STATUS='QUEUED' OR (STATUS='PROCESSING' AND LEASE_UNTIL<=?))",
            token,Timestamp.from(until),Timestamp.from(now),id.toString(),Timestamp.from(now))==1;
    }
    public boolean renew(UUID id,String token,Instant until) {
        return jdbc.update("UPDATE BUDGET_MATERIAL_JOB SET LEASE_UNTIL=? WHERE ID=? AND STATUS='PROCESSING' AND LEASE_TOKEN=?",Timestamp.from(until),id.toString(),token)==1;
    }
    public boolean complete(UUID id,String token,String status,Instant now) {
        return jdbc.update("UPDATE BUDGET_MATERIAL_JOB SET STATUS=?,UPDATED_AT=? WHERE ID=? AND STATUS='PROCESSING' AND LEASE_TOKEN=?",status,Timestamp.from(now),id.toString(),token)==1;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void requireLease(UUID id,String token) {
        var values=jdbc.queryForList("SELECT LEASE_TOKEN FROM BUDGET_MATERIAL_JOB WHERE ID=? AND STATUS='PROCESSING' FOR UPDATE",String.class,id.toString());
        if(values.size()!=1||!Objects.equals(token,values.get(0))) throw new IllegalStateException("budget material lease lost");
    }
}
