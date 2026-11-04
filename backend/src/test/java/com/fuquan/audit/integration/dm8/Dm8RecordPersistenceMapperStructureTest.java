package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class Dm8RecordPersistenceMapperStructureTest {

    private static final List<MapperCase> CASES = List.of(
            new MapperCase("ProjectMapper.xml", ProjectPersistenceRow.class,
                    List.of("findById", "findPage")),
            new MapperCase("ProjectFileMapper.xml", ProjectFilePersistenceRow.class,
                    List.of("findByProjectIdAndId", "findByParentAndPath", "findByProjectId")),
            new MapperCase("ProjectExtractionResultMapper.xml", ProjectExtractionPersistenceRow.class,
                    List.of("findByExtractionKey", "findBySource", "findByCandidate", "findByProject")),
            new MapperCase("ContractCandidateMapper.xml", ContractCandidatePersistenceRow.class,
                    List.of("findByProjectAndId", "findBySource", "findByProject")),
            new MapperCase("ContractCandidateMapper.xml", ContractCandidateLineItemPersistenceRow.class,
                    List.of("findLinesByCandidate")),
            new MapperCase("ProjectContractMapper.xml", ProjectContractPersistenceRow.class,
                    List.of("findByProjectId", "findByContractId", "findByCandidateId")));

    @Test
    void planBCRecordRowsUseConstructorMaterializationAndCanonicalSelectOrder() throws Exception {
        for (MapperCase mapperCase : CASES) {
            Configuration configuration = parse(mapperCase.mapperResource());
            String namespace = "com.fuquan.audit.integration.dm8."
                    + mapperCase.mapperResource().replace(".xml", "");
            List<String> expectedColumns = Arrays.stream(mapperCase.rowType().getRecordComponents())
                    .map(RecordComponent::getName)
                    .map(Dm8RecordPersistenceMapperStructureTest::upperSnakeCase)
                    .toList();

            assertThat(mapperCase.rowType().isRecord()).isTrue();
            for (String selectId : mapperCase.selectIds()) {
                MappedStatement statement = configuration.getMappedStatement(namespace + "." + selectId);
                ResultMap resultMap = statement.getResultMaps().get(0);
                assertThat(resultMap.getType()).isEqualTo(mapperCase.rowType());
                assertThat(resultMap.getPropertyResultMappings())
                        .as("%s.%s must not require record setters", namespace, selectId)
                        .isEmpty();

                String sql = normalizedSql(statement);
                assertThat(sql).as("%s.%s must not use SELECT *", namespace, selectId)
                        .doesNotContain("*");
                assertThat(selectedColumns(sql))
                        .as("%s.%s must match canonical record component order", namespace, selectId)
                        .containsExactlyElementsOf(expectedColumns);
            }
        }
    }

    private static Configuration parse(String mapperResource) throws Exception {
        String resource = "mappers/dm8/" + mapperResource;
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        try (InputStream input = Dm8RecordPersistenceMapperStructureTest.class
                .getClassLoader().getResourceAsStream(resource)) {
            assertThat(input).as(resource).isNotNull();
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        return configuration;
    }

    private static String normalizedSql(MappedStatement statement) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("keyword", null);
        parameters.put("offset", 0L);
        parameters.put("endRow", 20L);
        return statement.getBoundSql(parameters).getSql().replaceAll("\\s+", " ")
                .trim().toUpperCase(Locale.ROOT);
    }

    private static List<String> selectedColumns(String sql) {
        assertThat(sql).startsWith("SELECT ");
        int fromIndex = sql.indexOf(" FROM ");
        assertThat(fromIndex).isGreaterThan("SELECT ".length());
        return Arrays.stream(sql.substring("SELECT ".length(), fromIndex).split("\\s*,\\s*"))
                .toList();
    }

    private static String upperSnakeCase(String value) {
        StringBuilder result = new StringBuilder(value.length() + 4);
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            boolean boundary = index > 0 && Character.isUpperCase(current)
                    && (Character.isLowerCase(value.charAt(index - 1))
                    || Character.isDigit(value.charAt(index - 1))
                    || index + 1 < value.length() && Character.isLowerCase(value.charAt(index + 1)));
            if (boundary) result.append('_');
            result.append(Character.toUpperCase(current));
        }
        return result.toString();
    }

    private record MapperCase(String mapperResource, Class<?> rowType, List<String> selectIds) {
    }
}
