package com.fuquan.audit.security.application;

import com.fuquan.audit.common.application.OperationAuditService;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.security.application.port.UserAccountRepository;
import com.fuquan.audit.security.application.port.UserAdministrationPort;
import com.fuquan.audit.security.domain.CurrentUser;
import com.fuquan.audit.security.domain.PersonalUploadRoles;
import com.fuquan.audit.security.domain.UserAccount;
import com.fuquan.audit.security.domain.UserAccountStatus;
import com.fuquan.audit.security.domain.UsernameNormalizer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserAdministrationService {
    private final UserAdministrationPort accounts;
    private final UserAccountRepository lookup;
    private final PasswordEncoder passwords;
    private final OperationAuditService audit;

    public UserAdministrationService(UserAdministrationPort accounts, UserAccountRepository lookup,
            PasswordEncoder passwords, OperationAuditService audit) {
        this.accounts = accounts;
        this.lookup = lookup;
        this.passwords = passwords;
        this.audit = audit;
    }

    @Transactional
    public Person create(String username, String displayName, String password, CurrentUser operator) {
        requireAdmin(operator);
        String login = normalizeUsername(username);
        String name = validateName(displayName);
        validatePassword(password);
        if (lookup.findByUsername(login).isPresent()) throw duplicate();
        Instant now = Instant.now();
        // Reuse the deployed ordinary-user role code; legacy job labels no longer restrict modules.
        var account = new UserAccount(UUID.randomUUID().toString(), login, passwords.encode(password), name,
                UserAccountStatus.ACTIVE, 0, null, null, now, now, now, 0, Set.of(PersonalUploadRoles.CONTRACT));
        try {
            accounts.create(account);
        } catch (DuplicateKeyException exists) {
            throw duplicate();
        }
        Person person = Person.of(account);
        audit.success("CREATE_USER", operator, "USER", account.id(), name, null, person, "添加人员");
        return person;
    }

    @Transactional(readOnly = true)
    public People list(int page, int size, CurrentUser operator) {
        requireAdmin(operator);
        if (page < 1 || page > 1000000 || size < 1 || size > 100) throw invalid("分页参数不正确");
        return new People(accounts.list((page - 1) * size, size).stream().map(Person::of).toList(),
                accounts.count(), page, size);
    }

    @Transactional
    public Person update(String id, String username, String displayName, String password, CurrentUser operator) {
        requireAdmin(operator);
        UserAccount current = requireAccount(id);
        String login = normalizeUsername(username);
        String name = validateName(displayName);
        boolean renaming = !current.username().equals(login);
        boolean resettingPassword = password != null && !password.isEmpty();
        if (renaming && lookup.findByUsername(login).isPresent()) throw duplicate();
        if (resettingPassword) validatePassword(password);
        if (!renaming && name.equals(current.realName()) && !resettingPassword) return Person.of(current);

        Instant now = Instant.now();
        var next = new UserAccount(id, login, resettingPassword ? passwords.encode(password) : current.passwordHash(),
                name, current.status(), current.failedLoginCount(), current.lockedUntil(), current.lastLoginAt(),
                resettingPassword || renaming ? newSessionVersion(current, now) : current.passwordChangedAt(),
                current.createdAt(), now, current.versionNo(), current.roles());
        return save(current, next, operator, "UPDATE_USER", resettingPassword ? "修改人员并重设密码" : "修改人员");
    }

    @Transactional
    public Person setStatus(String id, String status, CurrentUser operator) {
        requireAdmin(operator);
        if (!"ACTIVE".equals(status) && !"DISABLED".equals(status)) throw invalid("账号状态不正确");
        UserAccountStatus target = UserAccountStatus.valueOf(status);
        if (id.equals(operator.userId()) && target == UserAccountStatus.DISABLED) {
            throw invalid("不能停用当前登录账号");
        }
        UserAccount current = requireAccount(id);
        if (current.status() == target) return Person.of(current);
        Instant now = Instant.now();
        var next = new UserAccount(id, current.username(), current.passwordHash(), current.realName(), target,
                0, null, current.lastLoginAt(), newSessionVersion(current, now),
                current.createdAt(), now, current.versionNo(), current.roles());
        return save(current, next, operator, "UPDATE_USER_STATUS", target == UserAccountStatus.ACTIVE ? "启用人员" : "停用人员");
    }

    private Person save(UserAccount current, UserAccount next, CurrentUser operator, String action, String description) {
        try {
            accounts.update(next);
        } catch (DuplicateKeyException exists) {
            throw duplicate();
        } catch (OptimisticLockingFailureException conflict) {
            throw new BusinessException(BusinessErrorCode.CONFLICT, "账号刚刚发生变化，请刷新后重试");
        }
        Person person = Person.of(next);
        audit.success(action, operator, "USER", next.id(), next.realName(), Person.of(current), person, description);
        return person;
    }

    private UserAccount requireAccount(String id) {
        return Optional.ofNullable(accounts.findById(id))
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND));
    }

    // Use the existing credential timestamp to revoke sessions without a schema change.
    private static Instant newSessionVersion(UserAccount current, Instant now) {
        Instant timestamp = now.truncatedTo(ChronoUnit.MICROS);
        Instant minimum = current.passwordChangedAt().truncatedTo(ChronoUnit.MICROS).plus(1, ChronoUnit.MICROS);
        return timestamp.isBefore(minimum) ? minimum : timestamp;
    }

    private static String normalizeUsername(String value) {
        String login;
        try {
            login = UsernameNormalizer.normalize(value);
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw invalid("用户名格式不正确");
        }
        if (!login.matches("[a-z0-9][a-z0-9_.-]{1,63}")) {
            throw invalid("用户名需为2～64位字母、数字、点、横线或下划线");
        }
        return login;
    }

    private static String validateName(String value) {
        String name = value == null ? "" : value.strip();
        if (name.isEmpty() || name.length() > 80 || name.chars().anyMatch(Character::isISOControl)) {
            throw invalid("姓名需为1～80个字符");
        }
        return name;
    }

    private static void validatePassword(String password) {
        if (password == null || password.isBlank() || password.length() < 8
                || password.getBytes(StandardCharsets.UTF_8).length > 72
                || password.chars().anyMatch(Character::isISOControl)) {
            throw invalid("密码至少8位，且不超过72字节");
        }
    }

    private static void requireAdmin(CurrentUser operator) {
        if (operator == null || !operator.roles().contains(PersonalUploadRoles.SYSTEM_ADMIN)) {
            throw new BusinessException(BusinessErrorCode.FORBIDDEN);
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, message);
    }

    private static BusinessException duplicate() {
        return new BusinessException(BusinessErrorCode.CONFLICT, "用户名已存在");
    }

    public record Person(String id, String username, String displayName, String status, boolean administrator) {
        static Person of(UserAccount account) {
            return new Person(account.id(), account.username(), account.realName(), account.status().name(),
                    account.roles().contains(PersonalUploadRoles.SYSTEM_ADMIN));
        }
    }
    public record People(List<Person> items, long total, int page, int size) {}
}
