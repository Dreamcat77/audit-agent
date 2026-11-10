package com.fuquan.audit.common.privacy;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Immutable per-process policy. Restart workers after an operator changes the DB. */
@Component
public final class ClientReviewIdentity {
    public static final String HEADER = "X-Audit-Client-Identity";
    private final ClientIdentityRepository repository;
    private List<String> snapshot;

    public ClientReviewIdentity(ClientIdentityRepository repository) { this.repository=repository; }

    private synchronized List<String> names() {
        if (snapshot == null) {
            var supplied=repository.enabledNames();
            if (supplied == null || supplied.isEmpty() || supplied.size()>64)
                throw new IllegalStateException("CLIENT_IDENTITY_NOT_CONFIGURED");
            var checked=new LinkedHashSet<String>();
            for (var name:supplied) {
                if (name==null) throw new IllegalStateException("CLIENT_IDENTITY_INVALID");
                var normalized=normalize(name);
                if (normalized.codePointCount(0,normalized.length())<2 || normalized.length()>120
                        || Set.of("甲方","乙方","我方","客户").contains(normalized))
                    throw new IllegalStateException("CLIENT_IDENTITY_INVALID");
                checked.add(normalized);
            }
            snapshot=List.copyOf(checked);
        }
        return snapshot;
    }

    public String internalHeader() {
        try {
            var body=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(
                Map.of("version",1,"names",names()));
            var encoded=Base64.getEncoder().encodeToString(body);
            if(encoded.length()>8000) throw new IllegalStateException("CLIENT_IDENTITY_HEADER_BOUND");
            return encoded;
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalStateException("CLIENT_IDENTITY_INVALID");
        }
    }

    public String party(String fullText) {
        if(!repository.required()) return "UNMATCHED";
        return ClientPartyMatcher.party(fullText,names());
    }

    public Map<UUID,String> parties(Map<UUID,String> fullTexts,List<UUID> selected) {
        var result=new LinkedHashMap<UUID,String>();
        for(var id:selected) result.put(id,party(fullTexts.getOrDefault(id,"")));
        return Map.copyOf(result);
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value,Normalizer.Form.NFKC).replaceAll("(?U)\\s+","").strip();
    }
}
