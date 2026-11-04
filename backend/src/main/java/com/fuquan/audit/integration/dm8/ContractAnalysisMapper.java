package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** Only the independent analysis row is stored here; source text stays in Intake tables. */
@Mapper
public interface ContractAnalysisMapper {
    record Row(String id,String projectId,String owner,String digest,String status,long version,String token,
            LocalDateTime until,LocalDateTime created,LocalDateTime deadline,String payload) {}
    @Select("SELECT SNAPSHOT_JSON FROM CONTRACT_ANALYSIS_CASE WHERE ID=#{id}")
    @ResultType(String.class)
    String find(String id);
    @Select("SELECT ID FROM (SELECT ID,ROW_NUMBER() OVER(ORDER BY CREATED_AT DESC,ID) RN FROM CONTRACT_ANALYSIS_CASE WHERE OWNER_ID=#{owner}) WHERE RN>#{offset} AND RN<=#{end}")
    List<String> recent(@Param("owner") String owner,@Param("offset") int offset,@Param("end") int end);
    @Select("<script>SELECT ID FROM (SELECT C.ID,ROW_NUMBER() OVER(ORDER BY C.CREATED_AT DESC,C.ID) RN FROM CONTRACT_ANALYSIS_CASE C JOIN PROJECT P ON P.ID=C.PROJECT_ID WHERE C.OWNER_ID=#{owner} <if test='projectName != null and projectName != \"\"'>AND INSTR(UPPER(COALESCE(P.NAME,'')),UPPER(#{projectName})) &gt; 0</if> <if test='from != null'>AND C.CREATED_AT &gt;= #{from}</if> <if test='to != null'>AND C.CREATED_AT &lt; #{to}</if>) WHERE RN&gt;#{offset} AND RN&lt;=#{end}</script>")
    List<String> recentFiltered(@Param("owner") String owner,@Param("projectName") String projectName,
            @Param("from") LocalDateTime from,@Param("to") LocalDateTime to,
            @Param("offset") int offset,@Param("end") int end);
    @Select("SELECT ID FROM (SELECT ID,ROW_NUMBER() OVER(ORDER BY ROW_VERSION,CREATED_AT,ID) RN FROM CONTRACT_ANALYSIS_CASE WHERE STATUS='QUEUED' OR (STATUS='RUNNING' AND LEASE_UNTIL<=#{now})) WHERE RN<=#{limit}")
    List<String> pending(@Param("now") LocalDateTime now,@Param("limit") int limit);
    @Insert("INSERT INTO CONTRACT_ANALYSIS_CASE(ID,PROJECT_ID,OWNER_ID,INPUT_DIGEST,STATUS,ROW_VERSION,LEASE_TOKEN,LEASE_UNTIL,CREATED_AT,DEADLINE,SNAPSHOT_JSON) VALUES(#{id},#{projectId},#{owner},#{digest},#{status},#{version},#{token,jdbcType=VARCHAR},#{until,jdbcType=TIMESTAMP},#{created},#{deadline},#{payload,jdbcType=CLOB,typeHandler=org.apache.ibatis.type.ClobTypeHandler})")
    int insert(Row row);
    @Update("<script>UPDATE CONTRACT_ANALYSIS_CASE SET STATUS=#{n.status},ROW_VERSION=#{n.version},LEASE_TOKEN=#{n.token,jdbcType=VARCHAR},LEASE_UNTIL=#{n.until,jdbcType=TIMESTAMP},SNAPSHOT_JSON=#{n.payload,jdbcType=CLOB,typeHandler=org.apache.ibatis.type.ClobTypeHandler} WHERE ID=#{n.id} AND ROW_VERSION=#{version} AND OWNER_ID=#{n.owner} AND PROJECT_ID=#{n.projectId} AND INPUT_DIGEST=#{n.digest} <choose><when test='oldToken != null'>AND LEASE_TOKEN=#{oldToken}</when><otherwise>AND LEASE_TOKEN IS NULL</otherwise></choose> <choose><when test='claim'>AND (STATUS='QUEUED' OR (STATUS='RUNNING' AND LEASE_UNTIL&lt;=#{now}))</when><when test='oldToken != null'>AND STATUS='RUNNING' AND LEASE_UNTIL&gt;#{now}</when></choose></script>")
    int replace(@Param("n") Row row,@Param("version") long version,@Param("oldToken") String token,
            @Param("claim") boolean claim,@Param("now") LocalDateTime now);
}
