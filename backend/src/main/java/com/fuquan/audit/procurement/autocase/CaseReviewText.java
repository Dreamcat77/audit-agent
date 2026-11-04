package com.fuquan.audit.procurement.autocase;

/** Read-only wording for system-authored checks; evidence and business values stay unchanged. */
final class CaseReviewText {
    private CaseReviewText() {}
    static String identityLabel(String type) {
        return switch(type) {case "projectName"->"项目名称";case "projectNo"->"项目编号";case "purchaserName"->"采购单位";default->"材料信息";};
    }
    static String explanation(AuditCase.ReviewCheck check) {
        if(!check.code().startsWith("IDENTITY_"))return check.explanation();
        String type=check.code().substring("IDENTITY_".length());
        if(check.status().equals("UNKNOWN"))return "本批材料尚不足以核对"+identityLabel(type)+"，不据此认定材料之间存在冲突。";
        String prefix=type+"：";
        if(check.explanation().startsWith(prefix))return identityLabel(type)+check.explanation().substring(type.length())
                .replace("；缺少该字段的文件不作为冲突依据","；未载明此信息的材料不参与本项比对");
        return check.explanation();
    }
}
