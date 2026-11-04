package com.fuquan.audit.contract.application.project.factai;

import com.fuquan.audit.contract.application.project.ProjectIntakeProperties;
import com.fuquan.audit.contract.application.project.archive.SelectedProjectArchiveReader;
import com.fuquan.audit.contract.application.project.parse.ParsedProjectDocument;
import com.fuquan.audit.contract.application.project.parse.ProjectDocumentParserTest;
import com.fuquan.audit.contract.application.project.parse.ProjectDocxContainerGuard;
import com.fuquan.audit.contract.application.project.parse.ProjectDocxTextParser;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.Scope;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.Segment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.assertj.core.api.Assertions.*;

/** Opt-in, read-only real-package capacity regression. No model, upload, or database calls. */
@EnabledIfSystemProperty(named="audit.v2.fixtureRoot",matches=".+")
class V2FactOutputCapacityTest {
    private static final Pattern ROW_ID=Pattern.compile("(?:MX|QA2)-\\d{3}");

    @Test void actualTwelveAndFortyEightRowsKeepAllSourceIdentitiesWithinOutputCapacity() throws Exception {
        Path root=Path.of(System.getProperty("audit.v2.fixtureRoot"));
        List<Map<String,Object>> statistics=new ArrayList<>();
        for(int scenario:List.of(1,2)) {
            String folder=scenario==1?"01_基准一致_长合同":"02_超长合同_48行明细";
            Path source=root.resolve("00_解压后按场景多选上传").resolve(folder)
                    .resolve("04_智慧维修系统采购及服务合同.docx");
            var props=ProjectIntakeProperties.defaults();
            var parser=new ProjectDocxTextParser(new ProjectDocxContainerGuard(new SelectedProjectArchiveReader(props),props));
            ParsedProjectDocument document;
            try(var input=Files.newInputStream(source)) {
                document=parser.parse(ProjectDocumentParserTest.file("contract.docx",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),input);
            }
            String canonical=document.fullText();
            int requiredPhysicalRowSegments=java.util.Arrays.stream(canonical.split("(?<=\\n)"))
                    .mapToInt(row->(row.codePointCount(0,row.length())+239)/240).sum();
            System.out.println("V2_CAPACITY_INPUT scenario="+scenario+" canonicalCodePoints="
                    +canonical.codePointCount(0,canonical.length())+" requiredPhysicalRowSegments="+requiredPhysicalRowSegments);
            UUID parseId=UUID.nameUUIDFromBytes(("v2-capacity-scenario-"+scenario).getBytes(StandardCharsets.UTF_8));
            List<Segment> segments=new ContractFactSegmenter().segment(parseId,canonical);
            assertCoverage(segments,canonical);
            Map<String,String> physicalRows=new LinkedHashMap<>();
            Map<String,Integer> nameCounts=new LinkedHashMap<>();
            for(var table:document.tables())for(var row:table.rows()) {
                if(row.cells().isEmpty())continue;
                var matcher=ROW_ID.matcher(row.cells().get(0).text().strip());
                if(!matcher.matches())continue;
                String rowId=matcher.group();
                assertThat(physicalRows.put(rowId,row.sourceLocator())).as("fixture row identifiers must be unique").isNull();
                if(row.cells().size()>1)nameCounts.merge(row.cells().get(1).text(),1,Integer::sum);
            }
            int expectedRows=scenario==1?12:48;
            assertThat(physicalRows.size()).isEqualTo(expectedRows);
            String rowPrefix=scenario==1?"MX":"QA2";
            for(int n=1;n<=expectedRows;n++)assertThat(physicalRows.containsKey(rowPrefix+"-%03d".formatted(n))).isTrue();

            Map<String,String> identities=new LinkedHashMap<>();
            int typedStart=0;
            for(var typed:document.segments()) {
                int typedEnd=typedStart+typed.text().codePointCount(0,typed.text().length());
                String rowId=physicalRows.entrySet().stream().filter(e->e.getValue().equals(typed.sourceLocator()))
                        .map(Map.Entry::getKey).findFirst().orElse(null);
                if(rowId!=null)for(Segment segment:segments) {
                    if(segment.startOffset()<typedStart || segment.startOffset()>=typedEnd)continue;
                    assertThat(segment.endOffset()).isLessThanOrEqualTo(typedEnd+1);
                    String previous=identities.putIfAbsent(rowId,segment.rowIdentity());
                    if(previous!=null)assertThat(previous.equals(segment.rowIdentity()))
                            .as("fragments of one physical table row keep one identity").isTrue();
                }
                typedStart=typedEnd+1;
            }
            assertThat(identities.keySet()).containsExactlyElementsOf(physicalRows.keySet());
            assertThat(new LinkedHashSet<>(identities.values()).size()).isEqualTo(expectedRows);
            assertThat(identities.values().stream().map(ContractFactSegmenter::lineGroup).distinct().count())
                    .isEqualTo(expectedRows);
            long mappedRows=segments.stream().filter(s->identities.containsValue(s.rowIdentity()))
                    .filter(s->s.lineItemColumns()!=null).map(Segment::rowIdentity).distinct().count();
            assertThat(mappedRows).as("actual V2 rows must receive exact explicit-header column mappings")
                    .isEqualTo(expectedRows);
            segments.stream().filter(s->identities.containsValue(s.rowIdentity())).forEach(s->
                    assertThat(s.lineItemColumns()).containsExactly("rowId","name","quantity","unitPrice","declaredAmount"));
            if(scenario==2)assertThat(nameCounts.values().stream().filter(n->n>1).count())
                    .as("same-name V2 rows remain separate through physical source identity").isGreaterThan(0);
            var stats=new LinkedHashMap<String,Object>();
            stats.put("scenario",scenario);stats.put("physicalLineRows",expectedRows);
            stats.put("planningPolicy",ContractFactBatchPlanner.POLICY_VERSION);
            stats.put("mappedPhysicalLineRows",mappedRows);
            stats.put("canonicalCodePoints",canonical.codePointCount(0,canonical.length()));
            stats.put("segments",segments.size());
            stats.put("duplicateNameGroups",nameCounts.values().stream().filter(n->n>1).count());
            for(Scope scope:List.of(Scope.CORE,Scope.LINE_ITEMS)) {
                var batches=new ContractFactBatchPlanner().plan(segments,scope);
                assertThat(batches.size()).isBetween(1,128);
                List<Segment> flattened=batches.stream().flatMap(List::stream).toList();
                assertThat(flattened.size()).isEqualTo(segments.size());
                for(int n=0;n<segments.size();n++)assertThat(flattened.get(n).equals(segments.get(n)))
                        .as("planning must preserve every immutable segment field and source order").isTrue();
                int maxUnits=0,maxLineRows=0;
                for(var batch:batches) {
                    assertThat(batch.size()).isBetween(1,32);
                    int units=batch.stream().mapToInt(segment->ContractFactBatchPlanner.estimatedOutputUnits(segment,scope)).sum();
                    assertThat(units).isLessThanOrEqualTo(3584);
                    maxUnits=Math.max(maxUnits,units);
                    int rows=(int)batch.stream().filter(s->identities.containsValue(s.rowIdentity())).map(Segment::rowIdentity).distinct().count();
                    maxLineRows=Math.max(maxLineRows,rows);
                    if(scope==Scope.LINE_ITEMS) assertThat(rows).isLessThanOrEqualTo(6);
                }
                assertThat(flattened.get(flattened.size()-1).equals(segments.get(segments.size()-1))).isTrue();
                stats.put(scope.name().toLowerCase()+"Batches",batches.size());
                stats.put(scope.name().toLowerCase()+"MaxEstimatedOutputUnits",maxUnits);
                stats.put(scope.name().toLowerCase()+"MaxLineRowsPerBatch",maxLineRows);
            }
            statistics.add(stats);
        }
        // Fixed ignored destination under the repository; contains counts only, never source text or filenames.
        Path backend=Path.of(System.getProperty("basedir",System.getProperty("user.dir"))).toAbsolutePath().normalize();
        Path repo=backend.getParent();
        assertThat(Files.isRegularFile(repo.resolve("AGENTS.md")) && Files.isRegularFile(backend.resolve("pom.xml"))).isTrue();
        Path evidence=repo.resolve(".runtime/contract-repair-test/repair-20261015/task-a");
        assertThat(evidence.startsWith(repo)).isTrue();
        Files.createDirectories(evidence);
        new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter()
                .writeValue(evidence.resolve("v2-output-capacity.json").toFile(),statistics);
        System.out.println("V2_CAPACITY_STATISTICS="+new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(statistics));
    }

    private void assertCoverage(List<Segment> segments,String canonical) {
        int next=0;
        StringBuilder reconstructed=new StringBuilder();
        for(Segment segment:segments) {
            assertThat(segment.startOffset()).isEqualTo(next);
            assertThat(segment.endOffset()).isGreaterThan(segment.startOffset());
            assertThat(segment.text().codePointCount(0,segment.text().length())).isLessThanOrEqualTo(240);
            reconstructed.append(segment.text());next=segment.endOffset();
        }
        assertThat(next).isEqualTo(canonical.codePointCount(0,canonical.length()));
        assertThat(reconstructed.toString().equals(canonical)).isTrue();
    }
}
