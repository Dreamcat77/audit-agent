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
        String explicit=ProjectNameResolver.resolve(names,texts,null);
        org.slf4j.LoggerFactory.getLogger(ContractAnalysisProjectNames.class).debug("CONTRACT_PROJECT_NAME candidateCount={} sourceCount={} outcome={}",names.size(),texts.size(),explicit==null?"FALLBACK":"SELECTED");
        if(explicit!=null)return explicit;
        var subjects=value.steps().stream().map(ContractAnalysisCase.Step::output).filter(Objects::nonNull)
                .flatMap(output->output.facts().stream()).filter(f->f.type().equals("CONTRACT_NAME"))
                .map(ContractAnalysisCase.Fact::value).map(ContractAnalysisProjectNames::contractSubject).filter(Objects::nonNull).distinct().toList();
        return subjects.size()==1?subjects.get(0):"自动整理项目-"+value.projectId().toString().substring(0,8);
    }

    private static String contractSubject(String title){
        if(title==null)return null;
        String subject=title.strip().replaceFirst("(?:合同书|合同)$","").strip();
        if(subject.equals(title.strip())||subject.length()<6||subject.length()>100
                ||subject.matches(".*[：:。；;\\r\\n].*")||subject.matches(".*(?:委托协议|补充协议|通知书|招标文件|合同模板|合同范本).*"))return null;
        return com.fuquan.audit.common.material.ProjectNameResolver.isProjectName(subject)?subject:null;
    }

    public static boolean matches(ContractAnalysisCase value, String established, String keyword) {
        return keyword == null || keyword.isBlank()
                || normalized(resolve(value, established, List.of())).contains(normalized(keyword));
    }

    private static String normalized(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT);
    }
}
