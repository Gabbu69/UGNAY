package com.ugnay.platform.researchquery;

import com.ugnay.platform.researchquery.ResearchQueryAst.Algorithm;
import com.ugnay.platform.researchquery.ResearchQueryAst.Comparator;
import com.ugnay.platform.researchquery.ResearchQueryAst.Context;
import com.ugnay.platform.researchquery.ResearchQueryAst.ContextKind;
import com.ugnay.platform.researchquery.ResearchQueryAst.Direction;
import com.ugnay.platform.researchquery.ResearchQueryAst.Expression;
import com.ugnay.platform.researchquery.ResearchQueryAst.Field;
import com.ugnay.platform.researchquery.ResearchQueryAst.Group;
import com.ugnay.platform.researchquery.ResearchQueryAst.Logical;
import com.ugnay.platform.researchquery.ResearchQueryAst.NumberLiteral;
import com.ugnay.platform.researchquery.ResearchQueryAst.Predicate;
import com.ugnay.platform.researchquery.ResearchQueryAst.Query;
import com.ugnay.platform.researchquery.ResearchQueryAst.SortKey;
import com.ugnay.platform.researchquery.ResearchQueryAst.StringLiteral;
import com.ugnay.platform.researchquery.ResearchQueryAst.Target;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.ugnay.platform.researchquery.QueryDiagnostic.Stage.SEMANTIC;

/** Performs type, target, comparator, limit, and bounded-AST validation. */
public final class ResearchQuerySemanticValidator {
    private static final Set<Field> STRING_FIELDS = Set.of(Field.TOPIC, Field.TITLE, Field.KEYWORD,
            Field.DEPARTMENT, Field.METHODOLOGY, Field.RESEARCH_AREA, Field.STATUS);
    private static final Set<Comparator> STRING_COMPARATORS = Set.of(Comparator.EQUAL, Comparator.NOT_EQUAL,
            Comparator.CONTAINS);

    public ValidationResult validate(Query query, UUID selectedProposalId) {
        List<QueryDiagnostic> diagnostics = new ArrayList<>();
        Context context = query.context();
        QueryPlan.ContextSpec contextSpec = null;
        if (context != null) {
            if (context.value() == null || context.value().isBlank()) {
                diagnostics.add(error("SEM_EMPTY_CONTEXT", "A context reference cannot be empty.", context.span()));
            }
            if (context.kind() == ContextKind.PROPOSAL && selectedProposalId != null) {
                parseUuid(context.value()).filter(value -> !value.equals(selectedProposalId)).ifPresent(value ->
                        diagnostics.add(error("SEM_CONFLICTING_CONTEXT",
                                "The query and selected proposal refer to different contexts.", context.span())));
            }
            contextSpec = new QueryPlan.ContextSpec(context.kind(), context.value(),
                    context.kind() == ContextKind.PROPOSAL ? selectedProposalId : null, context.span());
        } else if (selectedProposalId != null) {
            contextSpec = new QueryPlan.ContextSpec(ContextKind.PROPOSAL, selectedProposalId.toString(), selectedProposalId,
                    query.span());
        }

        if (query.target() == Target.RELATED && contextSpec == null) {
            diagnostics.add(error("SEM_RELATED_CONTEXT_REQUIRED",
                    "RELATED requires a target. Add TO PROPOSAL, TO THESIS, TO TEXT, or select an authorized proposal.",
                    query.span()));
        }

        int astDepth = depth(query.where());
        if (astDepth > ResearchQueryLanguage.MAX_AST_DEPTH) {
            diagnostics.add(error("SEM_AST_DEPTH_LIMIT", "The expression AST may be at most 16 levels deep.",
                    query.where().span()));
        }
        inspect(query.where(), query.target(), diagnostics);

        int limit = ResearchQueryLanguage.DEFAULT_LIMIT;
        if (query.requestedLimit() != null) {
            BigDecimal requested = query.requestedLimit();
            if (requested.scale() > 0 && requested.stripTrailingZeros().scale() > 0) {
                diagnostics.add(error("SEM_LIMIT_INTEGER", "LIMIT must be a whole number.", query.limitSpan()));
            } else {
                try {
                    limit = requested.intValueExact();
                    if (limit < 1 || limit > ResearchQueryLanguage.MAX_LIMIT) {
                        diagnostics.add(error("SEM_LIMIT_RANGE", "LIMIT must be between 1 and 100.", query.limitSpan()));
                    }
                } catch (ArithmeticException exception) {
                    diagnostics.add(error("SEM_LIMIT_RANGE", "LIMIT must be between 1 and 100.", query.limitSpan()));
                }
            }
        }

        SortKey sort = query.ordering() == null
                ? (query.target() == Target.RELATED ? SortKey.SIMILARITY : SortKey.RELEVANCE)
                : query.ordering().key();
        if (sort == SortKey.SIMILARITY && query.target() != Target.RELATED) {
            diagnostics.add(error("SEM_SIMILARITY_TARGET",
                    "ORDER BY SIMILARITY is valid only for FIND RELATED.", query.ordering().span()));
        }
        Direction direction = query.ordering() == null || query.ordering().direction() == null
                ? (sort == SortKey.TITLE ? Direction.ASC : Direction.DESC)
                : query.ordering().direction();
        Algorithm algorithm = query.algorithm() == null ? Algorithm.HYBRID : query.algorithm();

        if (!diagnostics.isEmpty()) return new ValidationResult(null, diagnostics);
        QueryPlan plan = new QueryPlan(query.target(), contextSpec, query.where(), algorithm, QueryPlan.version(algorithm),
                sort, direction, limit, countPredicates(query.where()));
        return new ValidationResult(plan, List.of());
    }

    private static void inspect(Expression expression, Target target, List<QueryDiagnostic> diagnostics) {
        if (expression == null) return;
        if (expression instanceof Logical logical) {
            inspect(logical.left(), target, diagnostics);
            inspect(logical.right(), target, diagnostics);
            return;
        }
        if (expression instanceof Group group) {
            inspect(group.expression(), target, diagnostics);
            return;
        }
        Predicate predicate = (Predicate) expression;
        if (STRING_FIELDS.contains(predicate.field())) {
            if (!(predicate.value() instanceof StringLiteral string)) {
                diagnostics.add(error("SEM_STRING_REQUIRED", predicate.field() + " requires a quoted string.",
                        predicate.value().span()));
            } else if (string.value() == null || string.value().isBlank()) {
                diagnostics.add(error("SEM_EMPTY_STRING", predicate.field() + " cannot use an empty string.", string.span()));
            }
            if (!STRING_COMPARATORS.contains(predicate.comparator())) {
                diagnostics.add(error("SEM_STRING_COMPARATOR",
                        predicate.field() + " supports only =, !=, and CONTAINS.", predicate.span()));
            }
            return;
        }

        if (!(predicate.value() instanceof NumberLiteral number)) {
            diagnostics.add(error("SEM_NUMBER_REQUIRED", predicate.field() + " requires a numeric value.",
                    predicate.value().span()));
            return;
        }
        if (predicate.comparator() == Comparator.CONTAINS) {
            diagnostics.add(error("SEM_NUMBER_COMPARATOR", predicate.field() + " does not support CONTAINS.",
                    predicate.span()));
        }
        if (predicate.field() == Field.YEAR) {
            if (!whole(number.value()) || number.value().compareTo(BigDecimal.valueOf(1_000)) < 0
                    || number.value().compareTo(BigDecimal.valueOf(9_999)) > 0) {
                diagnostics.add(error("SEM_YEAR_VALUE", "YEAR requires a four-digit whole number.", number.span()));
            }
        } else if (predicate.field() == Field.SIMILARITY) {
            if (target != Target.RELATED) {
                diagnostics.add(error("SEM_SIMILARITY_TARGET",
                        "SIMILARITY is valid only for FIND RELATED.", predicate.span()));
            }
            if (number.value().compareTo(BigDecimal.ZERO) < 0
                    || number.value().compareTo(BigDecimal.valueOf(100)) > 0) {
                diagnostics.add(error("SEM_SIMILARITY_RANGE", "SIMILARITY must be between 0 and 100.", number.span()));
            }
        }
    }

    private static int depth(Expression expression) {
        if (expression == null) return 0;
        if (expression instanceof Predicate) return 1;
        if (expression instanceof Group group) return 1 + depth(group.expression());
        Logical logical = (Logical) expression;
        return 1 + Math.max(depth(logical.left()), depth(logical.right()));
    }

    private static int countPredicates(Expression expression) {
        if (expression == null) return 0;
        if (expression instanceof Predicate) return 1;
        if (expression instanceof Group group) return countPredicates(group.expression());
        Logical logical = (Logical) expression;
        return countPredicates(logical.left()) + countPredicates(logical.right());
    }

    private static boolean whole(BigDecimal value) {
        return value != null && value.stripTrailingZeros().scale() <= 0;
    }

    private static java.util.Optional<UUID> parseUuid(String value) {
        try { return java.util.Optional.of(UUID.fromString(value)); }
        catch (RuntimeException exception) { return java.util.Optional.empty(); }
    }

    private static QueryDiagnostic error(String code, String message, SourceSpan span) {
        return new QueryDiagnostic(SEMANTIC, code, message, span, List.of());
    }

    public record ValidationResult(QueryPlan plan, List<QueryDiagnostic> diagnostics) {
        public ValidationResult { diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics); }
        public boolean valid() { return plan != null && diagnostics.isEmpty(); }
    }
}
