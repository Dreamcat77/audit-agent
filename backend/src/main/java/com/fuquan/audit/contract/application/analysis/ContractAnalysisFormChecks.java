package com.fuquan.audit.contract.application.analysis;

import com.fuquan.audit.contract.domain.ContractPartyName;
import java.util.*;
import java.util.regex.*;
import static com.fuquan.audit.contract.application.analysis.ContractAnalysisCase.*;

/** Explicitly unfilled positions are evidence of a drafting issue, not missing model output. */
final class ContractAnalysisFormChecks {
    private ContractAnalysisFormChecks() {}
    static void evaluate(Source source,String text,List<Fact> facts,List<Check> checks,List<Finding> findings) {
        party(source,text,facts,checks,findings,"PARTY_A","甲方","(?:甲方|定作方|定做方|委托方|采购人|发包方|出租方|买方)");
        party(source,text,facts,checks,findings,"PARTY_B","乙方","(?:乙方|承揽方|受托方|供应商|承包方|承租方|卖方)");
        if(facts.stream().noneMatch(f->f.type().equals("TOTAL_AMOUNT")&&ContractAnalysisRules.money(f.value()).isPresent())) {
            var amount=Pattern.compile("(?m)^.*(?:合同(?:金额|总价)|人民币金额)[（(]?(?:小写)?[）)]?\\h*[:：]\\h*[¥￥]?\\h*[_＿—\\h]*(?:[（(]包含税费[）)])?\\h*$").matcher(text);
            if(amount.find())issue(source,text,amount.start(),amount.end(),"CONTRACT_AMOUNT_COMPLETENESS","合同金额未填写", "合同金额：无。合同总价留空，暂不能核对金额及明细合计。","补齐合同总价及对应金额大写。",checks,findings);
        }
        var payment=new ArrayList<Evidence>();var breach=new ArrayList<Evidence>();var warranty=new ArrayList<Evidence>();
        var lines=Pattern.compile("(?m)^.+$").matcher(text);
        while(lines.find()) {
            String line=lines.group();
            if(line.matches(".*(?:应支付|支付|定金|尾款)[\\h_＿—]*(?:[%％]|即人民币[（(]大写[）)])[\\s\\S]*")
                    ||line.matches("[\\h_＿—]*[%％]尾款.*"))payment.add(evidence(source,text,lines.start(),lines.end()));
            if(line.matches(".*(?:货款金额的|逾期金额的|合同总计金额的)[\\h_＿—]+(?:向|[%％]).*"))breach.add(evidence(source,text,lines.start(),lines.end()));
            if(line.matches(".*(?:提供|保用期[：:])[\\h_＿—]+年的(?:保用期|质保期|保修期).*"))warranty.add(evidence(source,text,lines.start(),lines.end()));
        }
        issue(payment,"PAYMENT_TERMS_UNFILLED","付款金额或比例未填写","付款条款中的金额或比例留空，无法确定应付款。","补齐各期付款金额、比例及付款期限。",checks,findings);
        issue(breach,"BREACH_TERMS_UNFILLED","违约金比例未填写","违约责任条款的计算比例留空，暂不能计算违约金。","补齐各违约事件对应的比例和期限。",checks,findings);
        issue(warranty,"WARRANTY_TERM_UNFILLED","保用期限未填写","保用期限留空，无法确定免费维修责任的持续时间。","补齐保用或质保期限。",checks,findings);
    }
    private static void party(Source source,String text,List<Fact> facts,List<Check> checks,List<Finding> findings,String type,String label,String names) {
        if(facts.stream().anyMatch(f->f.type().equals(type)&&ContractPartyName.isPresent(f.value())&&f.evidence().stream().allMatch(e->e.fileId().equals(source.fileId()))))return;
        var matcher=Pattern.compile("(?m)(?:^|(?<=\\t)|(?<=\\|))\\h*"+names+"\\h*(?:[（(][^）)\\r\\n]{0,24}[）)]\\h*){0,3}[:：]([^\\r\\n\\t|]*)").matcher(text);
        var blank=new ArrayList<Evidence>();
        while(matcher.find()) {
            String value=matcher.group(1).strip();
            // Signature tables put the next blank caption in the same cell.
            String name=value.split("(?:住所|地址|法定代表人|委托代理人|电话|传真|开户银行|账号|邮政编码)\\h*[:：]",2)[0].strip();
            if(ContractPartyName.isPresent(name))return;
            blank.add(evidence(source,text,matcher.start(),matcher.end()));
        }
        issue(blank,type+"_NAME_COMPLETENESS",label+"名称未填写",label+"：无。"+label+"名称未填写。","补齐"+label+"的完整名称，签署前核对主体。",checks,findings);
    }
    private static Evidence evidence(Source s,String text,int start,int end) {
        int to=Math.min(end,start+4000);
        return new Evidence(s.fileId(),s.sha256(),s.parseResultId(),s.parseVersion(),s.textSha256(),start,to,text.substring(start,to));
    }
    private static void issue(Source s,String text,int start,int end,String code,String title,String description,String suggestion,List<Check> checks,List<Finding> findings) {
        issue(List.of(evidence(s,text,start,end)),code,title,description,suggestion,checks,findings);
    }
    private static void issue(List<Evidence> evidence,String code,String title,String description,String suggestion,List<Check> checks,List<Finding> findings) {
        if(evidence.isEmpty())return;
        var used=evidence.stream().distinct().limit(3).toList();
        checks.add(new Check(code,"FAIL",description,used));
        findings.add(new Finding(code,"WARNING",title,description,suggestion,used,"DETERMINISTIC_FORM"));
    }
}
