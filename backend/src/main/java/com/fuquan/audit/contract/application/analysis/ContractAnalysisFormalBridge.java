package com.fuquan.audit.contract.application.analysis;

import com.fuquan.audit.contract.application.project.*;
import com.fuquan.audit.contract.application.project.role.*;
import com.fuquan.audit.security.domain.CurrentUser;
import java.util.List;
import org.springframework.stereotype.Service;
import static com.fuquan.audit.contract.application.analysis.ContractAnalysisCase.*;

/** Existing audited decisions/promotion are reused; this bridge makes no external model call. */
@Service
public class ContractAnalysisFormalBridge {
    private final ContractAnalysisFactPublisher publisher;
    private final RoleAssessmentService roles;
    private final AutomaticProjectFileClassificationService automatic;
    public ContractAnalysisFormalBridge(ContractAnalysisFactPublisher publisher,RoleAssessmentService roles,
            AutomaticProjectFileClassificationService automatic){this.publisher=publisher;this.roles=roles;this.automatic=automatic;}
    public void link(ContractAnalysisCase c,Source source,List<Step> work,CurrentUser actor){
        publisher.publish(c,source,work,actor);
        var metadata=new ProjectRequestMetadata(c.id().toString(),c.id().toString(),null);
        roles.assess(new RoleAssessmentCommand(c.projectId(),source.fileId(),"analysis-role:"+source.parseResultId(),metadata),actor);
        automatic.advanceAnalyzedFile(c.projectId(),source.fileId(),metadata,actor);
    }
}
