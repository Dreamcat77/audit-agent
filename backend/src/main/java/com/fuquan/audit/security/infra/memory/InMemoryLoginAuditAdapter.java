package com.fuquan.audit.security.infra.memory;

import com.fuquan.audit.security.application.port.LoginAuditEvent;
import com.fuquan.audit.security.application.port.LoginAuditPort;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * local profile 的登录安全审计内存适配器。
 *
 * <p>该实现用于本地回归观察，不替代 dev29/customer 将长期登录安全事实持久化到 DM8 的适配器。</p>
 */
@Component
@Profile("local")
public class InMemoryLoginAuditAdapter implements LoginAuditPort {

    private final CopyOnWriteArrayList<LoginAuditEvent> events = new CopyOnWriteArrayList<>();

    /**
     * 线程安全地追加一条 local 登录审计事件。
     *
     * @param event 待追加安全审计事件
     * @throws NullPointerException 事件为空时抛出
     */
    @Override
    public void append(LoginAuditEvent event) {
        events.add(Objects.requireNonNull(event, "event must not be null"));
    }

    /**
     * 返回当前 local 审计事件的不可变快照，供验证和诊断使用。
     *
     * @return 按追加顺序排列的事件快照
     */
    public List<LoginAuditEvent> snapshot() {
        return List.copyOf(events);
    }
}
