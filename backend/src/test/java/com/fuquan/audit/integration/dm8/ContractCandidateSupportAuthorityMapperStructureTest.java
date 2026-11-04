package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class ContractCandidateSupportAuthorityMapperStructureTest {

    @Test
    void cohortQueriesAndWritesUseExactCandidateProjectAndFileScopes() throws Exception {
        String xml = normalized(Path.of("src", "main", "resources", "mappers", "dm8",
                "ContractCandidateSupportAuthorityMapper.xml"));

        assertThat(statement(xml, "SELECT", "FINDCURRENTBYPROJECTANDCANDIDATE"))
                .contains("PROJECT_ID=#{PROJECTID}", "CANDIDATE_ID=#{CANDIDATEID}", "ORDER BY SOURCE_FILE_ID")
                .doesNotContain("FOR UPDATE");
        assertThat(statement(xml, "SELECT", "FINDCURRENTBYPROJECTANDCANDIDATEFORUPDATE"))
                .contains("PROJECT_ID=#{PROJECTID}", "CANDIDATE_ID=#{CANDIDATEID}", "ORDER BY SOURCE_FILE_ID",
                        "FOR UPDATE");
        assertThat(statement(xml, "SELECT", "FINDBYPROJECTANDCANDIDATEANDSOURCEFILE"))
                .contains("PROJECT_ID=#{PROJECTID}", "CANDIDATE_ID=#{CANDIDATEID}",
                        "SOURCE_FILE_ID=#{SOURCEFILEID}");
        assertThat(statement(xml, "DELETE", "DELETECURRENTBYPROJECTANDCANDIDATE"))
                .contains("PROJECT_ID=#{PROJECTID}", "CANDIDATE_ID=#{CANDIDATEID}");
        assertThat(statement(xml, "INSERT", "INSERTALL"))
                .contains("INSERT INTO CONTRACT_CANDIDATE_SUPPORT_AUTHORITY", "<FOREACH COLLECTION=\"ROWS\"");
        String columns = statement(xml, "SQL", "COLUMNS");
        assertThat(columns).isEqualTo("<SQL ID=\"COLUMNS\">SUPPORT_AUTHORITY_ID, PROJECT_ID, CANDIDATE_ID, "
                + "SOURCE_FILE_ID, FILE_SHA256, PARSE_RESULT_ID, PARSE_VERSION, PROJECT_VERSION_NO, "
                + "EFFECTIVE_ROLE, ROLE_DECISION_ID, IDENTITY_RESOLUTION, IDENTITY_DECISION_ID, "
                + "SOURCE_FACT_FINGERPRINT, AUTHORITY_VERSION_NO, CREATED_BY, CREATED_AT");
        String insert = statement(xml, "INSERT", "INSERTALL");
        List<String> bindings = List.of("supportAuthorityId", "projectId", "candidateId", "sourceFileId",
                "fileSha256", "parseResultId", "parseVersion", "projectVersionNo", "effectiveRole",
                "roleDecisionId", "identityResolution", "identityDecisionId", "sourceFactFingerprint",
                "authorityVersion", "createdBy", "createdAt");
        assertThat(bindings).allSatisfy(binding -> assertThat(insert).contains("#{ROW." + binding.toUpperCase(Locale.ROOT) + "}"));
        assertThat(xml).doesNotContain("FINDBYPROJECTID", "CATEGORY");
    }

    @Test
    void extractionReadRemainsBoundToExactProjectSourceAndParse() throws Exception {
        String xml = normalized(Path.of("src", "main", "resources", "mappers", "dm8",
                "ProjectExtractionResultMapper.xml"));
        int start = xml.indexOf("<SELECT ID=\"FINDBYSOURCEANDPARSE\"");
        int end = xml.indexOf("</SELECT>", start);
        String select = xml.substring(start, end);
        assertThat(select).contains("PROJECT_ID=#{PROJECTID}", "SOURCE_FILE_ID=#{SOURCEID}",
                "PARSE_RESULT_ID=#{PARSERESULTID}");
    }

    private static String normalized(Path path) throws Exception {
        return Files.readString(path).replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    private static String statement(String xml, String tag, String id) {
        String open = "<" + tag + " ID=\"" + id + "\"";
        int start = xml.indexOf(open);
        int end = xml.indexOf("</" + tag + ">", start);
        assertThat(start).as("%s statement must exist", id).isGreaterThanOrEqualTo(0);
        assertThat(end).as("%s statement must close", id).isGreaterThan(start);
        return xml.substring(start, end);
    }
}
