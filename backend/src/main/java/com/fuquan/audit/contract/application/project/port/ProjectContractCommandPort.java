package com.fuquan.audit.contract.application.project.port;

import com.fuquan.audit.contract.domain.project.ProjectContract;

public interface ProjectContractCommandPort {
    void insert(ProjectContract association);
}
