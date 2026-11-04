package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ContractReviewRunDm8MapperStructureTest {
    @Test
    void mapperHasDurableInsertAndReopenForExactlyFourManifestTables() throws Exception {
        String xml = Files.readString(Path.of("src", "main", "resources", "mappers", "dm8",
                "ContractReviewRunMapper.xml")).replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        assertThat(xml).contains("INSERT INTO CONTRACT_REVIEW_RUN (", "INSERT INTO CONTRACT_REVIEW_RUN_MATERIAL (",
                "INSERT INTO CONTRACT_REVIEW_STEP (", "INSERT INTO CONTRACT_REVIEW_STEP_ATTEMPT (");
        assertThat(xml).contains("WHERE RUN_ID=#{RUNID}", "ORDER BY ORDINAL",
                "ORDER BY STEP_TYPE", "ORDER BY STEP_ID, ATTEMPT_NO",
                "CONTRACT_SNAPSHOT_CANONICAL_JSON", "INPUT_SNAPSHOT_IDENTITY",
                "SOURCE_CONFIRMATION_ID", "IDENTITY_DECISION_ID", "OPERATION_IDENTITY",
                "EXTERNAL_CALL_STATE", "LEASE_UNTIL");
        assertThat(xml).contains("#{ROW.CONTRACTSNAPSHOTCANONICALJSON,JDBCTYPE=CLOB}");
        assertThat(xml).doesNotContain("INSERT INTO AUDIT_TASK", "INSERT INTO CONTRACT_REVIEW_FINDING");
    }

    @Test
    void latestOwnedLookupUsesAllAuthorityPredicatesAndDeterministicUnlockedLimit() throws Exception {
        String xml = Files.readString(Path.of("src", "main", "resources", "mappers", "dm8",
                "ContractReviewRunMapper.xml"));
        var matcher = Pattern.compile("<select\\s+id=\"findLatestByProjectIdAndContractIdAndRequestedBy\"[^>]*>"
                + "(.*?)</select>", Pattern.DOTALL).matcher(xml);
        assertThat(matcher.find()).as("latest-owned ReviewRun select exists").isTrue();
        String select = matcher.group(1).replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);

        assertThat(select).contains("WHERE PROJECT_ID = #{PROJECTID}",
                "AND CONTRACT_ID = #{CONTRACTID}",
                "AND REQUESTED_BY = #{REQUESTEDBY}",
                "ORDER BY REQUESTED_AT DESC, RUN_ID DESC",
                "FETCH FIRST 1 ROW ONLY");
        assertThat(select).doesNotContain("STATUS", "FOR UPDATE");
    }
}
