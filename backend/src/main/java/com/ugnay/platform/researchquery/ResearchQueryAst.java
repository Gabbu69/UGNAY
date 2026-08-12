package com.ugnay.platform.researchquery;

import java.math.BigDecimal;

public final class ResearchQueryAst {
    private ResearchQueryAst() {}

    public sealed interface Node permits Query, Context, Expression, Literal {
        SourceSpan span();
    }

    public record Query(
            Target target,
            Context context,
            Expression where,
            Algorithm algorithm,
            Ordering ordering,
            BigDecimal requestedLimit,
            SourceSpan limitSpan,
            SourceSpan span) implements Node {}

    public record Context(ContextKind kind, String value, SourceSpan span) implements Node {}

    public sealed interface Expression extends Node permits Logical, Group, Predicate {}

    public record Logical(Expression left, BooleanOperator operator, Expression right, SourceSpan span)
            implements Expression {}

    public record Group(Expression expression, SourceSpan span) implements Expression {}

    public record Predicate(Field field, Comparator comparator, Literal value, SourceSpan span)
            implements Expression {}

    public sealed interface Literal extends Node permits StringLiteral, NumberLiteral {}

    public record StringLiteral(String value, SourceSpan span) implements Literal {}

    public record NumberLiteral(BigDecimal value, SourceSpan span) implements Literal {}

    public record Ordering(SortKey key, Direction direction, SourceSpan span) {}

    public enum Target { THESIS, RELATED }
    public enum ContextKind { PROPOSAL, THESIS, TEXT }
    public enum Algorithm { LEXICAL, TFIDF, SEMANTIC, HYBRID }
    public enum SortKey { RELEVANCE, SIMILARITY, YEAR, TITLE }
    public enum Direction { ASC, DESC }
    public enum BooleanOperator { AND, OR }
    public enum Field { TOPIC, TITLE, KEYWORD, YEAR, DEPARTMENT, METHODOLOGY, RESEARCH_AREA, STATUS, SIMILARITY }
    public enum Comparator { EQUAL, NOT_EQUAL, GREATER, GREATER_EQUAL, LESS, LESS_EQUAL, CONTAINS }
}
