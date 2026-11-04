package com.fuquan.audit.contract.application.capability;

import com.fuquan.audit.contract.application.capability.ReviewCapabilityDescriptor.Code;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Ordered metadata around an existing profile; not a manifest generator. */
public record ReviewProfileDescriptor(String version, List<Code> required, List<Code> optional) {
    public ReviewProfileDescriptor {
        Objects.requireNonNull(version);
        required = List.copyOf(required);
        optional = List.copyOf(optional);
        var unique = new HashSet<>(required);
        if (version.isBlank() || required.isEmpty() || unique.size() != required.size()
                || required.stream().anyMatch(Code::extension))
            throw new IllegalArgumentException("invalid required profile capabilities");
        for (var code : optional) {
            if (!unique.add(code) || code.extension())
                throw new IllegalArgumentException("invalid optional profile capabilities");
        }
    }
}
