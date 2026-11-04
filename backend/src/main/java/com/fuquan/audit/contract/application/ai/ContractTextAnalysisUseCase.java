package com.fuquan.audit.contract.application.ai;

import com.fuquan.audit.contract.application.ai.ContractTextAnalysisPort.ContractTextAnalysisMaterial;
import com.fuquan.audit.contract.application.ai.ContractTextAnalysisPort.ContractTextAnalysisRequest;
import com.fuquan.audit.contract.application.project.ProjectApplicationService;
import com.fuquan.audit.contract.application.project.ProjectParsedTextReadService;
import com.fuquan.audit.contract.application.project.SelectedProjectMaterialReadService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.text.Normalizer;
import org.springframework.stereotype.Service;
import com.fuquan.audit.common.observability.PipelineDiagnostics;

@Service
public class ContractTextAnalysisUseCase {

    private final SelectedProjectMaterialReadService selectedMaterials;
    private final ContractTextAnalysisPort analysis;
    private final ContractAiTextRedactor redactor;

    public ContractTextAnalysisUseCase(ProjectApplicationService projects,
                                       ProjectParsedTextReadService parsedTexts,
                                       ContractTextAnalysisPort analysis,
                                       ContractAiTextRedactor redactor) {
        this(new SelectedProjectMaterialReadService(projects, parsedTexts), analysis, redactor);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ContractTextAnalysisUseCase(SelectedProjectMaterialReadService selectedMaterials,
                                       ContractTextAnalysisPort analysis,
                                       ContractAiTextRedactor redactor) {
        this.selectedMaterials = selectedMaterials;
        this.analysis = analysis;
        this.redactor = redactor;
    }

    public ContractTextAnalysisResult analyze(UUID projectId, List<UUID> selectedFileIds) {
        Objects.requireNonNull(projectId, "projectId must not be null");
        Map<UUID, String> realTextByFileId = new HashMap<>();
        List<ContractTextAnalysisMaterial> materials = new ArrayList<>();
        for (SelectedProjectMaterialReadService.SelectedProjectMaterial selected
                : PipelineDiagnostics.run("CONTRACT", "SELECTED_TEXT_READ", projectId,
                        Map.of("fileCount", selectedFileIds == null ? 0 : selectedFileIds.size()), () -> selectedMaterials.read(projectId, selectedFileIds))) {
            var file = selected.file();
            String text = selected.text();
            realTextByFileId.put(file.id(), text);
            materials.add(new ContractTextAnalysisMaterial(
                    file.id(), file.category().name(), redactor.redact(text)));
        }
        ContractTextAnalysisResult result = PipelineDiagnostics.run("CONTRACT", "AI_ANALYSIS", projectId,
                Map.of("fileCount", materials.size(), "inputChars", materials.stream().mapToLong(m -> m.text().length()).sum()),
                () -> analysis.analyzeContractText(new ContractTextAnalysisRequest(projectId, materials)));
        return PipelineDiagnostics.run("CONTRACT", "LOCATOR_VALIDATION", projectId, Map.of("itemCount", result.hints().size()),
                () -> validateLocators(result, realTextByFileId));
    }

    private static ContractTextAnalysisResult validateLocators(
            ContractTextAnalysisResult result, Map<UUID, String> realTextByFileId) {
        if (!result.available()) {
            return result;
        }
        List<ContractTextAnalysisResult.Hint> hints = result.hints().stream()
                .map(hint -> new ContractTextAnalysisResult.Hint(
                        hint.category(), hint.attention(), hint.title(), hint.description(),
                        hint.evidenceRefs(), hint.evidenceItems().stream()
                        .map(item -> new ContractTextAnalysisResult.EvidenceItem(
                                item.fileId(), item.status(), item.excerpt(),
                                hasUniqueNormalizedMatch(
                                        realTextByFileId.get(item.fileId()), item.excerpt())))
                        .toList()))
                .toList();
        return new ContractTextAnalysisResult(true, result.summaries(), hints,
                result.model(), result.durationMs(), result.modelCalls(), result.monetaryObservation());
    }

    private static boolean hasUniqueNormalizedMatch(String source, String excerpt) {
        if (source == null || excerpt == null || excerpt.isBlank()) {
            return false;
        }
        String normalizedSource = normalizeVisibleText(source);
        String normalizedExcerpt = normalizeVisibleText(excerpt);
        if (normalizedExcerpt.isEmpty()) {
            return false;
        }
        int first = normalizedSource.indexOf(normalizedExcerpt);
        return first >= 0 && normalizedSource.lastIndexOf(normalizedExcerpt) == first;
    }

    private static String normalizeVisibleText(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).replaceAll("\\s+", "");
    }

}
