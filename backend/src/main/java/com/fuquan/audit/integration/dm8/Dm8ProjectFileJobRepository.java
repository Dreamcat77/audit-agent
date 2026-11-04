package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.contract.application.project.port.ProjectFileJobRepository;
import com.fuquan.audit.contract.domain.project.ProjectFileJob;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository @Profile({"local-dev","dev29","customer"})
public class Dm8ProjectFileJobRepository implements ProjectFileJobRepository {
    private final JdbcTemplate jdbc;
    public Dm8ProjectFileJobRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    private static Timestamp ts(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static final RowMapper<ProjectFileJob> ROW=(r,n)->new ProjectFileJob(
        UUID.fromString(r.getString("JOB_ID")),UUID.fromString(r.getString("PROJECT_ID")),
        UUID.fromString(r.getString("FILE_ID")),r.getString("FILE_SHA256"),r.getString("ACTOR_ID"),
        r.getString("REQUEST_ID"),r.getString("TRACE_ID"),r.getString("SOURCE_IP"),r.getString("STATUS"),
        r.getInt("ATTEMPT_NO"),r.getString("LEASE_TOKEN"),r.getTimestamp("LEASE_UNTIL")==null?null:
        r.getTimestamp("LEASE_UNTIL").toInstant(),r.getString("REASON_CODE"),
        r.getString("PARSE_RESULT_ID")==null?null:UUID.fromString(r.getString("PARSE_RESULT_ID")),
        r.getTimestamp("CREATED_AT").toInstant(),r.getTimestamp("UPDATED_AT").toInstant());
    public void insert(ProjectFileJob j) {
        jdbc.update("INSERT INTO PROJECT_FILE_PROCESSING_JOB(JOB_ID,PROJECT_ID,FILE_ID,FILE_SHA256,ACTOR_ID,"
            +"REQUEST_ID,TRACE_ID,SOURCE_IP,STATUS,ATTEMPT_NO,CREATED_AT,UPDATED_AT) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
            j.id().toString(),j.projectId().toString(),j.fileId().toString(),j.fileSha256(),j.actorId(),
            j.requestId(),j.traceId(),j.sourceIp(),j.status(),j.attemptNo(),ts(j.createdAt()),ts(j.updatedAt()));
    }
    public Optional<ProjectFileJob> find(UUID id) {
        return jdbc.query("SELECT * FROM PROJECT_FILE_PROCESSING_JOB WHERE JOB_ID=?",ROW,id.toString()).stream().findFirst();
    }
    public Optional<ProjectFileJob> byFile(UUID projectId,UUID fileId) {
        return jdbc.query("SELECT * FROM PROJECT_FILE_PROCESSING_JOB WHERE PROJECT_ID=? AND FILE_ID=?",ROW,
            projectId.toString(),fileId.toString()).stream().findFirst();
    }
    public List<ProjectFileJob> byProject(UUID projectId) {
        return jdbc.query("SELECT * FROM PROJECT_FILE_PROCESSING_JOB WHERE PROJECT_ID=? ORDER BY CREATED_AT,JOB_ID",ROW,projectId.toString());
    }
    public List<ProjectFileJob> pending(Instant now,int limit) {
        return jdbc.query("SELECT * FROM (SELECT J.*,ROW_NUMBER() OVER(ORDER BY CREATED_AT,JOB_ID) RN "
            +"FROM PROJECT_FILE_PROCESSING_JOB J WHERE STATUS='QUEUED' OR (STATUS='PROCESSING' AND LEASE_UNTIL<=?)) "
            +"WHERE RN<=? ORDER BY RN",ROW,ts(now),limit);
    }
    public List<ProjectFileJob.Attempt> attempts(UUID id) {
        return jdbc.query("SELECT * FROM PROJECT_FILE_PROCESSING_ATTEMPT WHERE JOB_ID=? ORDER BY ATTEMPT_NO",
            (r,n)->new ProjectFileJob.Attempt(id,r.getInt("ATTEMPT_NO"),r.getString("LEASE_TOKEN"),
                r.getString("STATUS"),r.getString("REASON_CODE"),r.getTimestamp("STARTED_AT").toInstant(),
                r.getTimestamp("COMPLETED_AT")==null?null:r.getTimestamp("COMPLETED_AT").toInstant()),id.toString());
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public boolean claim(UUID id,String token,Instant now,Instant until) {
        if(token==null||!until.isAfter(now)) return false;
        int changed=jdbc.update("UPDATE PROJECT_FILE_PROCESSING_JOB SET STATUS='PROCESSING',ATTEMPT_NO=ATTEMPT_NO+1,"
            +"LEASE_TOKEN=?,LEASE_UNTIL=?,REASON_CODE=NULL,UPDATED_AT=? WHERE JOB_ID=? "
            +"AND (STATUS='QUEUED' OR (STATUS='PROCESSING' AND LEASE_UNTIL<=?))",token,ts(until),ts(now),id.toString(),ts(now));
        if(changed!=1)return false;
        jdbc.update("UPDATE PROJECT_FILE_PROCESSING_ATTEMPT SET STATUS='EXPIRED',REASON_CODE='LEASE_EXPIRED',"
            +"COMPLETED_AT=? WHERE JOB_ID=? AND STATUS='PROCESSING'",ts(now),id.toString());
        ProjectFileJob claimed=find(id).orElseThrow();
        jdbc.update("INSERT INTO PROJECT_FILE_PROCESSING_ATTEMPT(JOB_ID,ATTEMPT_NO,LEASE_TOKEN,STATUS,STARTED_AT)"
            +" VALUES(?,?,?,'PROCESSING',?)",id.toString(),claimed.attemptNo(),token,ts(now));return true;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public boolean renew(UUID id,String token,Instant now,Instant until) {
        if(!until.isAfter(now))return false;
        return jdbc.update("UPDATE PROJECT_FILE_PROCESSING_JOB SET LEASE_UNTIL=?,UPDATED_AT=? WHERE JOB_ID=? "
            +"AND STATUS='PROCESSING' AND LEASE_TOKEN=? AND LEASE_UNTIL>?",ts(until),ts(now),id.toString(),token,ts(now))==1;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void requireLease(UUID id,String token,Instant now) {
        var found=jdbc.query("SELECT * FROM PROJECT_FILE_PROCESSING_JOB WHERE JOB_ID=? FOR UPDATE",ROW,id.toString());
        if(found.size()!=1||!"PROCESSING".equals(found.get(0).status())
                ||!Objects.equals(token,found.get(0).leaseToken())||!found.get(0).leaseUntil().isAfter(now)) {
            throw new IllegalStateException("processing lease lost");
        }
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public boolean finish(UUID id,String token,String status,String reason,UUID output,Instant now) {
        if(!Set.of("SUCCEEDED","FAILED","UNKNOWN","QUEUED").contains(status))throw new IllegalArgumentException("invalid terminal state");
        int changed=jdbc.update("UPDATE PROJECT_FILE_PROCESSING_JOB SET STATUS=?,REASON_CODE=?,PARSE_RESULT_ID=?,"
            +"LEASE_TOKEN=NULL,LEASE_UNTIL=NULL,UPDATED_AT=? WHERE JOB_ID=? AND STATUS='PROCESSING' "
            +"AND LEASE_TOKEN=? AND LEASE_UNTIL>?",status,reason,output==null?null:output.toString(),ts(now),id.toString(),token,ts(now));
        if(changed!=1)return false;
        int closed=jdbc.update("UPDATE PROJECT_FILE_PROCESSING_ATTEMPT SET STATUS=?,REASON_CODE=?,COMPLETED_AT=? "
            +"WHERE JOB_ID=? AND LEASE_TOKEN=? AND STATUS='PROCESSING'","QUEUED".equals(status)?"DEFERRED":status,reason,ts(now),id.toString(),token);
        if(closed!=1)throw new IllegalStateException("file processing attempt identity unproven");return true;
    }
    public boolean retry(UUID projectId,UUID id,Instant now) {
        return jdbc.update("UPDATE PROJECT_FILE_PROCESSING_JOB SET STATUS='QUEUED',REASON_CODE=NULL,UPDATED_AT=? "
            +"WHERE PROJECT_ID=? AND JOB_ID=? AND STATUS='FAILED'",ts(now),projectId.toString(),id.toString())==1;
    }
}
