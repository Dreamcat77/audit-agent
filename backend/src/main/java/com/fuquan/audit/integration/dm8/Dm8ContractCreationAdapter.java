package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.project.port.ContractCreationPort;
import com.fuquan.audit.contract.domain.Contract;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

@Repository
@Profile({"dev29", "customer", "local-dev"})
public class Dm8ContractCreationAdapter implements ContractCreationPort {
    private final ContractCreationMapper mapper;

    public Dm8ContractCreationAdapter(ContractCreationMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insertCompleteSnapshot(Contract contract) {
        try {
            mapper.insertContract(root(contract));
            contract.lineItems().forEach(line -> mapper.insertLineItem(new ContractLineItemPersistenceRow(
                    line.id().toString(), line.contractId().toString(), line.itemKey(), line.itemName(),
                    line.basis().name(), line.quantity(), line.unitPrice(),
                    line.declaredAmount().amount(), line.sortNo())));
            if (contract.awardNotice() != null) {
                var award = contract.awardNotice();
                mapper.insertAwardNotice(new ContractAwardNoticePersistenceRow(
                        award.id().toString(), award.contractId().toString(), award.noticeNo(), award.projectName(),
                        award.winningBidder(), award.winningBidderCreditCode(), award.winningAmount().amount(),
                        award.winningDate(), award.sourceRef(),
                        LocalDateTime.ofInstant(contract.createdAt(), ZoneOffset.UTC)));
            }
        } catch (DuplicateKeyException conflict) {
            throw new BusinessException(BusinessErrorCode.CONFLICT, "contract identity conflict");
        }
    }

    private ContractPersistenceRow root(Contract contract) {
        ContractPersistenceRow row = new ContractPersistenceRow();
        row.setId(contract.id().toString());
        row.setContractNo(contract.contractNo());
        row.setContractName(contract.contractName());
        row.setContractType(contract.contractType());
        row.setPartyA(contract.partyA());
        row.setPartyB(contract.partyB());
        row.setPartyBCreditCode(contract.partyBCreditCode());
        row.setContractAmount(contract.contractAmount().amount());
        row.setAmountInWords(contract.amountInWords());
        row.setStatus(contract.status());
        row.setVersionNo(contract.versionNo());
        row.setCreatedAt(LocalDateTime.ofInstant(contract.createdAt(), ZoneOffset.UTC));
        row.setUpdatedAt(LocalDateTime.ofInstant(contract.updatedAt(), ZoneOffset.UTC));
        return row;
    }
}
