package com.fuquan.audit.procurement.autocase;

import com.fuquan.audit.blacklist.application.port.BlacklistCheckPort;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.fuquan.audit.procurement.autocase.AuditCase.*;

/** Reuses durable blacklist alarms; names are checked once per immutable batch. */
@Service
public class CaseBlacklistReview {
    private final BlacklistCheckPort blacklist;
    private final Clock clock;
    private final com.fuquan.audit.common.application.OperationAuditService audit;
    public CaseBlacklistReview(BlacklistCheckPort blacklist, Clock clock,com.fuquan.audit.common.application.OperationAuditService audit) {this.blacklist=blacklist;this.clock=clock;this.audit=audit;}
    @Transactional
    public ModuleReview check(UUID id, List<Document> documents,com.fuquan.audit.security.domain.CurrentUser user) {
        var evidence = new TreeMap<String,List<SourceEvidence>>();
        var context = CaseReviewContext.select("AGENT_PRECHECK",documents);
        for (var doc : documents) for (var fact : CaseFactPolicy.facts(doc,"supplierName")) {
            var refs=CaseReviewContext.factEvidence(context,doc,fact);
            if(refs.isEmpty() && doc.segments().stream().anyMatch(s->s.locator().equals(fact.locator())&&s.text().contains(fact.excerpt())))
                refs=List.of(new SourceEvidence("BL-"+AuditCaseService.hash(doc.id()+fact.locator()+fact.excerpt()).substring(0,24),doc.id(),doc.name(),doc.sha256(),fact.locator(),fact.excerpt()));
            if (!refs.isEmpty()) evidence.computeIfAbsent(fact.value().strip(),k->new ArrayList<>()).addAll(refs);
        }
        var commands=new ArrayList<BlacklistCheckPort.CheckCommand>();
        for (var supplier:evidence.keySet()) commands.add(new BlacklistCheckPort.CheckCommand(id.toString(), commands.size()+1, null, supplier, null));
        var findings=new ArrayList<ReviewFinding>();var checks=new ArrayList<ReviewCheck>();
        for (var result:blacklist.checkBatch(commands)) {
            var refs=evidence.get(result.supplierName());
            boolean exact=result.matches().stream().anyMatch(m->"EXACT".equals(m.matchType()));
            String explanation=result.matched()?result.supplierName()+(exact?"命中":"名称接近")+"黑名单："
                    +result.matches().stream().map(m->m.matchedName()+"（"+m.blacklistType()+"；"+m.blacklistReason()+"）").distinct().collect(java.util.stream.Collectors.joining("；"))
                    :result.supplierName()+"未命中当前已接入的有效黑名单。";
            checks.add(new ReviewCheck("SUPPLIER_BLACKLIST",exact?"BLOCKED":result.matched()?"REVIEW":"NO_MATCH",explanation,refs));
            if(result.matched()) findings.add(new ReviewFinding("SUPPLIER_BLACKLIST",exact?"CRITICAL":"WARNING",
                    exact?"供应商命中黑名单，暂停推荐":"供应商名称相近，需关注",explanation,
                    exact?"已记录报警；该供应商不纳入本批推荐名单。":"名称相近尚不能认定为同一主体，不据此自动拦截。",refs,"BLACKLIST_DATABASE"));
            if(result.persistedAlert()!=null) audit.success("AUTO_CASE_BLACKLIST_ALERT",user,"BLACKLIST_ALERT",result.persistedAlert().id().toString(),
                    CaseFactPolicy.report(id,documents).projectName(),null,Map.of("caseId",id.toString()),"采购材料命中黑名单，已生成站内通知");
        }
        return new ModuleReview(evidence.isEmpty()?"DATA_INSUFFICIENT":"COMPLETED",AuditCaseService.hash(documents.toString()),
                "case-blacklist-v1",null,clock.instant(),clock.instant(),context.scope(),List.of(),checks,findings,0,null);
    }
}
