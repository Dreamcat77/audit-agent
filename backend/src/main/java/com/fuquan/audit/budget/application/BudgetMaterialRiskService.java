package com.fuquan.audit.budget.application;
import static com.fuquan.audit.budget.application.BudgetMaterialRisk.*;
import com.fuquan.audit.budget.application.port.*;
import com.fuquan.audit.common.application.OperationAuditService;
import com.fuquan.audit.common.exception.*;
import com.fuquan.audit.security.application.AuthContext;
import com.fuquan.audit.security.domain.CurrentUser;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class BudgetMaterialRiskService {
    private final BudgetMaterialBatchService batches;private final BudgetReviewTaskRepository tasks;
    private final BudgetMaterialJobRepository jobs;private final BudgetMaterialRiskRepository results;
    private final AuthContext auth;private final OperationAuditService audit;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    public BudgetMaterialRiskService(BudgetMaterialBatchService batches,BudgetReviewTaskRepository tasks,BudgetMaterialJobRepository jobs,BudgetMaterialRiskRepository results,AuthContext auth,OperationAuditService audit,com.fasterxml.jackson.databind.ObjectMapper json){this.batches=batches;this.tasks=tasks;this.jobs=jobs;this.results=results;this.auth=auth;this.audit=audit;this.json=json;}
    private CurrentUser actor(){return auth.currentUser().orElseThrow(()->new BusinessException(BusinessErrorCode.UNAUTHENTICATED));}
    public CandidatePage candidatePage(int page,List<UUID> focus) {
        var rows=candidates(page,focus);
        return new CandidatePage(rows,(focus==null||focus.isEmpty())&&tasks.count(null,null,null)>(long)page*50);
    }
    public List<Candidate> candidates(int page,List<UUID> focus) {
        String actor=actor().userId();
        if(page<1||page>100000||focus!=null&&focus.size()>1000)throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT);
        var selected=focus==null||focus.isEmpty()?tasks.findPage(null,null,null,(page-1)*50,50):focus.stream().distinct().map(id->tasks.findById(id).orElse(null)).filter(Objects::nonNull).toList();
        var rows=new ArrayList<Candidate>();
        for(var task:selected) {
            if(jobs.find(task.id()).filter(j->j.actorId().equals(actor)).isEmpty())continue;
            var reference=batches.result(task.id());
            if(reference==null||!reference.status().equals("COMPLETED"))continue;
            for(int index=0;index<reference.lines().size();index++)rows.add(new Candidate(new Selection(task.id(),index),task.fileName(),task.createdTime().toString(),reference.lines().get(index)));
        }
        return rows;
    }
    @Transactional
    public Result analyze(UUID id,List<Selection> selections) {
        var user=actor();
        if(id==null||selections==null||selections.isEmpty()||selections.size()>100||selections.stream().anyMatch(s->s==null||s.taskId()==null||s.lineIndex()<0)||new HashSet<>(selections).size()!=selections.size())throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT);
        var ordered=selections.stream().sorted(Comparator.comparing((Selection s)->s.taskId().toString()).thenComparingInt(Selection::lineIndex)).toList();
        var old=results.find(id,user.userId());
        if(old.isPresent()) {
            if(!old.get().projects().stream().map(Candidate::source).toList().equals(ordered))throw new BusinessException(BusinessErrorCode.CONFLICT,"本次分析范围已确定，不能替换项目");
            return old.get();
        }
        var available=candidates(1,ordered.stream().map(Selection::taskId).distinct().toList());
        var rows=new ArrayList<Candidate>();
        for(var selection:ordered)rows.add(available.stream().filter(c->c.source().equals(selection)).findFirst().orElseThrow(()->new BusinessException(BusinessErrorCode.CONFLICT,"所选材料尚未完成解析或当前账号无权读取")));
        var split=BudgetMaterialSplitAnalysis.analyzeSelected(rows);
        var result=new Result(id,user.userId(),Instant.now().toString(),rows,List.of(split.summary()),split);
        results.insert(result);
        audit.success("BUDGET_MATERIAL_RISK_ANALYZED",user,"BUDGET_MATERIAL_RISK_RESULT",id.toString(),null,null,Map.of("itemCount",rows.size()),"保存预算项目风险参考结果");
        return result;
    }
    public List<Result> history(int page){if(page<1||page>100000)throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT);return results.history(actor().userId(),(page-1)*20,20);}
    public Result get(UUID id){return results.find(id,actor().userId()).orElseThrow(()->new BusinessException(BusinessErrorCode.NOT_FOUND));}

    private String keyword(String value) {
        if(value!=null&&value.length()>100)throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT);
        return value==null||value.isBlank()?null:value.strip();
    }
    public BrowsePage browse(String cursor,String search) {
        String user=actor().userId(),term=keyword(search);
        Position position=null;
        if(cursor!=null&&!cursor.isBlank())try {
            if(cursor.length()>180)throw new IllegalArgumentException();
            String[] parts=new String(Base64.getUrlDecoder().decode(cursor),java.nio.charset.StandardCharsets.UTF_8).split("\\|",-1);
            if(parts.length!=3)throw new IllegalArgumentException();
            position=new Position(Instant.parse(parts[0]),UUID.fromString(parts[1]),Integer.parseInt(parts[2]));
            if(position.lineIndex()<0||position.lineIndex()>10000)throw new IllegalArgumentException();
        }catch(RuntimeException invalid){throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT,"项目分页参数无效");}
        var found=new ArrayList<Candidate>();
        while(true) {
            var sources=results.sources(user,position,term,10);
            if(sources.isEmpty())return new BrowsePage(found,null);
            for(var source:sources) {
                BudgetUploadedFacts.FileResult reference;
                try{reference=json.readValue(source.resultJson(),BudgetUploadedFacts.FileResult.class);}
                catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw new IllegalStateException("budget risk source snapshot invalid",invalid);}
                if(!"COMPLETED".equals(reference.status()))throw new IllegalStateException("budget risk source not complete");
                int start=position!=null&&position.taskId().equals(source.taskId())?position.lineIndex():0;
                for(int i=start;i<reference.lines().size();i++) {
                    var row=new Candidate(new Selection(source.taskId(),i),source.fileName(),source.createdAt().toString(),reference.lines().get(i));
                    var l=row.line();
                    if(term!=null&&!java.util.stream.Stream.of(row.fileName(),l.project(),l.subject(),l.subjectCode()).filter(Objects::nonNull).anyMatch(v->v.contains(term)))continue;
                    if(found.size()==12) {
                        String next=source.createdAt()+"|"+source.taskId()+"|"+i;
                        return new BrowsePage(found,Base64.getUrlEncoder().withoutPadding().encodeToString(next.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                    }
                    found.add(row);
                }
                position=new Position(source.createdAt(),source.taskId(),reference.lines().size());
            }
            if(sources.size()<10)return new BrowsePage(found,null);
        }
    }
    public HistoryPage searchHistory(int page,String search,java.time.LocalDate date) {
        if(page<1||page>100000)throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT);
        String user=actor().userId(),term=keyword(search);
        var filter=BudgetHistoryFilter.of(null,date,date);
        var found=results.searchHistory(user,term,filter.createdFrom(),filter.createdBefore(),(page-1)*6,6);
        var items=found.stream().map(r->new HistoryItem(r.id(),r.createdAt(),r.projects().size(),
                r.projects().stream().map(c->Objects.toString(c.line().project(),"项目待核对")+" · "+Objects.toString(c.line().subject(),"科目待核对"))
                        .distinct().limit(3).collect(java.util.stream.Collectors.joining("、")),
                r.splitAnalysis()==null?null:r.splitAnalysis().totalAmount(),r.splitAnalysis()==null?null:r.splitAnalysis().bandLabel())).toList();
        return new HistoryPage(items,page,6,results.countHistory(user,term,filter.createdFrom(),filter.createdBefore()));
    }
}
