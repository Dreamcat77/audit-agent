package com.fuquan.audit.contract.application.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.contract.application.project.port.ProjectRepository;
import com.fuquan.audit.contract.domain.project.Project;
import com.fuquan.audit.contract.infra.memory.analysis.InMemoryContractAnalysisRepository;
import com.fuquan.audit.integration.dm8.ContractAnalysisMapper;
import com.fuquan.audit.integration.dm8.Dm8ContractAnalysisRepository;
import java.time.*;
import java.util.*;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import static com.fuquan.audit.contract.application.analysis.ContractAnalysisCase.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ContractAnalysisHistorySearchTest {
    private static final Instant NOW=Instant.parse("2026-11-04T06:00:00Z");
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private final ContractAnalysisMapper mapper=mock(ContractAnalysisMapper.class);
    private record Entry(ContractAnalysisCase value,String currentName) {}

    @Test void findsTheDisplayedExtractedNameWhenStoredProjectNameIsStillGenerated() throws Exception {
        var entry=entry("owner",0,"自动整理项目-原始名称","智能化内控模块维护及优化服务");
        var report=new ContractAnalysisReport(null,null,null).summary(entry.value(),entry.currentName());
        assertThat(report.projectName()).isEqualTo("智能化内控模块维护及优化服务");
        for(var repository:repositories(List.of(entry))){
            assertThat(repository.recent("owner",0,15,"智能化",null,null)).containsExactly(entry.value());
            assertThat(repository.recent("owner",0,15,"模块维护",null,null)).containsExactly(entry.value());
            assertThat(repository.recent("owner",0,15,"原始名称",null,null)).isEmpty();
        }
    }

    @Test void searchesCurrentRenamedProjectRatherThanOldFactsOrUnrelatedSnapshotText() throws Exception {
        var renamed=entry("owner",0,"档案维护服务项目","智能化内控模块维护及优化服务");
        for(var repository:repositories(List.of(renamed))){
            assertThat(repository.recent("owner",0,15,"智能化",null,null)).isEmpty();
            assertThat(repository.recent("owner",0,15,"档案维护",null,null)).containsExactly(renamed.value());
        }
    }

    @Test void matchesBeyondTheFirstCandidateBatchAndPaginatesOnlyMatchingRows() throws Exception {
        var entries=new ArrayList<Entry>();
        for(int index=0;index<205;index++)entries.add(entry("owner",index,
                Set.of(110,140,170).contains(index)?"智能化维护服务项目":"普通设备采购项目",null));
        for(var repository:repositories(entries)){
            assertThat(repository.recent("owner",0,2,"智能化",null,null))
                    .containsExactly(entries.get(110).value(),entries.get(140).value());
            assertThat(repository.recent("owner",2,2,"智能化",null,null)).containsExactly(entries.get(170).value());
            assertThat(repository.recent("owner",3,2,"智能化",null,null)).isEmpty();
        }
        verify(mapper,never()).find(anyString()); // batch snapshots avoid one query per candidate
    }

    @Test void preservesOwnerIsolationAndHalfOpenDateRangeWithAndWithoutKeyword() throws Exception {
        var foreign=entry("other",0,"智能化维护服务项目",null);
        var before=entry("owner",3,"智能化维护服务项目",null);
        var included=entry("owner",2,"自动整理项目-待识别","智能化内控模块维护及优化服务");
        var end=entry("owner",1,"智能化维护服务项目",null);
        for(var repository:repositories(List.of(foreign,before,included,end))){
            for(String keyword:Arrays.asList("智能化",null,""))assertThat(repository.recent("owner",0,15,keyword,
                    NOW.minusSeconds(2),NOW.minusSeconds(1))).containsExactly(included.value());
        }
    }

    @Test void treatsPercentAndUnderscoreLiterallyAndNormalizesCaseAndWidth() throws Exception {
        var literal=entry("owner",0,"ＡＢＣ设备_20%优化服务项目",null);
        var other=entry("owner",1,"ABC设备X20A优化服务项目",null);
        for(var repository:repositories(List.of(literal,other))){
            assertThat(repository.recent("owner",0,15,"  abc设备_20%  ",null,null)).containsExactly(literal.value());
            assertThat(repository.recent("owner",0,15,"_20%",null,null)).containsExactly(literal.value());
        }
    }

    @Test void generatedFallbackNameRemainsSearchable() throws Exception {
        var fallback=entry("owner",0,"自动整理项目-旧临时名",null);
        String shown=ContractAnalysisProjectNames.resolve(fallback.value(),fallback.currentName(),List.of());
        for(var repository:repositories(List.of(fallback)))
            assertThat(repository.recent("owner",0,15,shown.substring(4),null,null)).containsExactly(fallback.value());
    }

    @Test void dm8CandidateQueryBindsScopeAndDatesBeforeBatchingAndMapsClobExplicitly() {
        var configuration=new Configuration();configuration.addMapper(ContractAnalysisMapper.class);
        var statement=configuration.getMappedStatement(ContractAnalysisMapper.class.getName()+".recentFiltered");
        var parameters=new HashMap<String,Object>();
        parameters.put("owner","owner");parameters.put("from",LocalDateTime.ofInstant(NOW,ZoneOffset.UTC));
        parameters.put("to",LocalDateTime.ofInstant(NOW.plusSeconds(86400),ZoneOffset.UTC));
        parameters.put("offset",0);parameters.put("end",100);
        var bound=statement.getBoundSql(parameters);
        assertThat(bound.getSql()).contains("C.OWNER_ID=?","C.CREATED_AT >= ?","C.CREATED_AT < ?",
                "ORDER BY C.CREATED_AT DESC,C.ID","C.SNAPSHOT_JSON PAYLOAD","P.NAME PROJECT_NAME");
        assertThat(bound.getSql()).doesNotContain("INSTR","LIKE","owner'");
        assertThat(bound.getParameterMappings()).extracting(mapping->mapping.getProperty())
                .containsExactly("owner","from","to","offset","end");
        assertThat(statement.getResultMaps().get(0).getConstructorResultMappings().get(0).getTypeHandler())
                .isInstanceOf(org.apache.ibatis.type.ClobTypeHandler.class);
    }

    private List<ContractAnalysisRepository> repositories(List<Entry> entries) throws Exception {
        var projects=mock(ProjectRepository.class);
        var memory=new InMemoryContractAnalysisRepository(projects);
        for(var entry:entries){
            var value=entry.value();memory.insert(value);
            when(projects.findById(value.projectId())).thenReturn(Optional.of(new Project(value.projectId(),
                    entry.currentName(),null,null,value.owner(),1,NOW,NOW)));
        }
        when(mapper.recentFiltered(anyString(),any(),any(),anyInt(),anyInt())).thenAnswer(invocation->{
            String owner=invocation.getArgument(0);LocalDateTime from=invocation.getArgument(1),to=invocation.getArgument(2);
            int offset=invocation.getArgument(3),end=invocation.getArgument(4);
            var result=new ArrayList<ContractAnalysisMapper.HistoryRow>();
            for(var entry:entries.stream().filter(e->e.value().owner().equals(owner))
                    .filter(e->from==null||!e.value().createdAt().isBefore(from.toInstant(ZoneOffset.UTC)))
                    .filter(e->to==null||e.value().createdAt().isBefore(to.toInstant(ZoneOffset.UTC)))
                    .sorted(Comparator.comparing((Entry e)->e.value().createdAt()).reversed().thenComparing(e->e.value().id()))
                    .skip(offset).limit(end-offset).toList())
                result.add(new ContractAnalysisMapper.HistoryRow(json.writeValueAsString(entry.value()),entry.currentName()));
            return result;
        });
        return List.of(memory,new Dm8ContractAnalysisRepository(mapper,json));
    }

    private Entry entry(String owner,int age,String currentName,String extractedName) {
        var scope=ContractAnalysisEvidenceTest.scope("项目名称："+(extractedName==null?currentName:extractedName));
        var steps=new ArrayList<Step>();
        if(extractedName!=null){
            var evidence=new ContractAnalysisEvidence().locate(scope,scope.text()).orElseThrow();
            var output=new Output("CONTRACT","GENERAL",true,
                    List.of(new Fact("PROJECT_NAME",extractedName,"MODEL",List.of(evidence))),
                    List.of(),List.of(),List.of(),0,"synthetic-test","contract-case-v1");
            steps.add(new Step("semantic/1",scope.source().fileId(),"SEMANTIC",0,scope.text().length(),
                    "c".repeat(64),StepState.COMPLETED,NOW,NOW,output,null));
        }
        var project=UUID.randomUUID();var created=NOW.minusSeconds(age);
        return new Entry(new ContractAnalysisCase(identity(owner,project),project,owner,"d".repeat(64),State.PARTIAL,
                1,null,null,1,created,NOW.plusSeconds(1800),List.of(scope.source()),steps,null),currentName);
    }
}
