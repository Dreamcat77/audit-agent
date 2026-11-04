package com.fuquan.audit.budget.application;

import com.fuquan.audit.common.material.application.port.DocumentTextPort;
import com.fuquan.audit.common.observability.PipelineDiagnostics;
import java.util.*;

/** Only allowlisted reasons and fixed prose leave the processing boundary. */
public final class BudgetProcessingOutcome {
    private BudgetProcessingOutcome() {}
    private static final Map<String,String> REASONS=Map.ofEntries(
        Map.entry("BUDGET_AI_ROW_AMBIGUOUS","已读取材料，但部分信息无法准确对应到原文中的具体条目，本次未形成可靠的分析结果。"),
        Map.entry("BUDGET_AI_EVIDENCE_INVALID","智能分析返回的信息未能与材料原文核对一致，本次未形成可靠的分析结果。"),
        Map.entry("BUDGET_AI_AMOUNT_LABEL_MISMATCH","智能分析未能准确区分原文中的申报金额和批复金额，本次未形成可靠的分析结果。"),
        Map.entry("MATERIAL_AI_UNGROUNDED","智能分析返回的信息未能与材料原文核对一致，本次未形成可靠的分析结果。"),
        Map.entry("MATERIAL_AI_INVALID_RESULT","智能分析返回的结果不完整或格式异常，本次未形成可用结果。"),
        Map.entry("MATERIAL_AI_FAILED","智能分析服务未能正常返回结果，本次处理已结束。"),
        Map.entry("MATERIAL_AI_UNAVAILABLE","智能分析服务暂不可用，本次未形成分析结果。"),
        Map.entry("MATERIAL_AI_BUSY","智能分析服务繁忙，本次未形成分析结果。"),
        Map.entry("DATABASE_ERROR","分析结果未能完成保存，请联系管理员核查，避免重复处理。"),
        Map.entry("DOWNSTREAM_CONNECT_ERROR","暂时无法连接分析服务，本次未形成分析结果。"),
        Map.entry("DOCUMENT_TEXT_EMPTY","未能从材料中读取到文字，本次未形成分析结果，请核对文件内容及扫描清晰度。"),
        Map.entry("DOCUMENT_ENCRYPTED","材料已加密，无法读取内容，请使用获准解密的文件。"),
        Map.entry("DOCUMENT_CONTAINER_INVALID","文件损坏或文件内容与格式不符，无法完成读取。"),
        Map.entry("DOCUMENT_SIGNATURE_INVALID","文件内容与格式不符，无法完成读取。"),
        Map.entry("PAGE_LIMIT_EXCEEDED","材料页数超过当前处理范围，本次未形成完整分析结果。"),
        Map.entry("TEXT_LIMIT_EXCEEDED","材料文字量超过当前处理范围，本次未形成完整分析结果。"),
        Map.entry("BUDGET_RESULT_LIMIT","材料明细超过当前处理范围，本次未形成完整分析结果。"),
        Map.entry("MATERIAL_CALL_TIMEOUT","等待智能分析结果超时，本次处理已结束，其他材料不受影响。"),
        Map.entry("MATERIAL_TIME_LIMIT","材料分析超过本次处理时限，未形成完整结果，其他材料不受影响。"),
        Map.entry("MATERIAL_QUEUE_TIMEOUT","等待分析服务超过本次排队时限，未形成分析结果，其他材料不受影响。")
    );
    public static String reason(Throwable failure) {
        var seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        for(Throwable cause=failure;cause!=null&&seen.size()<12&&seen.add(cause);cause=cause.getCause()) {
            String code=cause instanceof DocumentTextPort.ReadFailure read?read.code():cause.getMessage();
            if(REASONS.containsKey(Objects.toString(code,"")))return code;
        }
        String code=Objects.toString(PipelineDiagnostics.failure(failure).get("reason"),"");
        return REASONS.containsKey(code)?code:"BUDGET_PROCESSING_UNCONFIRMED";
    }
    public static String description(String status,String reason) {
        if(!Set.of("FAILED","UNKNOWN").contains(status))return null;
        return REASONS.getOrDefault(Objects.toString(reason,""),
            "本次处理已结束，尚未取得可核实的分析结果，具体原因需要管理员查看关联日志；不能据此判断材料不足或预算没有问题。");
    }
}
