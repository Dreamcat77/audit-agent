package com.fuquan.audit.contract.application.project.parse;

import java.util.List;
import java.util.Objects;

public record ParsedProjectDocument(List<Segment> segments, List<Table> tables) {
    public ParsedProjectDocument {
        segments = List.copyOf(Objects.requireNonNull(segments, "segments required"));
        tables = List.copyOf(Objects.requireNonNull(tables, "tables required"));
    }

    public ParsedProjectDocument(List<Segment> segments) {
        this(segments, List.of());
    }

    public String fullText() {
        return segments.stream().map(Segment::text).collect(java.util.stream.Collectors.joining("\n"));
    }

    public record Segment(String sourceLocator, String text, Integer pageNumber, Integer paragraphIndex) {
        public Segment {
            if (sourceLocator == null || sourceLocator.isBlank()) throw new IllegalArgumentException("sourceLocator required");
            if (text == null) throw new IllegalArgumentException("text required");
            if (pageNumber != null && pageNumber < 1) throw new IllegalArgumentException("pageNumber must be positive");
            if (paragraphIndex != null && paragraphIndex < 1) throw new IllegalArgumentException("paragraphIndex must be positive");
        }

        public Segment(String sourceLocator, String text) {
            this(sourceLocator, text, null, null);
        }
    }

    public record Table(String sourceLocator, List<Row> rows) {
        public Table {
            if (sourceLocator == null || sourceLocator.isBlank()) {
                throw new IllegalArgumentException("sourceLocator required");
            }
            rows = List.copyOf(Objects.requireNonNull(rows, "rows required"));
        }
    }

    public record Row(String sourceLocator, List<Cell> cells) {
        public Row {
            if (sourceLocator == null || sourceLocator.isBlank()) {
                throw new IllegalArgumentException("sourceLocator required");
            }
            cells = List.copyOf(Objects.requireNonNull(cells, "cells required"));
        }
    }

    public record Cell(String sourceLocator, String text) {
        public Cell {
            if (sourceLocator == null || sourceLocator.isBlank()) {
                throw new IllegalArgumentException("sourceLocator required");
            }
            if (text == null) {
                throw new IllegalArgumentException("text required");
            }
        }
    }
}
