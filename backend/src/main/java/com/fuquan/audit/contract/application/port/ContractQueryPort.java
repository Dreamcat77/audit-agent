package com.fuquan.audit.contract.application.port;

import com.fuquan.audit.contract.application.ContractListQuery;
import com.fuquan.audit.contract.application.ContractSummary;
import java.util.List;

/** 合同目录轻量读模型端口。 */
public interface ContractQueryPort {
    List<ContractSummary> findPage(ContractListQuery query);

    long count(ContractListQuery query);
}
