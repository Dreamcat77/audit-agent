package com.fuquan.audit.contract.application.analysis;

import com.fuquan.audit.contract.application.clause.*;
import com.fuquan.audit.contract.domain.project.ProjectFileCategory;
import java.util.*;
import static com.fuquan.audit.contract.application.analysis.ContractAnalysisCase.*;

/** Persists the same deterministic document checks used by formal review. */
final class ContractAnalysisDocumentRules {
    private ContractAnalysisDocumentRules() {}
    static Output evaluate(Source contract, List<Source> files, List<Step> steps, Map<UUID, String> texts,
            ContractAnalysisRelations relations) {
        var materials = new ArrayList<ContractClauseMaterial>();
        var related=relations.pairs(files,steps).stream().filter(p->p.contract().fileId().equals(contract.fileId())).map(p->p.support().fileId()).collect(java.util.stream.Collectors.toSet());
        long contracts=files.stream().filter(f->relations.role(f,steps).equals("CONTRACT")).count();
        for (var file : files) if (texts.containsKey(file.fileId())) {
            String role = relations.role(file, steps);
            if (!file.fileId().equals(contract.fileId()) && role.equals("CONTRACT")) continue;
            if (!file.fileId().equals(contract.fileId()) && !related.contains(file.fileId())
                    && !(contracts==1 && role.equals("OTHER") && relations.compatible(contract,file,steps))) continue;
            ProjectFileCategory category = switch (role) {
                case "CONTRACT" -> ProjectFileCategory.CONTRACT_BODY;
                case "TENDER" -> ProjectFileCategory.TENDER_DOCUMENT;
                case "AWARD" -> ProjectFileCategory.AWARD_NOTICE;
                default -> ProjectFileCategory.OTHER;
            };
            materials.add(new ContractClauseMaterial(file.fileId(), file.name(), category, texts.get(file.fileId())));
        }
        var checks = new ArrayList<Check>();
        var findings = new ArrayList<Finding>();
        var terms = new ContractTermClauseAnalyzer().analyze(materials);
        for (var result : terms.deterministicChecks()) {
            var evidence = result.sources().stream().map(s -> evidence(files, texts, s.fileId(), s.excerpt())).filter(Objects::nonNull).distinct().toList();
            if (evidence.isEmpty()) continue;
            boolean limited=contract.reasonCode()!=null || files.stream().filter(f->result.sources().stream().anyMatch(s->s.fileId().equals(f.fileId()))).anyMatch(f->f.reasonCode()!=null);
            boolean absence=Set.of("SIGN_DATE_COMPLETENESS","TERMINATION_COMPLETENESS","ATTACHMENT_REFERENCE_MATCH","TEMPLATE_STRUCTURE_COMPARISON").contains(result.type());
            if(limited && absence) {
                checks.add(new Check(result.type(),"NOT_EVALUATED","材料读取范围不完整，本项仅保留已读取内容，暂不判断缺失。",evidence));
                continue;
            }
            boolean warning = Set.of("REVIEW", "ATTENTION").contains(result.status());
            String status = result.status().equals("NORMAL") ? "PASS" : warning ? "FAIL" : "NOT_EVALUATED";
            checks.add(new Check(result.type(), status, result.summary(), evidence));
            if (warning) findings.add(new Finding(result.type(), "WARNING", title(result.type()), result.summary(),
                    result.recommendation(), evidence, "DETERMINISTIC_DOCUMENT"));
        }
        var outputs = steps.stream().filter(s -> contract.fileId().equals(s.fileId()) && s.output() != null).map(Step::output).toList();
        var amount = ContractAnalysisRules.unique(outputs.stream().flatMap(o -> o.facts().stream()).toList(), "TOTAL_AMOUNT")
                .flatMap(f -> com.fuquan.audit.common.domain.RmbAmountParser.parse(f.value()));
        if (amount.isPresent()) {
            var breach = new BreachClauseAnalyzer().analyze(amount.get(), materials);
            for (var item : breach.items()) if (item.sourceFileId().equals(contract.fileId())) {
                var evidence = evidence(files, texts, item.sourceFileId(), item.excerpt());
                if (evidence == null || item.correspondingAmount() == null) continue;
                String description = "违约金每" + unit(item.frequency()) + " " + item.correspondingAmount().toPlainString() + " 元"
                        + (item.capAmount() == null ? "" : "；约定上限 " + item.capAmount().toPlainString() + " 元")
                        + (item.actualPenaltyAmount() == null ? "；未列明实际违约期间，暂不计算累计金额。" : "；按材料实际期间累计 " + item.actualPenaltyAmount().toPlainString() + " 元。");
                boolean over = item.actualPenaltyAmount() != null && item.capAmount() != null && item.actualPenaltyAmount().compareTo(item.capAmount()) > 0;
                checks.add(new Check("BREACH_AMOUNT_CAP", over ? "FAIL" : item.actualPenaltyAmount() != null && item.capAmount() != null ? "PASS" : "NOT_EVALUATED", description, List.of(evidence)));
                if (over) findings.add(new Finding("BREACH_AMOUNT_CAP", "WARNING", "违约金累计金额超过约定上限", description,
                        "关注计费期间、基数与合同上限。", List.of(evidence), "DETERMINISTIC_DOCUMENT"));
            }
        }
        return new Output("OTHER", "GENERAL", true, List.of(), List.of(), checks, findings, 0, null, "document-rules-v1");
    }
    private static String unit(String frequency) { return switch (frequency) {case "DAY" -> "日"; case "WEEK" -> "周"; case "MONTH" -> "月"; default -> "次";}; }
    private static String title(String code) { return switch (code) {
        case "TEMPLATE_STRUCTURE_COMPARISON" -> "合同与招标模板结构存在差异";
        case "COPY_COUNT_REQUIREMENT_COMPARISON" -> "合同份数需要关注";
        case "ATTACHMENT_REFERENCE_MATCH" -> "合同引用附件待补齐";
        default -> ContractFindingSupport.title(code);
    }; }
    private static Evidence evidence(List<Source> files, Map<UUID, String> texts, UUID id, String quote) {
        var source = files.stream().filter(f -> f.fileId().equals(id)).findFirst().orElse(null);
        String text = texts.get(id);
        if (source == null || text == null || quote == null || quote.isBlank()) return null;
        String excerpt = quote.substring(0, Math.min(quote.length(), 4000));
        int start = text.indexOf(excerpt);
        return start < 0 ? null : new Evidence(id, source.sha256(), source.parseResultId(), source.parseVersion(), source.textSha256(), start, start + excerpt.length(), excerpt);
    }
}
