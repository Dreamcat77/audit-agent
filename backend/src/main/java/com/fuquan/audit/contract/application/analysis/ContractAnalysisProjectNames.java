package com.fuquan.audit.contract.application.analysis;

import com.fuquan.audit.common.material.ProjectNameResolver;
import com.fuquan.audit.contract.application.project.AutomaticProjectMetadataService;
import java.text.Normalizer;
import java.util.*;

/** One display-name policy for reports and history search; never writes project metadata. */
public final class ContractAnalysisProjectNames {
    private ContractAnalysisProjectNames() {}

    public static String resolve(ContractAnalysisCase value, String established, List<String> texts) {
        var names = new ArrayList<String>();
        if (established != null && !AutomaticProjectMetadataService.isGeneratedName(established)) names.add(established);
        value.steps().stream().map(ContractAnalysisCase.Step::output).filter(Objects::nonNull)
                .flatMap(output -> output.facts().stream())
                .filter(fact -> fact.type().equals("PROJECT_NAME")).map(ContractAnalysisCase.Fact::value).forEach(names::add);
        return ProjectNameResolver.resolve(names, texts, "自动整理项目-" + value.projectId().toString().substring(0, 8));
    }

    public static boolean matches(ContractAnalysisCase value, String established, String keyword) {
        return keyword == null || keyword.isBlank()
                || normalized(resolve(value, established, List.of())).contains(normalized(keyword));
    }

    private static String normalized(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT);
    }
}
