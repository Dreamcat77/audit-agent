package com.fuquan.audit.procurement.application;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.regex.Pattern;

/** Extract only explicit final RMB bid totals; do not sum technical tables or infer prices with AI. */
public final class ProcurementBidQuotationExtractor {
    private ProcurementBidQuotationExtractor() {}
    private static final String FINANCIAL_DIGIT = "零壹贰貳叁參叄肆伍陆陸柒捌玖";
    private static final Pattern TOTAL = Pattern.compile("(?:(?:投标|响应|含税)?报价(?:总金额|总额|总价|合计|金额)?|投标总报价|投标总价|响应总价|含税总价|价税合计)(?:[（(]含税[）)])?(?:为|[：:]|\\h)*(?:人民币)?\\h*(.+?(?:元|圆|圓)["+FINANCIAL_DIGIT+"角分整正\\h]*(?:[（(]大写[:：].+?[）)])?)");
    public static BigDecimal extract(String text) {
        var matcher=TOTAL.matcher(text); var values=new LinkedHashSet<BigDecimal>();
        while(matcher.find()) {
            var parsed=com.fuquan.audit.common.domain.RmbAmountParser.parse(matcher.group(1));
            if(parsed.isEmpty())return null;
            var value=parsed.get().stripTrailingZeros();
            if(value.signum()<=0 || value.precision()-value.scale()>16)return null;
            values.add(value);
        }
        return values.size()==1?values.iterator().next():null;
    }
}
