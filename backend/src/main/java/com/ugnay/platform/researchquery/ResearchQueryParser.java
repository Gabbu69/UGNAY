package com.ugnay.platform.researchquery;

import com.ugnay.platform.researchquery.QueryToken.Type;
import com.ugnay.platform.researchquery.ResearchQueryAst.Algorithm;
import com.ugnay.platform.researchquery.ResearchQueryAst.BooleanOperator;
import com.ugnay.platform.researchquery.ResearchQueryAst.Comparator;
import com.ugnay.platform.researchquery.ResearchQueryAst.Context;
import com.ugnay.platform.researchquery.ResearchQueryAst.ContextKind;
import com.ugnay.platform.researchquery.ResearchQueryAst.Direction;
import com.ugnay.platform.researchquery.ResearchQueryAst.Expression;
import com.ugnay.platform.researchquery.ResearchQueryAst.Field;
import com.ugnay.platform.researchquery.ResearchQueryAst.Group;
import com.ugnay.platform.researchquery.ResearchQueryAst.Literal;
import com.ugnay.platform.researchquery.ResearchQueryAst.Logical;
import com.ugnay.platform.researchquery.ResearchQueryAst.NumberLiteral;
import com.ugnay.platform.researchquery.ResearchQueryAst.Ordering;
import com.ugnay.platform.researchquery.ResearchQueryAst.Predicate;
import com.ugnay.platform.researchquery.ResearchQueryAst.Query;
import com.ugnay.platform.researchquery.ResearchQueryAst.SortKey;
import com.ugnay.platform.researchquery.ResearchQueryAst.StringLiteral;
import com.ugnay.platform.researchquery.ResearchQueryAst.Target;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static com.ugnay.platform.researchquery.QueryDiagnostic.Stage.PARSER;

/** Recursive-descent parser. It accepts only the documented UGNAY RQL grammar. */
public final class ResearchQueryParser {
    public ParseResult parse(List<QueryToken> input) {
        List<QueryToken> tokens = input == null || input.isEmpty()
                ? List.of(new QueryToken(Type.EOF, "", null, new SourceSpan(0, 0, 1, 1, 1, 1)))
                : List.copyOf(input);
        try {
            return new State(tokens).parse();
        } catch (ParseFailure failure) {
            return new ParseResult(null, List.of(failure.diagnostic));
        }
    }

    public record ParseResult(Query query, List<QueryDiagnostic> diagnostics) {
        public ParseResult { diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics); }
        public boolean valid() { return query != null && diagnostics.isEmpty(); }
    }

    private static final class State {
        private static final List<Type> FIELD_TOKENS = List.of(Type.TOPIC, Type.TITLE, Type.KEYWORD, Type.YEAR,
                Type.DEPARTMENT, Type.METHODOLOGY, Type.RESEARCH_AREA, Type.STATUS, Type.SIMILARITY);
        private static final List<Type> COMPARATOR_TOKENS = List.of(Type.EQUAL, Type.NOT_EQUAL, Type.GREATER,
                Type.GREATER_EQUAL, Type.LESS, Type.LESS_EQUAL, Type.CONTAINS);

        private final List<QueryToken> tokens;
        private int current;

        private State(List<QueryToken> tokens) { this.tokens = tokens; }

        private ParseResult parse() {
            QueryToken find = consume(Type.FIND, "A research query must start with FIND.", "FIND");
            Target target;
            if (match(Type.THESIS)) target = Target.THESIS;
            else if (match(Type.RELATED)) target = Target.RELATED;
            else throw expected("PARSE_EXPECTED_TARGET", "Choose THESIS or RELATED after FIND.", "THESIS", "RELATED");

            Context context = null;
            if (match(Type.TO)) context = context(previous());

            Expression where = null;
            if (match(Type.WHERE)) where = expression(0);

            Algorithm algorithm = null;
            if (match(Type.USING)) algorithm = algorithm();

            Ordering ordering = null;
            if (match(Type.ORDER)) ordering = ordering(previous());

            BigDecimal limit = null;
            SourceSpan limitSpan = null;
            if (match(Type.LIMIT)) {
                QueryToken value = consume(Type.NUMBER, "LIMIT requires a positive integer.", "integer");
                limit = value.numberLiteral();
                limitSpan = value.span();
            }

            QueryToken eof = consume(Type.EOF,
                    "Unexpected input. Clauses must appear as WHERE, USING, ORDER BY, then LIMIT.", "end of query");
            SourceSpan span = new SourceSpan(find.span().startOffset(), eof.span().endOffset(),
                    find.span().startLine(), find.span().startColumn(), eof.span().endLine(), eof.span().endColumn());
            return new ParseResult(new Query(target, context, where, algorithm, ordering, limit, limitSpan, span), List.of());
        }

        private Context context(QueryToken to) {
            ContextKind kind;
            if (match(Type.PROPOSAL)) kind = ContextKind.PROPOSAL;
            else if (match(Type.THESIS)) kind = ContextKind.THESIS;
            else if (match(Type.TEXT)) kind = ContextKind.TEXT;
            else throw expected("PARSE_EXPECTED_CONTEXT", "TO requires PROPOSAL, THESIS, or TEXT.",
                        "PROPOSAL", "THESIS", "TEXT");
            QueryToken value = consume(Type.STRING, "A context reference must be enclosed in double quotes.", "string");
            return new Context(kind, value.stringLiteral(), SourceSpan.covering(to.span(), value.span()));
        }

        private Expression expression(int depth) {
            return or(depth);
        }

        private Expression or(int depth) {
            Expression result = and(depth);
            while (match(Type.OR)) {
                Expression right = and(depth);
                result = new Logical(result, BooleanOperator.OR, right, SourceSpan.covering(result.span(), right.span()));
            }
            return result;
        }

        private Expression and(int depth) {
            Expression result = primary(depth);
            while (match(Type.AND)) {
                Expression right = primary(depth);
                result = new Logical(result, BooleanOperator.AND, right, SourceSpan.covering(result.span(), right.span()));
            }
            return result;
        }

        private Expression primary(int depth) {
            if (match(Type.LEFT_PAREN)) {
                QueryToken opening = previous();
                if (depth >= ResearchQueryLanguage.MAX_AST_DEPTH) {
                    throw failure("PARSE_DEPTH_LIMIT", "Parenthesized expressions may be at most 16 levels deep.",
                            opening.span(), List.of());
                }
                Expression inside = expression(depth + 1);
                QueryToken closing = consume(Type.RIGHT_PAREN, "Close the parenthesized expression with ')'.", ")");
                return new Group(inside, SourceSpan.covering(opening.span(), closing.span()));
            }
            return predicate();
        }

        private Expression predicate() {
            QueryToken fieldToken = consumeOne(FIELD_TOKENS, "A predicate requires a supported research field.",
                    FIELD_TOKENS.stream().map(Type::name).toArray(String[]::new));
            QueryToken comparatorToken = consumeOne(COMPARATOR_TOKENS, "A predicate requires a supported comparator.",
                    "=", "!=", ">", ">=", "<", "<=", "CONTAINS");
            QueryToken literalToken;
            if (match(Type.STRING, Type.NUMBER)) literalToken = previous();
            else throw expected("PARSE_EXPECTED_VALUE", "A predicate requires a string or numeric value.", "string", "number");
            Literal literal = literalToken.type() == Type.STRING
                    ? new StringLiteral(literalToken.stringLiteral(), literalToken.span())
                    : new NumberLiteral(literalToken.numberLiteral(), literalToken.span());
            return new Predicate(Field.valueOf(fieldToken.type().name()), comparator(comparatorToken.type()), literal,
                    SourceSpan.covering(fieldToken.span(), literalToken.span()));
        }

        private Algorithm algorithm() {
            if (match(Type.LEXICAL)) return Algorithm.LEXICAL;
            if (match(Type.TFIDF)) return Algorithm.TFIDF;
            if (match(Type.SEMANTIC)) return Algorithm.SEMANTIC;
            if (match(Type.HYBRID)) return Algorithm.HYBRID;
            throw expected("PARSE_EXPECTED_ALGORITHM", "USING requires a supported retrieval algorithm.",
                    "LEXICAL", "TFIDF", "SEMANTIC", "HYBRID");
        }

        private Ordering ordering(QueryToken order) {
            consume(Type.BY, "ORDER must be followed by BY.", "BY");
            SortKey key;
            if (match(Type.RELEVANCE)) key = SortKey.RELEVANCE;
            else if (match(Type.SIMILARITY)) key = SortKey.SIMILARITY;
            else if (match(Type.YEAR)) key = SortKey.YEAR;
            else if (match(Type.TITLE)) key = SortKey.TITLE;
            else throw expected("PARSE_EXPECTED_SORT", "ORDER BY requires RELEVANCE, SIMILARITY, YEAR, or TITLE.",
                        "RELEVANCE", "SIMILARITY", "YEAR", "TITLE");
            Direction direction = null;
            QueryToken end = previous();
            if (match(Type.ASC)) {
                direction = Direction.ASC;
                end = previous();
            } else if (match(Type.DESC)) {
                direction = Direction.DESC;
                end = previous();
            }
            return new Ordering(key, direction, SourceSpan.covering(order.span(), end.span()));
        }

        private QueryToken consume(Type type, String message, String... expected) {
            if (check(type)) return advance();
            throw failure("PARSE_EXPECTED_TOKEN", message, peek().span(), List.of(expected));
        }

        private QueryToken consumeOne(List<Type> types, String message, String... expected) {
            for (Type type : types) if (check(type)) return advance();
            throw failure("PARSE_EXPECTED_TOKEN", message, peek().span(), List.of(expected));
        }

        private ParseFailure expected(String code, String message, String... expected) {
            return failure(code, message, peek().span(), Arrays.asList(expected));
        }

        private ParseFailure failure(String code, String message, SourceSpan span, List<String> expected) {
            return new ParseFailure(new QueryDiagnostic(PARSER, code, message, span, expected));
        }

        private boolean match(Type... types) {
            for (Type type : types) {
                if (check(type)) {
                    advance();
                    return true;
                }
            }
            return false;
        }

        private boolean check(Type type) { return peek().type() == type; }

        private QueryToken advance() {
            QueryToken token = peek();
            if (token.type() != Type.EOF) current++;
            return token;
        }

        private QueryToken peek() { return tokens.get(Math.min(current, tokens.size() - 1)); }
        private QueryToken previous() { return tokens.get(Math.max(0, current - 1)); }
    }

    private static Comparator comparator(Type type) {
        return switch (type) {
            case EQUAL -> Comparator.EQUAL;
            case NOT_EQUAL -> Comparator.NOT_EQUAL;
            case GREATER -> Comparator.GREATER;
            case GREATER_EQUAL -> Comparator.GREATER_EQUAL;
            case LESS -> Comparator.LESS;
            case LESS_EQUAL -> Comparator.LESS_EQUAL;
            case CONTAINS -> Comparator.CONTAINS;
            default -> throw new IllegalArgumentException("Not a comparator token: " + type);
        };
    }

    private static final class ParseFailure extends RuntimeException {
        private final QueryDiagnostic diagnostic;
        private ParseFailure(QueryDiagnostic diagnostic) {
            super(diagnostic.message(), null, false, false);
            this.diagnostic = diagnostic;
        }
    }
}
