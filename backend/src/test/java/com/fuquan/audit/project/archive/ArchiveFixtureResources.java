package com.fuquan.audit.project.archive;

import java.io.IOException;

final class ArchiveFixtureResources {
    private ArchiveFixtureResources() { }

    static byte[] fixed(String name) {
        try (var input = ArchiveFixtureResources.class.getResourceAsStream("/archive-fixtures/" + name)) {
            if (input == null) throw new IllegalStateException("missing fixed archive fixture: " + name);
            return input.readAllBytes();
        } catch (IOException failure) {
            throw new IllegalStateException("cannot read fixed archive fixture: " + name, failure);
        }
    }
}
