package com.fuquan.audit.integration.ai.fake;

import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort;
import com.fuquan.audit.common.exception.*;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile({"local", "local-dev & !local-ai"})
public class UnavailableMaterialFieldAdapter implements MaterialFieldAiPort {
    @Override public Result extract(String kind, String id, String sha, List<Segment> segments) {
        throw new BusinessException(BusinessErrorCode.EXTERNAL_SERVICE_ERROR, "AI 信息提取尚未接通");
    }
}
