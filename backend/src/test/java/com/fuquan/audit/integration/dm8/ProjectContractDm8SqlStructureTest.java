package com.fuquan.audit.integration.dm8;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectContractDm8SqlStructureTest {

    @Test
    void v011CreatesTheOnlySameProjectCandidateAssociationWithoutChangingContract() throws Exception {
        Path migration = Path.of("..", "db", "dm8", "V011__project_contract_association.sql");
        assertThat(migration).exists();
        String sql = Files.readString(migration).toUpperCase();

        assertThat(sql).contains("CREATE TABLE PROJECT_CONTRACT")
                .contains("PROJECT_ID VARCHAR(36) NOT NULL")
                .contains("CONTRACT_ID VARCHAR(36) NOT NULL")
                .contains("CANDIDATE_ID VARCHAR(36) NOT NULL")
                .contains("CREATED_BY VARCHAR(36) NOT NULL")
                .contains("CREATED_AT TIMESTAMP")
                .contains("PRIMARY KEY (PROJECT_ID, CONTRACT_ID)")
                .contains("UNIQUE (CONTRACT_ID)")
                .contains("UNIQUE (CANDIDATE_ID)")
                .contains("FOREIGN KEY (PROJECT_ID) REFERENCES PROJECT (ID)")
                .contains("FOREIGN KEY (CONTRACT_ID) REFERENCES CONTRACT (ID)")
                .contains("FOREIGN KEY (CREATED_BY) REFERENCES SYS_USER (ID)")
                .contains("FOREIGN KEY (PROJECT_ID, CANDIDATE_ID)")
                .contains("REFERENCES CONTRACT_CANDIDATE (PROJECT_ID, ID)")
                .doesNotContain("ALTER TABLE CONTRACT")
                .doesNotContain("PROJECT_ID TO CONTRACT")
                .doesNotContain(" MERGE ")
                .doesNotContain("DELETE")
                .doesNotContain("ARCHIVE");
    }
}
