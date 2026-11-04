package com.fuquan.audit.contract.application.analysis;

import com.fuquan.audit.contract.domain.*;
import java.util.*;
import org.springframework.stereotype.Component;
import static com.fuquan.audit.contract.application.analysis.ContractAnalysisCase.*;

/** Cross-file scope needs an exact referenced contract number, or one unique contract per project. */
@Component
public class ContractAnalysisRelations {
    public record Pair(Source contract,Source support) {
        public String id(){return "relation/"+contract.fileId()+"/"+support.fileId();}
    }
    private List<Fact> facts(UUID fileId,List<Step> work){return work.stream().filter(s->fileId.equals(s.fileId())&&s.output()!=null&&!s.kind().startsWith("CROSS"))
        .flatMap(s->s.output().facts().stream()).filter(f->f.evidence().stream().allMatch(e->e.fileId().equals(fileId))).distinct().toList();}
    public List<Pair> pairs(List<Source> files,List<Step> work){
        var contracts=files.stream().filter(s->s.status()==SourceState.READY&&role(s,work).equals("CONTRACT")).toList();var result=new ArrayList<Pair>();
        for(var contract:contracts)for(var support:files){
            if(contract.fileId().equals(support.fileId())||support.status()!=SourceState.READY||role(support,work).equals("CONTRACT")||role(support,work).equals("OTHER"))continue;
            var cf=facts(contract.fileId(),work);var sf=facts(support.fileId(),work);
            var no=ContractAnalysisRules.unique(cf,"CONTRACT_NO");var ref=ContractAnalysisRules.unique(sf,"CONTRACT_REFERENCE");
            boolean exactContract=no.isPresent()&&ref.isPresent()&&same(no.get(),ref.get())&&contracts.stream().filter(c->ContractAnalysisRules.unique(facts(c.fileId(),work),"CONTRACT_NO").map(f->same(f,ref.get())).orElse(false)).count()==1;
            var pn=ContractAnalysisRules.unique(cf,"PROJECT_NO");var sp=ContractAnalysisRules.unique(sf,"PROJECT_NO");
            boolean uniqueProject=sf.stream().noneMatch(f->f.type().equals("CONTRACT_REFERENCE"))&&pn.isPresent()&&sp.isPresent()&&same(pn.get(),sp.get())&&contracts.stream().filter(c->ContractAnalysisRules.unique(facts(c.fileId(),work),"PROJECT_NO").map(f->same(f,pn.get())).orElse(false)).count()==1;
            var name=ContractAnalysisRules.unique(cf,"PROJECT_NAME");var supportName=ContractAnalysisRules.unique(sf,"PROJECT_NAME");
            boolean uniqueName=sf.stream().noneMatch(f->f.type().equals("CONTRACT_REFERENCE"))
                    &&!(pn.isPresent()&&sp.isPresent()&&!same(pn.get(),sp.get()))
                    &&name.isPresent()&&supportName.isPresent()&&same(name.get(),supportName.get())
                    &&contracts.stream().filter(c->ContractAnalysisRules.unique(facts(c.fileId(),work),"PROJECT_NAME").map(f->same(f,name.get())).orElse(false)).count()==1;
            if(exactContract||uniqueProject||uniqueName)result.add(new Pair(contract,support));
        }
        return result.stream().limit(12).toList();
    }
    boolean compatible(Source contract,Source support,List<Step> work) {
        for(String type:List.of("PROJECT_NO","PROJECT_NAME")) {
            var own=ContractAnalysisRules.unique(facts(contract.fileId(),work),type);
            var other=ContractAnalysisRules.unique(facts(support.fileId(),work),type);
            if(own.isPresent()&&other.isPresent()&&!same(own.get(),other.get()))return false;
        }
        var no=ContractAnalysisRules.unique(facts(contract.fileId(),work),"CONTRACT_NO");
        var ref=ContractAnalysisRules.unique(facts(support.fileId(),work),"CONTRACT_REFERENCE");
        return ref.isEmpty()||no.isPresent()&&same(no.get(),ref.get());
    }
    private boolean same(Fact a,Fact b){return ContractAnalysisEvidence.compact(a.value()).equals(ContractAnalysisEvidence.compact(b.value()));}
    public String role(Source source,List<Step> work){
        // Filename is a classification hint only, never contract facts or authority.
        var category=new com.fuquan.audit.contract.application.project.ProjectFileClassifier().classify(source.name());
        String named=switch(category){
            case CONTRACT_BODY->"CONTRACT";case AWARD_NOTICE->"AWARD";
            case TENDER_DOCUMENT->"TENDER";case BID_RESPONSE->"BID";
            case SUPPLEMENT->source.name().matches("(?is).*(补充协议|变更协议|supplement|澄清).*" )?"SUPPLEMENT":"OTHER";
            default->"OTHER";
        };
        if(!named.equals("OTHER"))return named;
        var roles=work.stream().filter(s->source.fileId().equals(s.fileId())&&s.output()!=null
                &&Set.of("FILE","FRAGMENT","FOCUSED").contains(s.kind()))
            .map(s->s.output().role()).filter(r->!r.equals("OTHER")).distinct().toList();
        if(roles.size()==1)return roles.get(0);
        if(roles.size()>1)return "OTHER";
        var recalled=work.stream().filter(s->source.fileId().equals(s.fileId())&&s.output()!=null&&s.kind().equals("RECALL"))
            .map(s->s.output().role()).filter(r->!r.equals("OTHER")).distinct().toList();
        return recalled.size()==1?recalled.get(0):"OTHER";
    }
    public Output evaluate(Pair pair,List<Step> work){
        var checks=new ArrayList<Check>();var findings=new ArrayList<Finding>();
        var cf=facts(pair.contract().fileId(),work);var sf=facts(pair.support().fileId(),work);
        if(role(pair.support(),work).equals("AWARD")){
            var total=ContractAnalysisRules.unique(cf,"TOTAL_AMOUNT");var award=ContractAnalysisRules.unique(sf,"AWARD_AMOUNT");
            if(total.isPresent()&&award.isPresent()&&ContractAnalysisRules.money(total.get().value()).isPresent()&&ContractAnalysisRules.money(award.get().value()).isPresent())
                ContractAnalysisRules.compare(checks,findings,F02RuleCodes.AWARD_AMOUNT_MATCH,"合同与关联中标金额核对",ContractAnalysisRules.money(total.get().value()).get(),ContractAnalysisRules.money(award.get().value()).get(),ContractAnalysisRules.combine(total.get(),award.get()));
            else checks.add(ContractAnalysisRules.missing(F02RuleCodes.AWARD_AMOUNT_MATCH,"关联材料的合同总价或中标金额依据不足，未执行金额比较"));
            var party=ContractAnalysisRules.unique(cf,"PARTY_B");var supplier=ContractAnalysisRules.unique(sf,"AWARD_SUPPLIER");
            if(party.isPresent()&&supplier.isPresent()){
                boolean match=ContractArithmetic.supplierName(party.get().value()).equals(ContractArithmetic.supplierName(supplier.get().value()));
                var e=ContractAnalysisRules.combine(party.get(),supplier.get());String message="合同乙方“"+party.get().value()+"”与关联中标供应商“"+supplier.get().value()+"”"+(match?"名称一致":"名称不同，请核对主体或更名依据");
                checks.add(new Check(F02RuleCodes.AWARD_SUPPLIER_MATCH,match?"PASS":"FAIL",message,e));
                if(!match)findings.add(new Finding(F02RuleCodes.AWARD_SUPPLIER_MATCH,"WARNING","合同乙方与中标供应商名称不同",message,"核对名称变更或合同承接的有效依据。",e,"DETERMINISTIC_F02"));
            }
        }
        return new Output("OTHER","GENERAL",true,List.of(),List.of(),checks,findings,0,null,"contract-relations-v1");
    }
    public List<ContractAnalysisEvidence.Scope> scopes(Pair pair,List<Step> work,Map<UUID,String> texts){
        var result=new ArrayList<ContractAnalysisEvidence.Scope>();
        for(var source:List.of(pair.contract(),pair.support())){
            String text=texts.get(source.fileId());
            if(text.length()<=5000){result.add(new ContractAnalysisEvidence.Scope(source,text,0,text.length()));continue;}
            var relevant=work.stream().filter(s->source.fileId().equals(s.fileId())&&s.output()!=null&&!s.kind().startsWith("CROSS"))
                .map(Step::output).toList();
            // A conflicting sentence may occur only in a finding, rather than the clause summary.
            // Keep those source ranges first, then clauses and facts, within the same fixed budget.
            var candidates=java.util.stream.Stream.of(
                relevant.stream().flatMap(o->o.findings().stream()).flatMap(f->f.evidence().stream()),
                relevant.stream().flatMap(o->o.clauses().stream()).flatMap(f->f.evidence().stream()),
                relevant.stream().flatMap(o->o.facts().stream()).flatMap(f->f.evidence().stream()))
                .flatMap(s->s).filter(e->e.fileId().equals(source.fileId())).distinct().toList();
            List<int[]> ranges=new ArrayList<>();
            for(var e:candidates){
                if(e.end()>text.length()||!text.substring(e.start(),e.end()).equals(e.excerpt()))continue;
                var trial=new ArrayList<int[]>(ranges);trial.add(new int[]{e.start(),e.end()});trial=merge(trial);
                if(trial.stream().mapToInt(r->r[1]-r[0]).sum()<=5000)ranges=trial;
            }
            for(var range:ranges)result.add(new ContractAnalysisEvidence.Scope(source,text,range[0],range[1]));
        }
        return result.stream().distinct().toList();
    }
    private static ArrayList<int[]> merge(List<int[]> input){
        var sorted=input.stream().sorted(Comparator.comparingInt(r->r[0])).toList();var merged=new ArrayList<int[]>();
        for(var r:sorted){if(merged.isEmpty()||r[0]>merged.get(merged.size()-1)[1])merged.add(r.clone());
            else merged.get(merged.size()-1)[1]=Math.max(merged.get(merged.size()-1)[1],r[1]);}
        return merged;
    }
}
