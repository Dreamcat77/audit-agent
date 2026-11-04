package com.fuquan.audit.contract.application.analysis;

import com.fuquan.audit.common.exception.*;
import com.fuquan.audit.contract.application.project.port.*;
import com.fuquan.audit.contract.domain.project.*;
import com.fuquan.audit.security.domain.CurrentUser;
import java.util.*;
import org.springframework.stereotype.Component;
import static com.fuquan.audit.contract.application.analysis.ContractAnalysisCase.*;

/** Uses existing Intake originals and immutable parses; never creates a second text store. */
@Component
public class ContractAnalysisSources {
    private final ProjectRepository projects;
    private final ProjectFileRepository files;
    private final ProjectFileParseResultRepository parses;
    private final ProjectFileJobRepository jobs;
    public ContractAnalysisSources(ProjectRepository projects,ProjectFileRepository files,ProjectFileParseResultRepository parses,ProjectFileJobRepository jobs){
        this.projects=projects;this.files=files;this.parses=parses;this.jobs=jobs;
    }
    public String projectName(ContractAnalysisCase c){
        return projects.findById(c.projectId()).filter(p->p.createdBy().equals(c.owner())).map(Project::name).orElse(null);
    }
    public List<Source> manifest(UUID projectId,CurrentUser actor){
        projects.findById(projectId).filter(p->p.createdBy().equals(actor.userId())).orElseThrow(()->new BusinessException(BusinessErrorCode.NOT_FOUND));
        var input=files.findByProjectId(projectId).stream().filter(f->f.category()!=ProjectFileCategory.PACKAGE)
                .sorted(Comparator.comparing(f->f.id().toString())).toList();
        if(input.isEmpty()||input.size()>200)throw new BusinessException(BusinessErrorCode.CONFLICT,"材料仍在接收或数量超出单批分析范围");
        return input.stream().map(f->new Source(f.id(),f.originalFilename(),f.sha256(),f.sizeBytes(),SourceState.WAITING,null,null,null,null)).toList();
    }
    public Optional<List<Source>> acceptedManifest(ContractAnalysisCase c){
        var project=projects.findById(c.projectId()).filter(p->p.createdBy().equals(c.owner())).orElseThrow(()->new IllegalStateException("INTAKE_OWNER_UNPROVEN"));
        var all=files.findByProjectId(project.id());var roots=all.stream().filter(f->f.parentArchiveFileId()==null).toList();
        if(roots.size()>c.expectedUploads())throw new IllegalStateException("INTAKE_MANIFEST_CHANGED");
        if(roots.size()<c.expectedUploads()||roots.stream().anyMatch(f->f.ingestionStatus()==ProjectFileIngestionStatus.UPLOAD_PENDING))return Optional.empty();
        for(var root:roots)if(root.category()==ProjectFileCategory.PACKAGE){
            var job=jobs.byFile(c.projectId(),root.id()).orElse(null);
            if(job==null||Set.of("QUEUED","PROCESSING").contains(job.status()))return Optional.empty();
        }
        var members=all.stream().filter(f->f.category()!=ProjectFileCategory.PACKAGE).sorted(Comparator.comparing(f->f.id().toString())).toList();
        if(members.size()>200)throw new IllegalStateException("INTAKE_MEMBER_LIMIT");
        if(members.isEmpty())return Optional.of(roots.stream().map(f->new Source(f.id(),f.originalFilename(),f.sha256(),f.sizeBytes(),SourceState.FAILED,null,null,null,"NO_READABLE_PACKAGE_MEMBERS")).toList());
        return Optional.of(members.stream().map(f->new Source(f.id(),f.originalFilename(),f.sha256(),f.sizeBytes(),SourceState.WAITING,null,null,null,null)).toList());
    }
    public Source freeze(UUID projectId,Source s){
        if(s.status()!=SourceState.WAITING)return s;
        var f=files.findByProjectIdAndId(projectId,s.fileId()).orElse(null);
        if(f==null||!s.sha256().equals(f.sha256())||s.size()!=f.sizeBytes())return state(s,SourceState.UNKNOWN,"SOURCE_IDENTITY_UNPROVEN");
        var p=parses.findLatestByFileId(s.fileId()).orElse(null);
        if(p!=null){
            if(!p.projectId().equals(projectId)||!p.fileId().equals(s.fileId())||!p.fileSha256().equals(s.sha256()))return state(s,SourceState.UNKNOWN,"PARSE_IDENTITY_UNPROVEN");
            if(p.parseStatus()==ProjectFileParseStatus.PARSED)return new Source(s.fileId(),s.name(),s.sha256(),s.size(),SourceState.READY,p.parseResultId(),p.parseVersion(),p.canonicalTextSha256(),null);
            return state(s,SourceState.FAILED,"FILE_PARSE_FAILED");
        }
        var job=jobs.byFile(projectId,s.fileId()).orElse(null);
        if(job!=null&&Set.of("UNKNOWN","FAILED").contains(job.status()))return state(s,job.status().equals("UNKNOWN")?SourceState.UNKNOWN:SourceState.FAILED,"FILE_PROCESSING_"+job.status());
        if(f.parseStatus()==ProjectFileParseStatus.FAILED||f.parseStatus()==ProjectFileParseStatus.UNSUPPORTED||f.ingestionStatus()==ProjectFileIngestionStatus.UPLOAD_FAILED)
            return state(s,SourceState.FAILED,"FILE_PARSE_FAILED");
        return s;
    }
    public ProjectFileParseResult load(UUID projectId,Source s){
        if(s.status()!=SourceState.READY)throw new IllegalStateException("SOURCE_NOT_READY");
        var p=parses.findById(s.parseResultId()).orElseThrow(()->new IllegalStateException("FROZEN_PARSE_MISSING"));
        if(!p.projectId().equals(projectId)||!p.fileId().equals(s.fileId())||!p.fileSha256().equals(s.sha256())
                ||p.parseVersion()!=s.parseVersion()||p.parseStatus()!=ProjectFileParseStatus.PARSED||!Objects.equals(p.canonicalTextSha256(),s.textSha256())
                ||!sha256(p.canonicalText()).equals(s.textSha256()))throw new IllegalStateException("FROZEN_PARSE_IDENTITY_UNPROVEN");
        return p;
    }
    public ProjectFile file(UUID projectId,Source source){
        return files.findByProjectIdAndId(projectId,source.fileId()).filter(f->f.sha256().equals(source.sha256())&&f.sizeBytes()==source.size())
            .orElseThrow(()->new IllegalStateException("SOURCE_IDENTITY_UNPROVEN"));
    }
    public static Source state(Source s,SourceState state,String reason){return new Source(s.fileId(),s.name(),s.sha256(),s.size(),state,s.parseResultId(),s.parseVersion(),s.textSha256(),reason);}
}
