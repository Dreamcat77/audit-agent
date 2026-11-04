package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.common.material.application.port.MaterialExtractionLedger;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.support.SqlLobValue;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository @Profile({"local-dev","dev29","customer"})
@Transactional(propagation=Propagation.REQUIRES_NEW)
public class Dm8MaterialExtractionLedger implements MaterialExtractionLedger {
    private final JdbcTemplate jdbc;
    public Dm8MaterialExtractionLedger(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public Optional<Entry> find(UUID id) {
        return jdbc.query("SELECT * FROM MATERIAL_EXTRACTION_LEDGER WHERE ID=?",(r,n)->new Entry(id,
            r.getString("SOURCE_HASH"),r.getString("POLICY_HASH"),r.getInt("UNIT_COUNT"),r.getInt("NEXT_UNIT"),
            r.getInt("CALL_COUNT"),r.getLong("USED_MILLIS"),r.getString("STATUS"),r.getString("CALL_TOKEN"),
            r.getTimestamp("UPDATED_AT").toInstant(),r.getString("RESULTS_JSON"),r.getString("REASON_CODE"),
            r.getTimestamp("DEADLINE_AT")==null?null:r.getTimestamp("DEADLINE_AT").toInstant()),id.toString()).stream().findFirst();
    }
    public void insert(Entry e) {
        jdbc.update("INSERT INTO MATERIAL_EXTRACTION_LEDGER(ID,SOURCE_HASH,POLICY_HASH,UNIT_COUNT,NEXT_UNIT,CALL_COUNT,USED_MILLIS,STATUS,UPDATED_AT,RESULTS_JSON) VALUES(?,?,?,?,0,0,0,'READY',?,?)",
            new Object[]{e.id().toString(),e.sourceHash(),e.policyHash(),e.unitCount(),Timestamp.from(e.updatedAt()),new SqlLobValue("[]")},
            new int[]{Types.VARCHAR,Types.VARCHAR,Types.VARCHAR,Types.INTEGER,Types.TIMESTAMP,Types.CLOB});
    }
    public boolean begin(UUID id,int next,String token,Instant now) {
        return jdbc.update("UPDATE MATERIAL_EXTRACTION_LEDGER SET STATUS='CALLING',CALL_TOKEN=?,UPDATED_AT=? WHERE ID=? AND STATUS='READY' AND NEXT_UNIT=? AND CALL_COUNT<512 AND USED_MILLIS<900000",
                token,Timestamp.from(now),id.toString(),next)==1;
    }
    public boolean acknowledge(UUID id,int next,String token,int calls,long elapsed,String results,Instant now) {
        if(calls<0||elapsed<0)throw new IllegalArgumentException("invalid extraction usage");
        return jdbc.update("UPDATE MATERIAL_EXTRACTION_LEDGER SET STATUS=CASE WHEN NEXT_UNIT+1=UNIT_COUNT THEN 'COMPLETE' ELSE 'READY' END,NEXT_UNIT=NEXT_UNIT+1,CALL_COUNT=CALL_COUNT+?,USED_MILLIS=USED_MILLIS+?,RESULTS_JSON=?,CALL_TOKEN=NULL,UPDATED_AT=? WHERE ID=? AND STATUS='CALLING' AND NEXT_UNIT=? AND CALL_TOKEN=? AND CALL_COUNT+?<=512 AND USED_MILLIS+?<=900000 AND (DEADLINE_AT IS NULL OR DEADLINE_AT>?)",
                new Object[]{calls,elapsed,new SqlLobValue(results),Timestamp.from(now),id.toString(),next,token,calls,elapsed,Timestamp.from(now)},
                new int[]{Types.INTEGER,Types.BIGINT,Types.CLOB,Types.TIMESTAMP,Types.VARCHAR,Types.INTEGER,Types.VARCHAR,Types.INTEGER,Types.BIGINT,Types.TIMESTAMP})==1;
    }
    public void unknown(UUID id,String token,String reason,Instant now) {
        jdbc.update("UPDATE MATERIAL_EXTRACTION_LEDGER SET STATUS='UNKNOWN',REASON_CODE=?,UPDATED_AT=? WHERE ID=? AND STATUS='CALLING' AND CALL_TOKEN=?",
                reason,Timestamp.from(now),id.toString(),token);
    }
    public void startDeadline(UUID id,Instant deadline) {
        jdbc.update("UPDATE MATERIAL_EXTRACTION_LEDGER SET DEADLINE_AT=? WHERE ID=? AND STATUS='READY' AND DEADLINE_AT IS NULL",Timestamp.from(deadline),id.toString());
    }
    public boolean terminate(UUID id,String token,String reason,Instant now) {
        return jdbc.update("UPDATE MATERIAL_EXTRACTION_LEDGER SET STATUS='UNKNOWN',REASON_CODE=?,RESULTS_JSON=?,CALL_TOKEN=NULL,UPDATED_AT=? WHERE ID=? AND ((STATUS='CALLING' AND CALL_TOKEN=?) OR (STATUS='READY' AND ? IS NULL))",
            new Object[]{reason,new SqlLobValue("[]"),Timestamp.from(now),id.toString(),token,token},
            new int[]{Types.VARCHAR,Types.CLOB,Types.TIMESTAMP,Types.VARCHAR,Types.VARCHAR,Types.VARCHAR})==1;
    }
}
