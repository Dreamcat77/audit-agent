package com.fuquan.audit.integration.material;
import com.fuquan.audit.common.exception.*;
import com.fuquan.audit.common.material.visual.*;
import com.fuquan.audit.contract.api.ContractVisualController;
import com.fuquan.audit.contract.application.analysis.*;
import com.fuquan.audit.contract.application.project.ProjectFileContentReadService;
import com.fuquan.audit.procurement.autocase.*;
import com.fuquan.audit.budget.api.BudgetVisualController;
import com.fuquan.audit.budget.application.*;
import com.fuquan.audit.security.application.AuthContext;
import com.fuquan.audit.security.domain.CurrentUser;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class VisualOwnershipTest {
    @Test void threeModulesDenyForeignOwnerBeforeTextOrOriginalAccess()throws Exception {
        var user=new CurrentUser("other","other","other",Set.of("CONTRACT_USER"));var auth=mock(AuthContext.class);when(auth.currentUser()).thenReturn(Optional.of(user));
        var id=UUID.randomUUID();var file=UUID.randomUUID();var visual=mock(MaterialVisualEvidence.class);var renderer=mock(MaterialPageRenderer.class);
        var contracts=mock(ContractAnalysisService.class);var sources=mock(ContractAnalysisSources.class);var content=mock(ProjectFileContentReadService.class);
        when(contracts.owned(id,user)).thenThrow(new BusinessException(BusinessErrorCode.NOT_FOUND));
        var contract=new ContractVisualController(contracts,sources,content,visual,renderer,auth);
        assertThatThrownBy(()->contract.locate(id,file,0,5,1)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->contract.image(id,file,1)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->contract.office(id,file)).isInstanceOf(BusinessException.class);
        var purchases=mock(AuditCaseService.class);when(purchases.owned(id,user)).thenThrow(new BusinessException(BusinessErrorCode.NOT_FOUND));
        var procurement=new CaseVisualController(purchases,visual,renderer,auth);
        assertThatThrownBy(()->procurement.locate(id,file,0,5,1)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->procurement.image(id,file,1)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->procurement.office(id,file)).isInstanceOf(BusinessException.class);
        var files=mock(BudgetMaterialService.class);var batches=mock(BudgetMaterialBatchService.class);when(files.owned(id,user)).thenThrow(new BusinessException(BusinessErrorCode.NOT_FOUND));
        var budget=new BudgetVisualController(files,batches,visual,renderer,auth);
        assertThatThrownBy(()->budget.locate(id,0,5,1)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->budget.image(id,1)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->budget.original(id)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->budget.office(id)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(sources,content,visual,renderer,batches);verify(purchases,never()).open(any(),any(),any());verify(files,never()).openOriginal(any(),any());
    }
    @Test void budgetDownloadRequiresOriginalSizeAndHash()throws Exception {
        var user=new CurrentUser("owner","owner","owner",Set.of("BUDGET_USER"));var auth=mock(AuthContext.class);when(auth.currentUser()).thenReturn(Optional.of(user));
        var id=UUID.randomUUID();var files=mock(BudgetMaterialService.class);var task=mock(com.fuquan.audit.budget.domain.BudgetReviewTask.class);
        var job=mock(com.fuquan.audit.budget.domain.BudgetMaterialJob.class);
        when(files.owned(id,user)).thenReturn(job);when(files.task(id)).thenReturn(task);
        byte[] bytes="original-office-file".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(task.fileSize()).thenReturn((long)bytes.length);
        when(files.openOriginal(id,user)).thenAnswer(x->new java.io.ByteArrayInputStream(bytes));
        when(job.sha256()).thenReturn(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
        var controller=new BudgetVisualController(files,mock(BudgetMaterialBatchService.class),mock(MaterialVisualEvidence.class),mock(MaterialPageRenderer.class),auth);
        assertThat(controller.original(id).getBody()).isEqualTo(bytes);
        when(job.sha256()).thenReturn("different");
        assertThatThrownBy(()->controller.original(id)).isInstanceOf(BusinessException.class);
        when(task.fileSize()).thenReturn((long)bytes.length+1);
        assertThatThrownBy(()->controller.original(id)).isInstanceOf(BusinessException.class);
    }
    @Test void ownedBatchCannotOpenAnUnrelatedFile()throws Exception {
        var user=new CurrentUser("owner","owner","owner",Set.of("CONTRACT_USER"));var auth=mock(AuthContext.class);when(auth.currentUser()).thenReturn(Optional.of(user));
        var id=UUID.randomUUID();var alien=UUID.randomUUID();var visual=mock(MaterialVisualEvidence.class);var renderer=mock(MaterialPageRenderer.class);
        var contracts=mock(ContractAnalysisService.class);var batch=mock(ContractAnalysisCase.class);when(batch.sources()).thenReturn(List.of());when(contracts.owned(id,user)).thenReturn(batch);
        var sources=mock(ContractAnalysisSources.class);var content=mock(ProjectFileContentReadService.class);var contract=new ContractVisualController(contracts,sources,content,visual,renderer,auth);
        assertThatThrownBy(()->contract.image(id,alien,1)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->contract.office(id,alien)).isInstanceOf(BusinessException.class);
        var purchases=mock(AuditCaseService.class);var purchase=mock(AuditCase.class);when(purchase.documents()).thenReturn(List.of());when(purchases.owned(id,user)).thenReturn(purchase);
        var procurement=new CaseVisualController(purchases,visual,renderer,auth);assertThatThrownBy(()->procurement.image(id,alien,1)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->procurement.office(id,alien)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(sources,content,visual,renderer);verify(purchases,never()).open(any(),any(),any());
    }
}
