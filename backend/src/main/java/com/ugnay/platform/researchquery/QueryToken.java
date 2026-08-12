package com.ugnay.platform.researchquery;

import java.math.BigDecimal;

public record QueryToken(Type type, String lexeme, Object literal, SourceSpan span) {
    public enum Type {
        FIND, THESIS, RELATED, TO, PROPOSAL, TEXT, WHERE, AND, OR,
        USING, LEXICAL, TFIDF, SEMANTIC, HYBRID, ORDER, BY, RELEVANCE,
        SIMILARITY, YEAR, TITLE, ASC, DESC, LIMIT, TOPIC, KEYWORD,
        DEPARTMENT, METHODOLOGY, RESEARCH_AREA, STATUS, CONTAINS,
        EQUAL, NOT_EQUAL, GREATER, GREATER_EQUAL, LESS, LESS_EQUAL,
        LEFT_PAREN, RIGHT_PAREN, STRING, NUMBER, IDENTIFIER, UNKNOWN, EOF
    }

    public String stringLiteral() {
        return literal instanceof String value ? value : null;
    }

    public BigDecimal numberLiteral() {
        return literal instanceof BigDecimal value ? value : null;
    }
}
