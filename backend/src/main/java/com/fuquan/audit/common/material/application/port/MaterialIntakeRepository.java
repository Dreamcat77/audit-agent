package com.fuquan.audit.common.material.application.port;

import com.fuquan.audit.common.material.domain.MaterialIntake;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MaterialIntakeRepository {
    Optional<MaterialIntake> find(UUID id);
    Optional<MaterialIntake> findOperation(String actorId, String key);
    List<MaterialIntake> eligible(String keyword, int limit);
    List<MaterialIntake> recent(String actorId, int limit);
    List<MaterialIntake> pending(Instant now, int limit);
    boolean renew(UUID id, String leaseToken, Instant until);
    void insert(MaterialIntake intake);
    boolean claim(MaterialIntake intake, Instant now);
    boolean claimPending(MaterialIntake intake, Instant now);
    boolean complete(MaterialIntake intake);
    default List<MaterialIntake> timedOutForCleanup(int limit){return List.of();}
    default void cleaned(UUID id,String reason){throw new UnsupportedOperationException("cleanup acknowledgement is not supported");}
}
