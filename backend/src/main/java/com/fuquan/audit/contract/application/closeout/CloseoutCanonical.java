package com.fuquan.audit.contract.application.closeout;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fuquan.audit.contract.application.reviewrun.ContractSnapshotCanonicalizer;

public final class CloseoutCanonical {
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            // Snapshot identities were computed from exact BigDecimal values. Reading
            // them as doubles, or stripping their scale, changes those frozen bytes.
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));
    private CloseoutCanonical() { }
    public static String json(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (JsonProcessingException failure) { throw new IllegalArgumentException("invalid closeout snapshot", failure); }
    }
    public static JsonNode tree(String value) {
        try { return JSON.readTree(value); }
        catch (JsonProcessingException failure) { throw new IllegalArgumentException("invalid closeout snapshot", failure); }
    }
    public static String sha256(String value) { return ContractSnapshotCanonicalizer.sha256(value); }
}
