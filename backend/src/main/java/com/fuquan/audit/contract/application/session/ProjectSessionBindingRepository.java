package com.fuquan.audit.contract.application.session;

import com.fuquan.audit.contract.domain.ReviewSessionProjectFileBinding;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public interface ProjectSessionBindingRepository {
    void insert(ReviewSessionProjectFileBinding binding);
    List<ReviewSessionProjectFileBinding> findBySessionId(UUID sessionId);
    void deactivateBySessionId(UUID sessionId);
    /** local serializes the whole command; DM8 relies on transaction + session CAS. */
    default <T> T withSessionFence(UUID sessionId, Supplier<T> command) { return command.get(); }
}
