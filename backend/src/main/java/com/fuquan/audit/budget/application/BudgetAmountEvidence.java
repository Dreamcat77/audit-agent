package com.fuquan.audit.budget.application;

import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Fact;
import java.util.regex.Pattern;

/** A verbatim project total/unit price is not a line's declared or approved amount. */
public final class BudgetAmountEvidence {
    private BudgetAmountEvidence() {}
    public static boolean isOtherAmount(Fact fact) {
        if(!fact.field().endsWith("Amount"))return false;
        String excerpt=fact.excerpt().strip();
        return Pattern.compile("^(?:项目预算金额|项目预算总额|项目总预算|采购预算金额|预算总金额|预算总额|预算金额|预算申报总额|申报合计|批复控制数|批复总额|单价|合计|总计)\\s*[:：为]?\\s*"
            +Pattern.quote(fact.value())+"[。.]?$").matcher(excerpt).matches();
    }
}
