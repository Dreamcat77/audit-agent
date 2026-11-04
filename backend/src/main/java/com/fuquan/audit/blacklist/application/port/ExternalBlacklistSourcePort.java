package com.fuquan.audit.blacklist.application.port;

import com.fuquan.audit.blacklist.domain.BlacklistSupplier;
import java.util.List;

/**
 * 外部行业信用/失信名单同步边界。
 *
 * <p>当前不主动调用外部系统；接入时由具体适配器实现该 Port，业务层不感知 HTTP
 * 客户端和外部系统协议。</p>
 */
public interface ExternalBlacklistSourcePort {

    List<BlacklistSupplier> fetchActiveEntries();
}
