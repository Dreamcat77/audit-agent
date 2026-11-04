package com.fuquan.audit.blacklist.infra.memory;

import com.fuquan.audit.blacklist.application.port.ExternalBlacklistSourcePort;
import com.fuquan.audit.blacklist.domain.BlacklistSupplier;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 未配置外部名单服务时的显式空适配器，不产生任何业务数据。
 */
@Component
@Profile("local")
public class NoopExternalBlacklistSource implements ExternalBlacklistSourcePort {

    @Override
    public List<BlacklistSupplier> fetchActiveEntries() {
        return List.of();
    }
}
