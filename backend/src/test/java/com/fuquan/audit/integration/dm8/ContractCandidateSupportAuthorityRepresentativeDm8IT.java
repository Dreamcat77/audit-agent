package com.fuquan.audit.integration.dm8;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fuquan.audit.common.application.port.IdempotencyPort;
import com.fuquan.audit.common.application.port.OperationAuditPort;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.contract.application.project.*;
import com.fuquan.audit.contract.application.project.extraction.ContractCandidateAssembler;
import com.fuquan.audit.contract.application.project.extraction.ContractCandidateDerivedFacts;
import com.fuquan.audit.contract.application.project.port.ProjectFileRepository;
import com.fuquan.audit.contract.domain.project.ContractCandidateLineItem;
import com.fuquan.audit.security.domain.CurrentUser;
import java.io.InputStream;
import java.sql.*;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Explicitly selected IT, excluded by default Surefire naming. No application startup or DDL.
 * AUDIT_P1B_DM8_VERIFY=true + AUDIT_DB_* are mandatory; absence FAILS, never skips.
 * Read-only schema verification precedes every possible write. After separately approved
 * migration/preflight, writes additionally require AUDIT_P1B_DM8_WRITE_VERIFY=true and
 * AUDIT_P1B_DM8_ACTOR_ID (an approved existing non-system test actor UUID).
 *
 * Constraint tests roll back all fixtures. Cross-connection tests commit generated synthetic
 * fixtures, then clean up only those exact generated Project IDs. Printed IDs permit operator
 * recovery after process termination. Never run the write gate against a customer database.
 * Coordinator probes stop at authority validation: they prove production lock acquisition,
 * not full validation-to-commit, audit/idempotency, Confirmation or Promotion replay.
 */
class ContractCandidateSupportAuthorityRepresentativeDm8IT {
    private static final String SUPPORT = "CONTRACT_CANDIDATE_SUPPORT_AUTHORITY";
    private static final String HASH = "a".repeat(64);
    private static final String NEXT_HASH = "b".repeat(64);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 20, 0, 0);
    private static SqlSessionFactory sessions;

    @BeforeAll
    static void requireRealDm8AndExactCatalogBeforeAnyWrite() {
        safe(() -> {
            assertEquals("true", System.getenv("AUDIT_P1B_DM8_VERIFY"),
                    "NOT VERIFIED: explicit AUDIT_P1B_DM8_VERIFY=true is required");
            required("AUDIT_DB_URL"); required("AUDIT_DB_USERNAME"); required("AUDIT_DB_PASSWORD");
            assertTrue(required("AUDIT_DB_URL").startsWith("jdbc:dm:"), "NOT VERIFIED: DM8 JDBC required");
            assertFalse(Set.of("SYSDBA", "SYSAUDITOR", "SYSSSO").contains(
                    required("AUDIT_DB_USERNAME").toUpperCase(Locale.ROOT)), "System accounts are prohibited");
            try (Connection c = connect()) {
                c.setReadOnly(true);
                assertTrue(c.getMetaData().getDatabaseProductName().toUpperCase(Locale.ROOT).contains("DM"),
                        "NOT VERIFIED: database must identify as DM");
                assertEquals(8, c.getMetaData().getDatabaseMajorVersion(), "NOT VERIFIED: representative DM8 required");
                System.out.println("P1B representative DM8 major=8; credentials and endpoint withheld");
                assertCatalog(c);
                c.rollback();
            }
            Configuration config = new Configuration();
            config.setMapUnderscoreToCamelCase(true);
            config.setDefaultStatementTimeout(8);
            config.setLogImpl(NoLoggingImpl.class);
            for (String name : List.of("ProjectMapper", "ProjectFileMapper", "ContractCandidateMapper",
                    "ContractCandidateSupportAuthorityMapper")) {
                String resource = "mappers/dm8/" + name + ".xml";
                try (InputStream in = ContractCandidateSupportAuthorityRepresentativeDm8IT.class
                        .getClassLoader().getResourceAsStream(resource)) {
                    assertNotNull(in, "production mapper resource missing");
                    new XMLMapperBuilder(in, config, resource, config.getSqlFragments()).parse();
                }
            }
            sessions = new SqlSessionFactoryBuilder().build(config);
        });
    }

    @Test
    void exactReadOnlyCatalog() {
        safe(() -> {
            try (Connection c = connect()) { c.setReadOnly(true); assertCatalog(c); c.rollback(); }
        });
    }

    @Test
    void exactCompositeConstraintsRejectSubstitutionAndAllowIndependentCandidateOwnership() {
        safe(() -> {
            requireWriteGate();
            try (Connection c = connect()) {
                Fixture f = new Fixture();
                try {
                    f.seed(c);
                    // Historical rows remain null/null; each one-null direction must fail.
                    update(c, "UPDATE CONTRACT_CANDIDATE SET SUPPORT_AUTHORITY_VERSION_NO=NULL, "
                            + "SUPPORT_AUTHORITY_FINGERPRINT=NULL WHERE ID=?", f.secondCandidate);
                    rejected(c, () -> update(c, "UPDATE CONTRACT_CANDIDATE SET SUPPORT_AUTHORITY_VERSION_NO=1 WHERE ID=?",
                            f.secondCandidate));
                    rejected(c, () -> update(c, "UPDATE CONTRACT_CANDIDATE SET SUPPORT_AUTHORITY_FINGERPRINT=? WHERE ID=?",
                            HASH, f.secondCandidate));
                    update(c, "UPDATE CONTRACT_CANDIDATE SET SUPPORT_AUTHORITY_VERSION_NO=1, "
                            + "SUPPORT_AUTHORITY_FINGERPRINT=? WHERE ID=?", HASH, f.secondCandidate);
                    Map<String, Object> valid = f.support(f.a, f.secondCandidate, 1);
                    insert(c, SUPPORT, valid); // Same A is already owned independently by first Candidate.
                    rejected(c, () -> insert(c, SUPPORT, f.support(f.a, f.secondCandidate, 1)));
                    for (Map.Entry<String, Object> mutation : List.<Map.Entry<String, Object>>of(
                            Map.entry("PROJECT_ID", f.otherProject),
                            Map.entry("SOURCE_FILE_ID", f.foreign.id),
                            Map.entry("PARSE_RESULT_ID", f.b.parse), Map.entry("FILE_SHA256", NEXT_HASH),
                            Map.entry("PARSE_VERSION", 2), Map.entry("ROLE_DECISION_ID", f.b.role),
                            Map.entry("ROLE_DECISION_ID", f.a.otherRole),
                            Map.entry("EFFECTIVE_ROLE", "CONTRACT_BODY"),
                            Map.entry("IDENTITY_DECISION_ID", f.b.identity),
                            Map.entry("IDENTITY_DECISION_ID", f.a.otherIdentity),
                            Map.entry("PROJECT_VERSION_NO", 2L),
                            Map.entry("IDENTITY_RESOLUTION", "CONFIRM_NOT_SAME_PROJECT"),
                            Map.entry("AUTHORITY_VERSION_NO", 2L),
                            Map.entry("SOURCE_FACT_FINGERPRINT", "z".repeat(64)),
                            Map.entry("FILE_SHA256", "A".repeat(64)),
                            Map.entry("PARSE_VERSION", 0), Map.entry("AUTHORITY_VERSION_NO", 0L))) {
                        // B is otherwise unused by this Candidate; duplicate ownership cannot mask rejection.
                        Map<String, Object> row = f.support(f.b, f.secondCandidate, 1);
                        row.put(mutation.getKey(), mutation.getValue());
                        // Use A for substitutions whose alternative points to B.
                        if (Set.of("PARSE_RESULT_ID", "ROLE_DECISION_ID", "IDENTITY_DECISION_ID")
                                .contains(mutation.getKey())) {
                            update(c, "DELETE FROM " + SUPPORT + " WHERE PROJECT_ID=? AND CANDIDATE_ID=?",
                                    f.project, f.secondCandidate);
                            row = f.support(f.a, f.secondCandidate, 1);
                            row.put(mutation.getKey(), mutation.getValue());
                        }
                        Map<String, Object> changed = row;
                        rejected(c, () -> insert(c, SUPPORT, changed));
                    }
                    assertEquals(1, count(c, SUPPORT, "CANDIDATE_ID", f.candidate));
                } finally { c.rollback(); }
                assertEquals(0, count(c, "PROJECT", "ID", f.project));
                assertEquals(0, count(c, "PROJECT", "ID", f.otherProject));
            }
        });
    }

    @Test
    void authorityHoldingFileCanAcquireProjectWhileProductionReplacementWaitsForFile() {
        safe(() -> {
            try (Fixture f = committedFixture(); Connection authority = connect()) {
                lockFile(authority, f.project, f.a.id);
                CountDownLatch attemptingFile = new CountDownLatch(1);
                List<String> acquired = new CopyOnWriteArrayList<>();
                ExecutorService pool = Executors.newFixedThreadPool(1);
                try {
                    Future<?> replacement = pool.submit(() -> productionLockProbe(f, List.of(f.a.id), acquired,
                            id -> { if (id.equals(f.a.id)) attemptingFile.countDown(); }));
                    await(attemptingFile);
                    assertThrows(TimeoutException.class, () -> replacement.get(300, TimeUnit.MILLISECONDS),
                            "replacement must wait for the held support File");
                    assertFalse(acquired.contains("PROJECT"), "replacement took Project before support File");
                    // A reversed lock order deadlocks here and fails the bounded statement timeout.
                    query(authority, "SELECT ID FROM PROJECT WHERE ID=? FOR UPDATE", f.project);
                    authority.rollback();
                    replacement.get(15, TimeUnit.SECONDS);
                    assertTrue(acquired.contains("PROJECT"));
                    System.out.println("P1B lock probe: File wait observed; authority acquired Project; no deadlock");
                } finally {
                    authority.rollback();
                    stop(pool, f);
                }
            }
        });
    }

    @Test
    void oppositeInputOrdersAcquireIdenticalCanonicalProductionFileOrderWithoutDeadlock() {
        safe(() -> {
            try (Fixture f = committedFixture()) {
                ExecutorService pool = Executors.newFixedThreadPool(2);
                CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
                List<String> first = new CopyOnWriteArrayList<>(), second = new CopyOnWriteArrayList<>();
                try {
                    Future<?> a = pool.submit(() -> { ready.countDown(); await(start);
                        productionLockProbe(f, List.of(f.a.id, f.b.id), first, id -> {}); });
                    Future<?> b = pool.submit(() -> { ready.countDown(); await(start);
                        productionLockProbe(f, List.of(f.b.id, f.a.id), second, id -> {}); });
                    await(ready); start.countDown();
                    a.get(15, TimeUnit.SECONDS); b.get(15, TimeUnit.SECONDS);
                    List<String> expected = new ArrayList<>(List.of(f.primary.id, f.a.id, f.b.id));
                    expected.sort(String::compareTo); expected.add("PROJECT");
                    assertEquals(expected, first); assertEquals(expected, second);
                    System.out.println("P1B lock probe: opposite inputs, identical canonical acquisition, no deadlock");
                } finally { start.countDown(); stop(pool, f); }
            }
        });
    }

    @Test
    void sameCandidateVersionHasOneCommittedProductionCasWinnerAndNoMixedCohort() {
        safe(() -> {
            try (Fixture f = committedFixture()) {
                ExecutorService pool = Executors.newFixedThreadPool(2);
                CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
                try {
                    Future<Boolean> a = pool.submit(() -> replaceAtVersionZero(f, f.a, ready, start));
                    Future<Boolean> b = pool.submit(() -> replaceAtVersionZero(f, f.b, ready, start));
                    await(ready); start.countDown();
                    boolean aWon = a.get(15, TimeUnit.SECONDS), bWon = b.get(15, TimeUnit.SECONDS);
                    assertNotEquals(aWon, bWon, "exactly one committed CAS winner is required");
                    try (Connection c = connect(); SqlSession session = sessions.openSession(c)) {
                        var candidate = session.getMapper(ContractCandidateMapper.class)
                                .findByProjectAndId(f.project, f.candidate);
                        var rows = session.getMapper(ContractCandidateSupportAuthorityMapper.class)
                                .findCurrentByProjectAndCandidate(f.project, f.candidate);
                        assertEquals(1, candidate.versionNo()); assertEquals(2L, candidate.supportAuthorityVersionNo());
                        assertEquals(NEXT_HASH, candidate.supportAuthorityFingerprint()); assertEquals(1, rows.size());
                        assertEquals(aWon ? f.a.id : f.b.id, rows.get(0).sourceFileId());
                        assertEquals(2, rows.get(0).authorityVersion());
                        assertEquals(1, session.getMapper(ContractCandidateMapper.class)
                                .findLinesByCandidate(f.project, f.candidate).size());
                        c.rollback();
                    }
                    System.out.println("P1B CAS: one commit, one rejection; Candidate version=1, cohort=2; no mixed cohort");
                } finally { start.countDown(); stop(pool, f); }
            }
        });
    }

    @Test
    void failuresAfterDeleteCasAndLineReplacementAndInsertRestoreTheWholeTransaction() {
        safe(() -> {
            try (Fixture f = committedFixture()) {
                List<Object> before = snapshot(f);
                for (int failureStage = 1; failureStage <= 3; failureStage++) {
                    int stage = failureStage;
                    assertThrows(InjectedFailure.class, () -> {
                        try (Connection c = connect(); SqlSession session = sessions.openSession(c)) {
                            try {
                                replaceThroughProductionRepositories(session, f, f.b, stage);
                                fail("failure injection did not execute");
                            } finally { c.rollback(); }
                        }
                    });
                    assertEquals(before, snapshot(f), "rollback must restore Candidate, line items and support rows");
                }
                System.out.println("P1B rollback: old rows, Candidate CAS/lines, and inserted cohort all restored");
            }
        });
    }

    private static void assertCatalog(Connection c) throws SQLException {
        assertEquals(1, scalar(c, "SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME=?", SUPPORT),
                "NOT VERIFIED / RED: support authority table absent; no write was attempted");
        column(c, "CONTRACT_CANDIDATE", "SUPPORT_AUTHORITY_VERSION_NO", "BIGINT", 8, "Y");
        column(c, "CONTRACT_CANDIDATE", "SUPPORT_AUTHORITY_FINGERPRINT", "VARCHAR", 64, "Y");
        Map<String, Integer> text = new LinkedHashMap<>();
        for (String name : List.of("SUPPORT_AUTHORITY_ID", "PROJECT_ID", "CANDIDATE_ID", "SOURCE_FILE_ID",
                "PARSE_RESULT_ID", "ROLE_DECISION_ID", "IDENTITY_DECISION_ID", "CREATED_BY")) text.put(name, 36);
        for (String name : List.of("FILE_SHA256", "SOURCE_FACT_FINGERPRINT")) text.put(name, 64);
        text.put("EFFECTIVE_ROLE", 32); text.put("IDENTITY_RESOLUTION", 40);
        for (var e : text.entrySet()) column(c, SUPPORT, e.getKey(), "VARCHAR", e.getValue(), "N");
        column(c, SUPPORT, "AUTHORITY_VERSION_NO", "BIGINT", 8, "N");
        column(c, SUPPORT, "PROJECT_VERSION_NO", "BIGINT", 8, "N");
        column(c, SUPPORT, "PARSE_VERSION", "INTEGER", 4, "N");
        column(c, SUPPORT, "CREATED_AT", "TIMESTAMP", 8, "N");
        assertEquals(16, scalar(c, "SELECT COUNT(*) FROM USER_TAB_COLUMNS WHERE TABLE_NAME=?", SUPPORT));
        key(c, "CONTRACT_CANDIDATE", "UK_CONTRACT_CANDIDATE_SUPPORT_COHORT", "U",
                "PROJECT_ID,ID,SUPPORT_AUTHORITY_VERSION_NO");
        key(c, "PROJECT_FILE_ROLE_DECISION", "UK_PROJECT_FILE_ROLE_DECISION_SUPPORT_AUTHORITY_BINDING", "U",
                "DECISION_ID,PROJECT_ID,FILE_ID,FILE_SHA256,PARSE_RESULT_ID,PARSE_VERSION,SELECTED_ROLE");
        key(c, "PROJECT_FILE_IDENTITY_DECISION", "UK_PROJECT_FILE_IDENTITY_DECISION_SUPPORT_AUTHORITY_BINDING", "U",
                "DECISION_ID,PROJECT_ID,PROJECT_VERSION_NO,FILE_ID,FILE_SHA256,PARSE_RESULT_ID,PARSE_VERSION,RESOLUTION");
        key(c, "PROJECT_FILE", "UK_PROJECT_FILE_PROJECT_ID", "U", "PROJECT_ID,ID");
        key(c, "PROJECT_FILE_PARSE_RESULT", "UK_PROJECT_FILE_PARSE_RESULT_BINDING", "U",
                "PARSE_RESULT_ID,PROJECT_ID,FILE_ID,FILE_SHA256,PARSE_VERSION");
        key(c, SUPPORT, "PK_" + SUPPORT, "P", "SUPPORT_AUTHORITY_ID");
        key(c, SUPPORT, "UK_" + SUPPORT + "_FILE", "U", "PROJECT_ID,CANDIDATE_ID,SOURCE_FILE_ID");
        foreignKey(c, "CANDIDATE", "PROJECT_ID,CANDIDATE_ID,AUTHORITY_VERSION_NO", "CONTRACT_CANDIDATE",
                "PROJECT_ID,ID,SUPPORT_AUTHORITY_VERSION_NO");
        foreignKey(c, "FILE", "PROJECT_ID,SOURCE_FILE_ID", "PROJECT_FILE", "PROJECT_ID,ID");
        foreignKey(c, "PARSE", "PARSE_RESULT_ID,PROJECT_ID,SOURCE_FILE_ID,FILE_SHA256,PARSE_VERSION",
                "PROJECT_FILE_PARSE_RESULT", "PARSE_RESULT_ID,PROJECT_ID,FILE_ID,FILE_SHA256,PARSE_VERSION");
        foreignKey(c, "ROLE", "ROLE_DECISION_ID,PROJECT_ID,SOURCE_FILE_ID,FILE_SHA256,PARSE_RESULT_ID,PARSE_VERSION,EFFECTIVE_ROLE",
                "PROJECT_FILE_ROLE_DECISION", "DECISION_ID,PROJECT_ID,FILE_ID,FILE_SHA256,PARSE_RESULT_ID,PARSE_VERSION,SELECTED_ROLE");
        foreignKey(c, "IDENTITY", "IDENTITY_DECISION_ID,PROJECT_ID,PROJECT_VERSION_NO,SOURCE_FILE_ID,FILE_SHA256,PARSE_RESULT_ID,PARSE_VERSION,IDENTITY_RESOLUTION",
                "PROJECT_FILE_IDENTITY_DECISION", "DECISION_ID,PROJECT_ID,PROJECT_VERSION_NO,FILE_ID,FILE_SHA256,PARSE_RESULT_ID,PARSE_VERSION,RESOLUTION");
        foreignKey(c, "CREATED_BY", "CREATED_BY", "SYS_USER", "ID");
        check(c, "CONTRACT_CANDIDATE", "TUPLE", "(SUPPORT_AUTHORITY_VERSION_NO IS NULL AND SUPPORT_AUTHORITY_FINGERPRINT IS NULL) OR "
                + "(SUPPORT_AUTHORITY_VERSION_NO IS NOT NULL AND SUPPORT_AUTHORITY_FINGERPRINT IS NOT NULL)");
        check(c, "CONTRACT_CANDIDATE", "VERSION", "SUPPORT_AUTHORITY_VERSION_NO IS NULL OR SUPPORT_AUTHORITY_VERSION_NO > 0");
        check(c, "CONTRACT_CANDIDATE", "FINGERPRINT", "SUPPORT_AUTHORITY_FINGERPRINT IS NULL OR "
                + "(LENGTH(SUPPORT_AUTHORITY_FINGERPRINT) = 64 AND SUPPORT_AUTHORITY_FINGERPRINT = LOWER(SUPPORT_AUTHORITY_FINGERPRINT) "
                + "AND REGEXP_LIKE(SUPPORT_AUTHORITY_FINGERPRINT, '^[0-9a-f]{64}$'))");
        check(c, SUPPORT, "ROLE", "EFFECTIVE_ROLE = 'AWARD_NOTICE'");
        check(c, SUPPORT, "IDENTITY", "IDENTITY_RESOLUTION = 'CONFIRM_SAME_PROJECT'");
        check(c, SUPPORT, "VERSIONS", "PARSE_VERSION > 0 AND AUTHORITY_VERSION_NO > 0 AND PROJECT_VERSION_NO > 0");
        check(c, SUPPORT, "HASHES", "LENGTH(FILE_SHA256) = 64 AND FILE_SHA256 = LOWER(FILE_SHA256) "
                + "AND REGEXP_LIKE(FILE_SHA256, '^[0-9a-f]{64}$') AND LENGTH(SOURCE_FACT_FINGERPRINT) = 64 "
                + "AND SOURCE_FACT_FINGERPRINT = LOWER(SOURCE_FACT_FINGERPRINT) "
                + "AND REGEXP_LIKE(SOURCE_FACT_FINGERPRINT, '^[0-9a-f]{64}$')");
        index(c, "CURRENT", "PROJECT_ID,CANDIDATE_ID,AUTHORITY_VERSION_NO,SOURCE_FILE_ID");
        index(c, "SOURCE", "PROJECT_ID,SOURCE_FILE_ID,CANDIDATE_ID");
        index(c, "ROLE_DECISION", "ROLE_DECISION_ID,PROJECT_ID,SOURCE_FILE_ID,PARSE_RESULT_ID,PARSE_VERSION");
        index(c, "IDENTITY_DECISION", "IDENTITY_DECISION_ID,PROJECT_ID,SOURCE_FILE_ID,PARSE_RESULT_ID,PARSE_VERSION");
        index(c, "PARSE_EVIDENCE", "PROJECT_ID,SOURCE_FILE_ID,PARSE_RESULT_ID");
    }

    private static void column(Connection c, String table, String name, String type, int width, String nullable)
            throws SQLException {
        List<List<String>> rows = query(c, "SELECT DATA_TYPE,DATA_LENGTH,NULLABLE,DATA_DEFAULT,DATA_PRECISION,DATA_SCALE FROM USER_TAB_COLUMNS "
                + "WHERE TABLE_NAME=? AND COLUMN_NAME=?", table, name);
        assertEquals(1, rows.size(), "catalog column missing: " + name);
        List<String> row = rows.get(0);
        assertEquals(type, row.get(0), "column type: " + name);
        if (width >= 0) assertEquals(Integer.toString(width), row.get(1), "column width: " + name);
        assertEquals(nullable, row.get(2), "column nullability: " + name);
        assertNull(row.get(3), "unexpected column default: " + name);
        assertNull(row.get(4), "unexpected column precision: " + name);
        assertEquals("TIMESTAMP".equals(type) ? "6" : "0", row.get(5), "column scale: " + name);
    }

    private static void key(Connection c, String table, String name, String type, String columns) throws SQLException {
        assertEquals(List.of(List.of(table, type, "ENABLED")), query(c,
                "SELECT TABLE_NAME,CONSTRAINT_TYPE,STATUS FROM USER_CONSTRAINTS WHERE CONSTRAINT_NAME=?", name),
                "catalog key mismatch: " + name);
        assertEquals(Arrays.asList(columns.split(",")), query(c,
                "SELECT COLUMN_NAME FROM USER_CONS_COLUMNS WHERE CONSTRAINT_NAME=? ORDER BY POSITION", name)
                .stream().map(row -> row.get(0)).toList(), "key tuple mismatch: " + name);
    }

    private static void foreignKey(Connection c, String suffix, String child, String parent, String parentColumns)
            throws SQLException {
        String name = "FK_" + SUPPORT + "_" + suffix;
        key(c, SUPPORT, name, "R", child);
        List<List<String>> rows = query(c, "SELECT P.TABLE_NAME,P.CONSTRAINT_NAME,P.CONSTRAINT_TYPE,P.STATUS,C.DELETE_RULE,"
                + "CASE WHEN C.R_OWNER=USER THEN 'LOCAL' ELSE 'FOREIGN' END FROM USER_CONSTRAINTS C "
                + "JOIN USER_CONSTRAINTS P ON P.CONSTRAINT_NAME=C.R_CONSTRAINT_NAME WHERE C.CONSTRAINT_NAME=?", name);
        assertEquals(1, rows.size(), "parent key missing: " + name);
        List<String> row = rows.get(0);
        assertEquals(parent, row.get(0)); assertTrue(Set.of("P", "U").contains(row.get(2)));
        assertEquals("ENABLED", row.get(3)); assertEquals("NO ACTION", row.get(4)); assertEquals("LOCAL", row.get(5));
        key(c, parent, row.get(1), row.get(2), parentColumns);
    }

    private static void check(Connection c, String table, String suffix, String condition) throws SQLException {
        String name = "CK_CONTRACT_CANDIDATE_SUPPORT_AUTHORITY_" + suffix;
        List<List<String>> rows = query(c,
                "SELECT TABLE_NAME,CONSTRAINT_TYPE,STATUS,SEARCH_CONDITION FROM USER_CONSTRAINTS WHERE CONSTRAINT_NAME=?", name);
        assertEquals(1, rows.size(), "check constraint missing: " + name);
        assertEquals(List.of(table, "C", "ENABLED"), rows.get(0).subList(0, 3));
        assertEquals(normalizeCheck(condition), normalizeCheck(rows.get(0).get(3)), "check expression drift: " + name);
    }

    private static String normalizeCheck(String value) {
        // Preserve all parentheses/operators/literals; remove only whitespace and identifier quoting.
        String normalized = value.replaceAll("\\s+", "").replace("\"", "");
        // Observed on representative DM8: IS NOT NULL is rendered as NOT (column IS NULL).
        normalized = normalized.replaceAll("NOT\\(([A-Z_][A-Z_0-9]*)ISNULL\\)", "$1ISNOTNULL");
        while (normalized.startsWith("(") && normalized.endsWith(")")) {
            int depth = 0; boolean outer = true;
            for (int i = 0; i < normalized.length() - 1; i++) {
                if (normalized.charAt(i) == '(') depth++;
                if (normalized.charAt(i) == ')') depth--;
                if (depth == 0) { outer = false; break; }
            }
            if (!outer) break;
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized;
    }

    private static void index(Connection c, String suffix, String columns) throws SQLException {
        String name = "IDX_" + SUPPORT + "_" + suffix;
        assertEquals(List.of(List.of(SUPPORT, "NONUNIQUE", "VALID")), query(c,
                "SELECT TABLE_NAME,UNIQUENESS,STATUS FROM USER_INDEXES WHERE INDEX_NAME=?", name));
        List<List<String>> rows = query(c,
                "SELECT COLUMN_NAME,DESCEND FROM USER_IND_COLUMNS WHERE INDEX_NAME=? ORDER BY COLUMN_POSITION", name);
        assertEquals(Arrays.asList(columns.split(",")), rows.stream().map(row -> row.get(0)).toList());
        assertTrue(rows.stream().allMatch(row -> "ASC".equals(row.get(1))), "index sort drift: " + name);
    }

    private static void productionLockProbe(Fixture f, List<String> supportIds, List<String> acquired,
            java.util.function.Consumer<String> attempting) {
        safe(() -> {
            try (Connection c = connect(); SqlSession session = sessions.openSession(c)) {
                try {
                    Dm8ProjectRepositories repositories = repositories(session);
                    ProjectFileRepository files = spy(repositories.projectFileRepository());
                    doAnswer(call -> {
                        String id = call.getArgument(1).toString(); attempting.accept(id);
                        Object result = call.callRealMethod(); acquired.add(id); return result;
                    }).when(files).findByProjectIdAndIdForUpdate(any(), any());
                    var projects = spy(repositories.projectRepository());
                    doAnswer(call -> { Object result = call.callRealMethod(); acquired.add("PROJECT"); return result; })
                            .when(projects).findByIdForUpdate(any());
                    CandidateSourceAuthorityValidator validationBoundary = mock(CandidateSourceAuthorityValidator.class);
                    when(validationBoundary.snapshot(any())).thenThrow(new LockProbeFinished());
                    IdempotencyPort unusedIdempotency = mock(IdempotencyPort.class);
                    when(unusedIdempotency.findByScopeAndKey(any(), any())).thenReturn(Optional.empty());
                    var coordinator = new ContractCandidateSupportReplacementTransactionCoordinator(projects, files,
                            repositories.contractCandidateRepository(), validationBoundary,
                            mock(CandidateSupportAuthorityValidator.class), repositories.extractionResultRepository(),
                            new Dm8ContractCandidateSupportAuthorityRepository(session.getMapper(ContractCandidateSupportAuthorityMapper.class)),
                            mock(ContractCandidateAssembler.class), unusedIdempotency, mock(OperationAuditPort.class), Clock.systemUTC());
                    assertThrows(LockProbeFinished.class, () -> coordinator.replace(
                            new ReplaceContractCandidateSupportAuthorityCommand(UUID.fromString(f.project),
                                    UUID.fromString(f.candidate), 0, supportIds.stream().map(UUID::fromString).toList(),
                                    id(), new ProjectRequestMetadata("p1b-it", "p1b-it", null)),
                            new CurrentUser(f.actor, "synthetic-probe", "synthetic-probe", Set.of("ADMIN"))));
                    verify(unusedIdempotency, never()).claim(any());
                } finally { c.rollback(); }
            }
        });
    }

    private static boolean replaceAtVersionZero(Fixture f, Source selected, CountDownLatch ready, CountDownLatch start) {
        try (Connection c = connect(); SqlSession session = sessions.openSession(c)) {
            ready.countDown(); await(start);
            try {
                replaceThroughProductionRepositories(session, f, selected, 0);
                c.commit(); return true;
            } catch (BusinessException rejected) {
                assertEquals(BusinessErrorCode.CONFLICT, rejected.errorCode());
                assertEquals("candidate derived facts conflict", rejected.getMessage(),
                        "only the production zero-row CAS result is an expected loser");
                c.rollback(); return false;
            } finally { c.rollback(); }
        } catch (Exception failure) { throw sanitized(failure); }
    }

    private static void replaceThroughProductionRepositories(SqlSession session, Fixture f, Source selected, int failureStage) {
        var candidates = repositories(session).contractCandidateRepository();
        var supports = new Dm8ContractCandidateSupportAuthorityRepository(session.getMapper(ContractCandidateSupportAuthorityMapper.class));
        candidates.findByProjectIdAndIdForUpdate(UUID.fromString(f.project), UUID.fromString(f.candidate)).orElseThrow();
        supports.deleteCurrentByProjectIdAndCandidateId(UUID.fromString(f.project), UUID.fromString(f.candidate));
        if (failureStage == 1) throw new InjectedFailure();
        var line = new ContractCandidateLineItem(UUID.randomUUID(), UUID.fromString(f.project),
                UUID.fromString(f.candidate), "synthetic-new", "synthetic-new", null, null, null, 0);
        candidates.replaceDraftDerivedFactsIfVersionMatches(UUID.fromString(f.project), UUID.fromString(f.candidate), 0,
                new ContractCandidateDerivedFacts(null, null, null, null, null, null, null, null, null, null, null, List.of(line)),
                2, NEXT_HASH, Instant.parse("2026-09-20T00:00:01Z"));
        if (failureStage == 2) throw new InjectedFailure();
        session.getMapper(ContractCandidateSupportAuthorityMapper.class).insertAll(List.of(
                new ContractCandidateSupportAuthorityPersistenceRow(id(), f.project, f.candidate, selected.id,
                        HASH, selected.parse, 1, 1, "AWARD_NOTICE", selected.role, "CONFIRM_SAME_PROJECT", selected.identity,
                        NEXT_HASH, 2, f.actor, NOW)));
        if (failureStage == 3) throw new InjectedFailure();
    }

    private static Dm8ProjectRepositories repositories(SqlSession session) {
        return new Dm8ProjectRepositories(session.getMapper(ProjectMapper.class), session.getMapper(ProjectFileMapper.class),
                null, session.getMapper(ContractCandidateMapper.class));
    }

    private static List<Object> snapshot(Fixture f) throws SQLException {
        try (Connection c = connect(); SqlSession session = sessions.openSession(c)) {
            var candidate = session.getMapper(ContractCandidateMapper.class);
            List<Object> state = List.of(candidate.findByProjectAndId(f.project, f.candidate),
                    candidate.findLinesByCandidate(f.project, f.candidate),
                    session.getMapper(ContractCandidateSupportAuthorityMapper.class)
                            .findCurrentByProjectAndCandidate(f.project, f.candidate));
            c.rollback(); return state;
        }
    }

    private static Fixture committedFixture() throws SQLException {
        requireWriteGate(); Fixture f = new Fixture();
        // Print only generated test resource IDs, before any possible commit, for interrupted-run recovery.
        System.out.println("P1B synthetic fixture Project IDs=" + f.project + "," + f.otherProject);
        try (Connection c = connect()) {
            try { f.seed(c); c.commit(); }
            // A failed/uncertain commit is not permission to delete unproven durable state.
            // The printed generated IDs support an operator's separate recovery decision.
            catch (Throwable failure) { c.rollback(); throw failure; }
        }
        return f;
    }

    private static final class Fixture implements AutoCloseable {
        boolean cleanupSafe = true;
        final String project = id(), otherProject = id(), candidate = id(), secondCandidate = id();
        final String actor = UUID.fromString(required("AUDIT_P1B_DM8_ACTOR_ID")).toString();
        final Source primary = new Source(), secondPrimary = new Source(), a = new Source(), b = new Source(), foreign = new Source();

        void seed(Connection c) throws SQLException {
            assertEquals(1, count(c, "SYS_USER", "ID", actor), "approved test actor must already exist");
            for (String projectId : List.of(project, otherProject)) insert(c, "PROJECT",
                    row("ID", projectId, "CREATED_BY", actor, "VERSION_NO", 1));
            for (Source source : List.of(primary, secondPrimary, a, b)) source.seed(c, project, actor);
            foreign.seed(c, otherProject, actor);
            for (var entry : Map.of(candidate, primary.id, secondCandidate, secondPrimary.id).entrySet()) {
                insert(c, "CONTRACT_CANDIDATE", row("ID", entry.getKey(), "PROJECT_ID", project,
                        "SOURCE_CONTRACT_FILE_ID", entry.getValue(), "PROMOTION_STATE", "DRAFT", "VERSION_NO", 0,
                        "SUPPORT_AUTHORITY_VERSION_NO", 1, "SUPPORT_AUTHORITY_FINGERPRINT", HASH));
            }
            insert(c, SUPPORT, support(a, candidate, 1));
            insert(c, "CONTRACT_CANDIDATE_LINE_ITEM", row("ID", id(), "PROJECT_ID", project,
                    "CANDIDATE_ID", candidate, "ITEM_KEY", "synthetic-old", "SORT_NO", 0));
        }

        Map<String, Object> support(Source source, String owner, long version) {
            return row("SUPPORT_AUTHORITY_ID", id(), "PROJECT_ID", project, "CANDIDATE_ID", owner,
                    "AUTHORITY_VERSION_NO", version, "SOURCE_FILE_ID", source.id, "FILE_SHA256", HASH,
                    "PARSE_RESULT_ID", source.parse, "PARSE_VERSION", 1, "PROJECT_VERSION_NO", 1,
                    "EFFECTIVE_ROLE", "AWARD_NOTICE", "ROLE_DECISION_ID", source.role,
                    "IDENTITY_RESOLUTION", "CONFIRM_SAME_PROJECT", "IDENTITY_DECISION_ID", source.identity,
                    "SOURCE_FACT_FINGERPRINT", HASH, "CREATED_BY", actor, "CREATED_AT", NOW);
        }

        @Override public void close() throws SQLException {
            assertTrue(cleanupSafe, "NOT VERIFIED: worker state uncertain; use printed generated IDs for operator recovery");
            // Child-first exact generated Project scopes. No prefixes, LIKE, user rows or external objects.
            try (Connection c = connect()) {
                try {
                    for (String projectId : List.of(project, otherProject)) {
                        for (String table : List.of(SUPPORT, "CONTRACT_CANDIDATE_LINE_ITEM", "CONTRACT_CANDIDATE",
                                "PROJECT_FILE_IDENTITY_DECISION", "PROJECT_FILE_IDENTITY_ASSESSMENT",
                                "PROJECT_FILE_ROLE_DECISION", "PROJECT_FILE_ROLE_ASSESSMENT", "PROJECT_FILE_PARSE_RESULT",
                                "PROJECT_FILE")) update(c, "DELETE FROM " + table + " WHERE PROJECT_ID=?", projectId);
                        update(c, "DELETE FROM PROJECT WHERE ID=?", projectId);
                    }
                    c.commit();
                } finally { c.rollback(); }
            }
            try (Connection observer = connect()) {
                for (String projectId : List.of(project, otherProject)) {
                    assertEquals(0, count(observer, "PROJECT", "ID", projectId), "NOT VERIFIED: synthetic fixture cleanup incomplete");
                    assertEquals(0, count(observer, SUPPORT, "PROJECT_ID", projectId), "NOT VERIFIED: synthetic cohort cleanup incomplete");
                }
                observer.rollback();
            }
            System.out.println("P1B synthetic fixture cleanup verified by independent connection");
        }
    }

    private static final class Source {
        final String id = id(), parse = id(), roleAssessment = id(), role = id(), otherRole = id();
        final String identityAssessment = id(), identity = id(), otherIdentity = id();
        void seed(Connection c, String project, String actor) throws SQLException {
            insert(c, "PROJECT_FILE", row("ID", id, "PROJECT_ID", project, "ORIGINAL_FILENAME", "synthetic-it.txt",
                    "MEDIA_TYPE", "text/plain", "CATEGORY", "AWARD_NOTICE", "INGESTION_STATUS", "AVAILABLE",
                    "PARSE_STATUS", "PARSED", "STORAGE_KEY", id().replace("-", "") + id().replace("-", ""),
                    "SHA256", HASH, "SIZE_BYTES", 1, "PARSED_TEXT", "synthetic"));
            insert(c, "PROJECT_FILE_PARSE_RESULT", row("PARSE_RESULT_ID", parse, "PROJECT_ID", project, "FILE_ID", id,
                    "FILE_SHA256", HASH, "PARSE_VERSION", 1, "PARSE_ORIGIN", "CURRENT_PIPELINE", "PARSE_STATUS", "PARSED",
                    "PARSE_METHOD", "PDF_TEXT", "ENGINE", "synthetic-it", "CANONICAL_TEXT", "synthetic",
                    "CANONICAL_TEXT_SHA256", HASH, "CREATED_AT", NOW, "COMPLETED_AT", NOW));
            Map<String, Object> assessment = row("ASSESSMENT_ID", roleAssessment, "PROJECT_ID", project, "FILE_ID", id,
                    "FILE_SHA256", HASH, "PARSE_RESULT_ID", parse, "PARSE_VERSION", 1, "EXECUTION_STATUS", "PENDING",
                    "ASSESSMENT_SOURCE", "MACHINE_DETERMINISTIC", "ASSESSMENT_VERSION", "synthetic-it", "ROLE_POLICY_VERSION", "synthetic-it",
                    "INPUT_FINGERPRINT", HASH, "ASSESSMENT_FENCE_KEY", HASH);
            insert(c, "PROJECT_FILE_ROLE_ASSESSMENT", assessment);
            for (int version = 1; version <= 2; version++) insert(c, "PROJECT_FILE_ROLE_DECISION",
                    row("DECISION_ID", version == 2 ? role : otherRole, "PROJECT_ID", project, "FILE_ID", id,
                            "FILE_SHA256", HASH, "PARSE_RESULT_ID", parse, "PARSE_VERSION", 1, "ASSESSMENT_ID", roleAssessment,
                            "SELECTED_ROLE", version == 2 ? "AWARD_NOTICE" : "CONTRACT_BODY", "DECISION_STATUS", "CONFIRMED",
                            "DECIDED_BY", actor, "DECIDED_AT", NOW, "VERSION_NO", version));
            insert(c, "PROJECT_FILE_IDENTITY_ASSESSMENT", row("ASSESSMENT_ID", identityAssessment, "PROJECT_ID", project,
                    "PROJECT_VERSION_NO", 1, "FILE_ID", id, "FILE_SHA256", HASH, "PARSE_RESULT_ID", parse, "PARSE_VERSION", 1,
                    "ASSESSMENT_SOURCE", "DETERMINISTIC", "ASSESSMENT_VERSION", "synthetic-it", "IDENTITY_POLICY_VERSION", "synthetic-it",
                    "STATUS", "SAME_PROJECT", "NORMALIZED_INPUT_FINGERPRINT", HASH, "CREATED_AT", NOW, "COMPLETED_AT", NOW));
            for (int version = 1; version <= 2; version++) insert(c, "PROJECT_FILE_IDENTITY_DECISION",
                    row("DECISION_ID", version == 2 ? identity : otherIdentity, "PROJECT_ID", project, "PROJECT_VERSION_NO", 1,
                            "FILE_ID", id, "FILE_SHA256", HASH, "PARSE_RESULT_ID", parse, "PARSE_VERSION", 1,
                            "ASSESSMENT_ID", identityAssessment, "RESOLUTION", version == 2 ? "CONFIRM_SAME_PROJECT" : "CONFIRM_NOT_SAME_PROJECT",
                            "DECIDED_BY", actor, "DECIDED_AT", NOW, "VERSION_NO", version));
        }
    }

    private static void requireWriteGate() {
        assertEquals("true", System.getenv("AUDIT_P1B_DM8_WRITE_VERIFY"),
                "NOT VERIFIED: write/committed-fixture tests require separately authorized AUDIT_P1B_DM8_WRITE_VERIFY=true");
        UUID.fromString(required("AUDIT_P1B_DM8_ACTOR_ID"));
    }

    private static Connection connect() {
        try {
            Connection c = DriverManager.getConnection(required("AUDIT_DB_URL"), required("AUDIT_DB_USERNAME"), required("AUDIT_DB_PASSWORD"));
            c.setAutoCommit(false); c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED); return c;
        } catch (SQLException failure) { throw sanitized(failure); }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        assertTrue(value != null && !value.isBlank(), "NOT VERIFIED: required out-of-band environment missing: " + name);
        return value;
    }

    private static String id() { return UUID.randomUUID().toString(); }
    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) values.put((String) pairs[i], pairs[i + 1]);
        return values;
    }

    private static void insert(Connection c, String table, Map<String, Object> values) throws SQLException {
        update(c, "INSERT INTO " + table + " (" + String.join(",", values.keySet()) + ") VALUES ("
                + String.join(",", Collections.nCopies(values.size(), "?")) + ")", values.values().toArray());
    }

    private static int update(Connection c, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = prepare(c, sql, parameters)) { return statement.executeUpdate(); }
    }

    private static PreparedStatement prepare(Connection c, String sql, Object... parameters) throws SQLException {
        PreparedStatement statement = c.prepareStatement(sql); statement.setQueryTimeout(8);
        for (int i = 0; i < parameters.length; i++) statement.setObject(i + 1, parameters[i]);
        return statement;
    }

    private static List<List<String>> query(Connection c, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = prepare(c, sql, parameters); ResultSet result = statement.executeQuery()) {
            List<List<String>> rows = new ArrayList<>();
            while (result.next()) {
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= result.getMetaData().getColumnCount(); i++) row.add(result.getString(i));
                rows.add(row);
            }
            return rows;
        }
    }

    private static int scalar(Connection c, String sql, Object... parameters) throws SQLException {
        return Integer.parseInt(query(c, sql, parameters).get(0).get(0));
    }
    private static int count(Connection c, String table, String column, String id) throws SQLException {
        return scalar(c, "SELECT COUNT(*) FROM " + table + " WHERE " + column + "=?", id);
    }
    private static void lockFile(Connection c, String project, String file) throws SQLException {
        assertEquals(1, query(c, "SELECT ID FROM PROJECT_FILE WHERE PROJECT_ID=? AND ID=? FOR UPDATE", project, file).size());
    }
    private static void rejected(Connection c, SqlAction action) throws Exception {
        Savepoint savepoint = c.setSavepoint();
        try {
            try { action.run(); fail("invalid tuple was accepted by DM8"); }
            catch (SQLException failure) {
                assertTrue(failure.getSQLState() != null && failure.getSQLState().startsWith("23"),
                        "rejection must be an integrity violation, not a syntax/connection/timeout failure");
            }
        } finally { c.rollback(savepoint); c.releaseSavepoint(savepoint); }
    }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(10, TimeUnit.SECONDS), "bounded concurrency orchestration timed out"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError("probe interrupted"); }
    }
    private static void stop(ExecutorService pool, Fixture fixture) {
        pool.shutdownNow();
        try {
            fixture.cleanupSafe = pool.awaitTermination(15, TimeUnit.SECONDS);
            assertTrue(fixture.cleanupSafe, "NOT VERIFIED: DB worker did not terminate; cleanup uncertain");
        } catch (InterruptedException failure) {
            fixture.cleanupSafe = false;
            Thread.currentThread().interrupt(); throw new AssertionError("worker shutdown interrupted");
        }
    }
    @FunctionalInterface private interface SqlAction { void run() throws Exception; }
    private static void safe(SqlAction action) {
        try { action.run(); }
        catch (AssertionError failure) { throw new AssertionError(failure.getMessage()); }
        catch (Throwable failure) { throw sanitized(failure); }
    }
    private static AssertionError sanitized(Throwable failure) {
        // Deliberately omit cause, driver message, connection properties and SQL-bound values.
        SQLException sql = null;
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException found) { sql = found; break; }
        }
        return new AssertionError("NOT VERIFIED: representative probe failed (" + failure.getClass().getSimpleName()
                + (sql == null ? "" : "; SQLState=" + sql.getSQLState() + "; vendorCode=" + sql.getErrorCode()) + ")");
    }
    private static final class LockProbeFinished extends Error { }
    private static final class InjectedFailure extends RuntimeException { }
}
