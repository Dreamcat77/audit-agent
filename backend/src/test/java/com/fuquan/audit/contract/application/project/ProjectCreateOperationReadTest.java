package com.fuquan.audit.contract.application.project;

import com.fuquan.audit.contract.infra.memory.project.InMemoryProjectRepositories;
import com.fuquan.audit.integration.idempotency.memory.InMemoryIdempotencyAdapter;
import com.fuquan.audit.common.observability.*;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.security.domain.CurrentUser;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ProjectCreateOperationReadTest {
    @Test void lostCreateResponseIsRediscoveredByOriginalKeyWithoutCreatingAnotherProjectOrLeakingAnotherActor() {
        var clock=Clock.fixed(Instant.parse("2026-10-14T00:00:00Z"),ZoneOffset.UTC);
        var repositories=new InMemoryProjectRepositories();
        var projects=new ProjectApplicationService(repositories.projectRepository(),repositories.projectFileRepository(),
                repositories.contractCandidateRepository(),repositories.extractionResultRepository(),clock);
        var current=new AtomicReference<>(new CurrentUser("actor-one","one","One",Set.of("AUDITOR")));
        var coordinator=new ProjectCommandTransactionCoordinator(projects,new InMemoryIdempotencyAdapter(),event->{},
                ()->Optional.of(current.get()),clock,new FlowDebugLogger(new ObservabilityProperties()));
        var created=coordinator.create("original-key","req","trace","127.0.0.1");
        assertThat(coordinator.readCreateOperation("original-key").id()).isEqualTo(created.id());
        assertThat(projects.get(created.id()).id()).isEqualTo(created.id());
        // Empty preparation projects intentionally stay out of the confirmed-project list.
        assertThat(projects.list(1,20,null).total()).isZero();
        current.set(new CurrentUser("actor-two","two","Two",Set.of("AUDITOR")));
        assertThatThrownBy(()->coordinator.readCreateOperation("original-key")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->coordinator.readCreateOperation("unproven-key")).isInstanceOf(BusinessException.class);
        assertThat(projects.get(created.id()).id()).isEqualTo(created.id());
        assertThat(projects.list(1,20,null).total()).isZero();
    }
}
