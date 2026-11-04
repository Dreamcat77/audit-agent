package com.fuquan.audit.security.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fuquan.audit.common.application.port.OperationAuditEvent;
import com.fuquan.audit.common.application.port.OperationAuditPort;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.security.application.port.UserAccountRoleQueryPort;
import com.fuquan.audit.security.application.port.UserRoleAssignmentPort;
import com.fuquan.audit.security.domain.CurrentUser;
import com.fuquan.audit.security.domain.UserAccount;
import com.fuquan.audit.security.domain.UserAccountStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class UserRoleAssignmentServiceTest {

    @Test
    void adminCanAssignRoleIdempotentlyAndAuditTheOperation() {
        UserAccount target = account("target", Set.of("PROCUREMENT_APPLICANT"));
        FakeAssignments assignments = new FakeAssignments(target);
        List<OperationAuditEvent> events = new ArrayList<>();
        UserRoleAssignmentService service = new UserRoleAssignmentService(
                new FakeUserQuery(target), assignments, events::add);
        CurrentUser admin = user("admin", "SYSTEM_ADMIN");

        assertThat(service.assignRole("target", "finance_reviewer", admin))
                .containsExactly("FINANCE_REVIEWER", "PROCUREMENT_APPLICANT");
        assertThat(service.assignRole("target", "FINANCE_REVIEWER", admin))
                .containsExactly("FINANCE_REVIEWER", "PROCUREMENT_APPLICANT");

        assertThat(assignments.assignments).isEqualTo(1);
        assertThat(events).hasSize(2);
        assertThat(events.get(0).businessResult()).isEqualTo("ROLE_ASSIGNED");
        assertThat(events.get(1).businessResult()).isEqualTo("ROLE_ALREADY_ASSIGNED");
    }

    @Test
    void nonAdminCannotChangeRoleAndUnknownRoleIsRejected() {
        UserAccount target = account("target", Set.of());
        FakeAssignments assignments = new FakeAssignments(target);
        UserRoleAssignmentService service = new UserRoleAssignmentService(
                new FakeUserQuery(target), assignments, event -> { });

        assertThatThrownBy(() -> service.assignRole("target", "FINANCE_REVIEWER",
                user("reviewer", "PROCUREMENT_REVIEWER")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("系统管理员");
        assertThatThrownBy(() -> service.assignRole("target", "UNKNOWN_ROLE",
                user("admin", "SYSTEM_ADMIN")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("角色不存在");
    }

    private static CurrentUser user(String id, String role) {
        return new CurrentUser(id, id, id, Set.of(role));
    }

    private static UserAccount account(String id, Set<String> roles) {
        Instant now = Instant.now();
        return new UserAccount(id, id, "{noop}test", id, UserAccountStatus.ACTIVE,
                0, null, null, now, now, now, 0, roles);
    }

    private record FakeUserQuery(UserAccount account) implements UserAccountRoleQueryPort {
        @Override public Optional<UserAccount> findFirstActiveByRole(String roleCode) {
            return Optional.empty();
        }
        @Override public Optional<UserAccount> findActiveById(String userId) {
            return account.id().equals(userId) ? Optional.of(account) : Optional.empty();
        }
    }

    private static final class FakeAssignments implements UserRoleAssignmentPort {
        private final UserAccount target;
        private final Set<String> roles = new java.util.TreeSet<>();
        private int assignments;

        private FakeAssignments(UserAccount target) {
            this.target = target;
            roles.addAll(target.roles());
        }

        @Override public boolean isActiveRole(String roleCode) {
            return Set.of("PROCUREMENT_APPLICANT", "FINANCE_REVIEWER").contains(roleCode);
        }
        @Override public boolean hasRole(String userId, String roleCode) {
            return target.id().equals(userId) && roles.contains(roleCode);
        }
        @Override public void assignRole(String userId, String roleCode) {
            assignments++;
            roles.add(roleCode);
        }
        @Override public void removeRole(String userId, String roleCode) {
            roles.remove(roleCode);
        }
        @Override public List<String> findActiveRoleCodesByUserId(String userId) {
            return List.copyOf(roles);
        }
    }
}
