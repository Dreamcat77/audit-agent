package com.fuquan.audit.contract.application.analysis;

import java.util.*;
import java.util.regex.Pattern;

/** Reject an explicit reversal of a cited actor; silence is not proof of contradiction. */
final class ContractRoleSupport {
    private ContractRoleSupport() {}
    private static final String[] ROLES={"甲方","乙方"};
    private static final String RIGHTS="(?:起诉|诉讼|仲裁|救济|维权|追责)";
    static boolean supports(String claim,List<String> quotes){
        // Work on each unchanged cited sentence. Do not consult a remote clause
        // or the other party's general role in the batch to invent support.
        for(String role:ROLES){
            String other=role.equals("甲方")?"乙方":"甲方";
            if(restricted(claim,role)
                    &&quotes.stream().anyMatch(q->restricted(q,other))
                    &&quotes.stream().noneMatch(q->restricted(q,role)))return false;
            if(debtor(claim,role)
                    &&quotes.stream().anyMatch(q->debtor(q,other))
                    &&quotes.stream().noneMatch(q->debtor(q,role)))return false;
        }
        return true;
    }
    private static boolean restricted(String text,String role){
        return Pattern.compile(role+"[^甲乙。；;\\n]{0,12}(?:不得|不能|无法|无权|放弃|丧失|失去)[^甲乙。；;\\n]{0,22}"+RIGHTS)
                .matcher(text).find();
    }
    private static boolean debtor(String text,String role){
        // A recipient ('向甲方赔偿') is not the debtor. A reduced liability cap
        // may impair recovery, but does not make the creditor owe that amount.
        return Pattern.compile(role+"[^甲乙不未无。；;\\n]{0,8}(?:须|应|必须|将|需|可能)?承担[^甲乙。；;\\n]{0,16}(?:赔偿|债务|违约金)")
                .matcher(text).find();
    }
}
