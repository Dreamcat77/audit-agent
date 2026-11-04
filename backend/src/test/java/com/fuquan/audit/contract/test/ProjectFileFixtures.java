package com.fuquan.audit.contract.test;

import java.io.InputStream;

public final class ProjectFileFixtures {
    private static final String ROOT = "/project-file-fixtures/";

    private ProjectFileFixtures() {
    }

    public static byte[] bytes(String name) {
        return resource(ROOT + name);
    }

    public static byte[] archiveBytes(String name) {
        return resource("/archive-fixtures/" + name);
    }

    private static byte[] resource(String path) {
        try (InputStream input = ProjectFileFixtures.class.getResourceAsStream(path)) {
            if (input == null) throw new IllegalArgumentException("missing project file fixture: " + path);
            return input.readAllBytes();
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("cannot read project file fixture: " + path, failure);
        }
    }
}
