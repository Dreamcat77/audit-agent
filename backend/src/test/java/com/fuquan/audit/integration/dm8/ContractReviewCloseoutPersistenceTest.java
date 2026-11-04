package com.fuquan.audit.integration.dm8;

import static org.junit.jupiter.api.Assertions.*;
import com.fuquan.audit.contract.domain.closeout.ContractReviewFindingAcknowledgement;
import com.fuquan.audit.contract.infra.memory.closeout.InMemoryContractReviewCloseoutRepository;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

class ContractReviewCloseoutPersistenceTest {
    @Test void dm8ReopensAllAcknowledgementFieldsAndBindsClob() throws Exception {
        var mapper = org.mockito.Mockito.mock(ContractReviewCloseoutMapper.class);
        var fact = ack(UUID.randomUUID(), UUID.randomUUID());
        var row = ContractReviewCloseoutMapper.AcknowledgementRow.from(fact);
        org.mockito.Mockito.when(mapper.findAcknowledgement(fact.acknowledgementId().toString())).thenReturn(row);
        assertEquals(fact, new Dm8ContractReviewCloseoutRepository(mapper).findAcknowledgement(fact.acknowledgementId()).orElseThrow());
        var configuration = new org.apache.ibatis.session.Configuration();
        try (var stream = java.nio.file.Files.newInputStream(java.nio.file.Path.of("src/main/resources/mappers/dm8/ContractReviewCloseoutMapper.xml"))) {
            new org.apache.ibatis.builder.xml.XMLMapperBuilder(stream, configuration, "closeout", configuration.getSqlFragments()).parse();
        }
        var sql = configuration.getMappedStatement(ContractReviewCloseoutMapper.class.getName() + ".insertAcknowledgement").getBoundSql(row);
        assertTrue(sql.getSql().contains("CONTRACT_REVIEW_FINDING_ACKNOWLEDGEMENT"));
        assertEquals(org.apache.ibatis.type.JdbcType.CLOB, sql.getParameterMappings().stream()
                .filter(p -> p.getProperty().equals("handlingNote")).findFirst().orElseThrow().getJdbcType());
    }
    @Test void concurrentFindingInsertsKeepOneImmutableWinner() throws Exception {
        var repo = new InMemoryContractReviewCloseoutRepository();
        UUID finding = UUID.randomUUID(), run = UUID.randomUUID();
        var a = ack(finding, run);
        var b = ack(finding, run);
        var gate = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> insert(repo, a, gate));
            var second = pool.submit(() -> insert(repo, b, gate));
            gate.countDown();
            assertEquals(1, first.get() + second.get());
            var winner = repo.findAcknowledgementByFinding(finding).orElseThrow();
            assertTrue(winner.equals(a) || winner.equals(b));
            assertEquals(1, repo.findAcknowledgementsByRun(run).size());
        } finally { pool.shutdownNow(); }
    }
    private static int insert(InMemoryContractReviewCloseoutRepository repo,
            ContractReviewFindingAcknowledgement ack, CountDownLatch gate) throws Exception {
        gate.await();
        try { repo.insertAcknowledgement(ack); return 1; }
        catch (DuplicateKeyException duplicate) { return 0; }
    }
    private static ContractReviewFindingAcknowledgement ack(UUID finding, UUID run) {
        return new ContractReviewFindingAcknowledgement(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), run, UUID.randomUUID(), UUID.randomUUID(), finding, 0,
                UUID.randomUUID().toString(), Instant.EPOCH, "已查看", "request", "trace", Instant.EPOCH, 0);
    }
}
