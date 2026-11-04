package com.fuquan.audit.common.material.transfer;
import static com.fuquan.audit.common.material.transfer.MaterialTransfer.*;
import com.fuquan.audit.common.application.OperationAuditService;
import com.fuquan.audit.security.domain.CurrentUser;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
@Service
public class MaterialTransferTransactions {
    private final MaterialTransferRepository repo;private final OperationAuditService audit;
    public MaterialTransferTransactions(MaterialTransferRepository repo,OperationAuditService audit){this.repo=repo;this.audit=audit;}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void create(Session s,CurrentUser user){repo.insert(s);audit.success("MATERIAL_TRANSFER_STARTED",user,"MATERIAL_TRANSFER",s.id().toString(),null,null,java.util.Map.of("kind",s.kind(),"bytes",s.spec().size()),"开始可续传文件上传");}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void part(UUID id,Part p){repo.insertPart(id,p);}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public boolean claim(UUID id,String token,Instant now,Instant until){return repo.claim(id,token,now,until);}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public boolean renew(UUID id,String token,Instant until){return repo.renew(id,token,until);}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public boolean complete(UUID id,String token,String result,CurrentUser user){boolean changed=repo.complete(id,token,result);if(changed)audit.success("MATERIAL_TRANSFER_COMPLETED",user,"MATERIAL_TRANSFER",id.toString(),null,null,null,"文件分片校验完成并交付材料处理");return changed;}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void release(UUID id,String token){repo.release(id,token);}
}
