package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** DM8 SQL only. Snapshot writes execute inside AuditCaseTransactions. */
@Mapper
public interface AutoCaseMapper {
    record Row(String id,String owner,String operation,String fingerprint,String status,long version,String token,
               LocalDateTime until,LocalDateTime created,String payload) {}
    @Select("SELECT SNAPSHOT_JSON FROM AUDIT_CASE WHERE ID=#{id}")
    String find(String id);
    @Select("SELECT SNAPSHOT_JSON FROM AUDIT_CASE_FILE WHERE CASE_ID=#{id} ORDER BY FILE_ORDER")
    List<String> files(String id);
    record StoredFact(String fileId,int position,String payload) {}
    @Select("SELECT FILE_ID,FACT_ORDER,FACT_JSON FROM AUDIT_FACT WHERE CASE_ID=#{id} ORDER BY FILE_ID,FACT_ORDER")
    @ConstructorArgs({@Arg(column="FILE_ID",javaType=String.class),@Arg(column="FACT_ORDER",javaType=int.class),
            @Arg(column="FACT_JSON",javaType=String.class,typeHandler=org.apache.ibatis.type.ClobTypeHandler.class)})
    List<StoredFact> facts(String id);
    @Select({"<script>SELECT ID FROM (SELECT ID,ROW_NUMBER() OVER(ORDER BY CREATED_AT DESC,ID) RN FROM AUDIT_CASE WHERE 1=1",
        "<if test='owner != null'> AND OWNER_ID=#{owner}</if>",
        ") WHERE RN&lt;=100</script>"})
    List<String> recent(String owner);
    @Select({"<script>SELECT SNAPSHOT_JSON FROM (SELECT SNAPSHOT_JSON,ROW_NUMBER() OVER(ORDER BY CREATED_AT DESC,ID) RN FROM AUDIT_CASE WHERE 1=1",
        "<if test='owner != null'> AND OWNER_ID=#{owner}</if>",
        "<if test='beforeId != null'> AND (CREATED_AT &lt; (SELECT CREATED_AT FROM AUDIT_CASE WHERE ID=#{beforeId} <if test='owner != null'>AND OWNER_ID=#{owner}</if>) OR (CREATED_AT=(SELECT CREATED_AT FROM AUDIT_CASE WHERE ID=#{beforeId} <if test='owner != null'>AND OWNER_ID=#{owner}</if>) AND ID &gt; #{beforeId}))</if>",
        ") WHERE RN &lt;= #{limit} ORDER BY RN</script>"})
    List<String> history(@Param("owner") String owner,@Param("beforeId") String beforeId,@Param("limit") int limit);
    @Select("SELECT ID FROM (SELECT ID,ROW_NUMBER() OVER(ORDER BY CREATED_AT,ID) RN FROM AUDIT_CASE WHERE STATUS='QUEUED' OR (STATUS IN ('RUNNING','UPLOADING') AND LEASE_UNTIL<=#{now})) WHERE RN<=8")
    List<String> pending(LocalDateTime now);
    @Insert("INSERT INTO AUDIT_CASE(ID,OWNER_ID,OPERATION_KEY,FINGERPRINT,STATUS,ROW_VERSION,LEASE_TOKEN,LEASE_UNTIL,CREATED_AT,SNAPSHOT_JSON) VALUES(#{id},#{owner},#{operation},#{fingerprint},#{status},#{version},#{token},#{until},#{created},#{payload,jdbcType=CLOB,typeHandler=org.apache.ibatis.type.ClobTypeHandler})")
    int insert(Row row);
    @Update("UPDATE AUDIT_CASE SET STATUS=#{n.status},ROW_VERSION=#{n.version},LEASE_TOKEN=#{n.token},LEASE_UNTIL=#{n.until},SNAPSHOT_JSON=#{n.payload,jdbcType=CLOB,typeHandler=org.apache.ibatis.type.ClobTypeHandler} WHERE ID=#{n.id} AND ROW_VERSION=#{version} AND LEASE_TOKEN=#{token}")
    int replace(@Param("n") Row row,@Param("version") long version,@Param("token") String token);
    @Delete("DELETE FROM AUDIT_CASE_FILE WHERE CASE_ID=#{id}") int deleteFiles(String id);
    @Insert("INSERT INTO AUDIT_CASE_FILE(ID,CASE_ID,FILE_ORDER,SHA256,STORAGE_KEY,STATUS,SNAPSHOT_JSON) VALUES(#{id},#{caseId},#{order},#{sha},#{key},#{status},#{payload,jdbcType=CLOB,typeHandler=org.apache.ibatis.type.ClobTypeHandler})")
    int insertFile(@Param("id") String id,@Param("caseId") String caseId,@Param("order") int order,@Param("sha") String sha,@Param("key") String key,@Param("status") String status,@Param("payload") String payload);
    @Delete("DELETE FROM AUDIT_FACT WHERE CASE_ID=#{id}") int deleteFacts(String id);
    @Insert("INSERT INTO AUDIT_FACT(ID,CASE_ID,FILE_ID,FACT_ORDER,FACT_TYPE,DATA_TYPE,VERIFICATION,FACT_JSON) VALUES(#{id},#{caseId},#{fileId},#{position},#{type},#{dataType},#{verification},#{payload,jdbcType=CLOB,typeHandler=org.apache.ibatis.type.ClobTypeHandler})")
    int insertFact(@Param("id") String id,@Param("caseId") String caseId,@Param("fileId") String fileId,@Param("position") int position,@Param("type") String type,@Param("dataType") String dataType,@Param("verification") String verification,@Param("payload") String payload);
    @Delete("DELETE FROM AUDIT_QUOTE_LINE WHERE CASE_ID=#{id}") int deleteLines(String id);
    @Insert("INSERT INTO AUDIT_QUOTE_LINE(ID,CASE_ID,FILE_ID,ITEM_NAME,QUANTITY,UNIT_PRICE,LINE_AMOUNT,LOCATOR) VALUES(#{id},#{caseId},#{fileId},#{item},#{qty},#{price},#{amount},#{locator})")
    int insertLine(@Param("id") String id,@Param("caseId") String caseId,@Param("fileId") String fileId,@Param("item") String item,@Param("qty") java.math.BigDecimal qty,@Param("price") java.math.BigDecimal price,@Param("amount") java.math.BigDecimal amount,@Param("locator") String locator);
}
