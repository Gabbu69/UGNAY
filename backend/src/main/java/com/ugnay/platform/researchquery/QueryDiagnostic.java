package com.ugnay.platform.researchquery;

import java.util.List;

public record QueryDiagnostic(
        Stage stage,
        String code,
        String message,
        SourceSpan span,
        List<String> expected) {

    public QueryDiagnostic {
        expected = expected == null ? List.of() : List.copyOf(expected);
    }

    public enum Stage { LEXER, PARSER, SEMANTIC, EXECUTION }
}
