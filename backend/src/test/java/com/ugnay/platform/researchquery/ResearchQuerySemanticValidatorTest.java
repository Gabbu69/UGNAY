package com.ugnay.platform.researchquery;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ResearchQuerySemanticValidatorTest {
    private final ResearchQueryLexer lexer = new ResearchQueryLexer();
    private final ResearchQueryParser parser = new ResearchQueryParser();
    private final ResearchQuerySemanticValidator validator = new ResearchQuerySemanticValidator();

    @Test
    void requiresContextForRelatedUnlessSelectedProposalIsSupplied() {
        var query = parse("FIND RELATED WHERE SIMILARITY > 70");
        assertThat(validator.validate(query, null).diagnostics()).extracting(QueryDiagnostic::code)
                .containsExactly("SEM_RELATED_CONTEXT_REQUIRED");

        var valid = validator.validate(query, UUID.randomUUID());
        assertThat(valid.valid()).isTrue();
        assertThat(valid.plan().context().kind()).isEqualTo(ResearchQueryAst.ContextKind.PROPOSAL);
    }

    @Test
    void validatesFieldTypesComparatorRangesAndLimit() {
        var invalid = validator.validate(parse(
                "FIND THESIS WHERE TITLE > 7 OR YEAR = 22 OR SIMILARITY > 101 ORDER BY SIMILARITY LIMIT 1.5"), null);

        assertThat(invalid.diagnostics()).extracting(QueryDiagnostic::code).contains(
                "SEM_STRING_REQUIRED", "SEM_STRING_COMPARATOR", "SEM_YEAR_VALUE",
                "SEM_SIMILARITY_TARGET", "SEM_SIMILARITY_RANGE", "SEM_LIMIT_INTEGER");
    }

    @Test
    void appliesDocumentedDefaultsAndExactAlgorithmVersions() {
        var result = validator.validate(parse("FIND THESIS WHERE TOPIC CONTAINS \"agriculture\" USING LEXICAL"), null);

        assertThat(result.valid()).isTrue();
        assertThat(result.plan().limit()).isEqualTo(20);
        assertThat(result.plan().sortKey()).isEqualTo(ResearchQueryAst.SortKey.RELEVANCE);
        assertThat(result.plan().direction()).isEqualTo(ResearchQueryAst.Direction.DESC);
        assertThat(result.plan().algorithmVersion()).isEqualTo("LEXICAL_KEYWORD_V1");
    }

    private ResearchQueryAst.Query parse(String source) {
        var lexed = lexer.tokenize(source);
        assertThat(lexed.valid()).isTrue();
        var parsed = parser.parse(lexed.tokens());
        assertThat(parsed.valid()).isTrue();
        return parsed.query();
    }
}
