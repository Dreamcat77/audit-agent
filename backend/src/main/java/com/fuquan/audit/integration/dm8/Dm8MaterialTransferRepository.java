package com.fuquan.audit.integration.dm8;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.common.material.transfer.*;
import static com.fuquan.audit.common.material.transfer.MaterialTransfer.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.support.SqlLobValue;
import org.springframework.stereotype.Repository;

@Repository @Profile({"customer","dev29","local-dev"})
public class Dm8MaterialTransferRepository implements MaterialTransferRepository {
    private final JdbcTemplate jdbc;private final ObjectMapper json;
    public Dm8MaterialTransferRepository(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    public Optional<Session> find(UUID id) {
        return jdbc.query("SELECT * FROM MATERIAL_TRANSFER WHERE ID=?",(r,n)-> {
            try {return new Session(id,r.getString("ACTOR_ID"),r.getString("KIND"),UUID.fromString(r.getString("OPERATION_ID")),json.readValue(r.getString("SPEC_JSON"),Spec.class),r.getString("STATUS"),r.getString("LEASE_TOKEN"),r.getTimestamp("LEASE_UNTIL")==null?null:r.getTimestamp("LEASE_UNTIL").toInstant(),r.getString("RESULT_JSON"),r.getTimestamp("CREATED_AT").toInstant());}
            catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new SQLException("invalid transfer metadata",e);}
        },id.toString()).stream().findFirst();
    }
    public void insert(Session s) {
        try {String spec=json.writeValueAsString(s.spec());duplicate(()->jdbc.update("INSERT INTO MATERIAL_TRANSFER(ID,ACTOR_ID,KIND,OPERATION_ID,SPEC_JSON,STATUS,CREATED_AT) VALUES(?,?,?,?,?,'OPEN',?)",
            new Object[]{s.id().toString(),s.actor(),s.kind(),s.operation().toString(),new SqlLobValue(spec),Timestamp.from(s.createdAt())},new int[]{Types.VARCHAR,Types.VARCHAR,Types.VARCHAR,Types.VARCHAR,Types.CLOB,Types.TIMESTAMP}));}
        catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("transfer serialization failed",e);}
    }
    public List<Part> parts(UUID id){return jdbc.query("SELECT PART_INDEX,FILE_SIZE,SHA256,STORAGE_KEY FROM MATERIAL_TRANSFER_PART WHERE TRANSFER_ID=? ORDER BY PART_INDEX",(r,n)->new Part(r.getInt(1),r.getLong(2),r.getString(3),r.getString(4)),id.toString());}
    public void insertPart(UUID id,Part p){duplicate(()->jdbc.update("INSERT INTO MATERIAL_TRANSFER_PART(TRANSFER_ID,PART_INDEX,FILE_SIZE,SHA256,STORAGE_KEY) VALUES(?,?,?,?,?)",id.toString(),p.index(),p.size(),p.sha256(),p.storageKey()));}
    public boolean claim(UUID id,String token,Instant now,Instant until){return jdbc.update("UPDATE MATERIAL_TRANSFER SET STATUS='ASSEMBLING',LEASE_TOKEN=?,LEASE_UNTIL=? WHERE ID=? AND (STATUS='OPEN' OR (STATUS='ASSEMBLING' AND LEASE_UNTIL<?))",token,Timestamp.from(until),id.toString(),Timestamp.from(now))==1;}
    public boolean renew(UUID id,String token,Instant until){return jdbc.update("UPDATE MATERIAL_TRANSFER SET LEASE_UNTIL=? WHERE ID=? AND STATUS='ASSEMBLING' AND LEASE_TOKEN=?",Timestamp.from(until),id.toString(),token)==1;}
    public boolean complete(UUID id,String token,String value){return jdbc.update("UPDATE MATERIAL_TRANSFER SET STATUS='COMPLETED',RESULT_JSON=?,LEASE_TOKEN=NULL,LEASE_UNTIL=NULL WHERE ID=? AND STATUS='ASSEMBLING' AND LEASE_TOKEN=?",new Object[]{new SqlLobValue(value),id.toString(),token},new int[]{Types.CLOB,Types.VARCHAR,Types.VARCHAR})==1;}
    public void release(UUID id,String token){jdbc.update("UPDATE MATERIAL_TRANSFER SET STATUS='OPEN',LEASE_TOKEN=NULL,LEASE_UNTIL=NULL WHERE ID=? AND STATUS='ASSEMBLING' AND LEASE_TOKEN=?",id.toString(),token);}
    private void duplicate(Runnable operation){try{operation.run();}catch(org.springframework.dao.DataAccessException e){for(Throwable t=e;t!=null;t=t.getCause())if(t instanceof SQLException sql&&sql.getErrorCode()==-6602)throw new org.springframework.dao.DuplicateKeyException("transfer already exists",e);throw e;}}
}
