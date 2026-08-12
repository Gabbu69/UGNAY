package com.ugnay.platform.researchquery;

import com.ugnay.platform.researchquery.ResearchQueryAst.Group;
import com.ugnay.platform.researchquery.ResearchQueryAst.Logical;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResearchQueryParserTest {
    private final ResearchQueryLexer lexer = new ResearchQueryLexer();
    private final ResearchQueryParser parser = new ResearchQueryParser();

    @Test
    void parsesApprovedExampleAndPreservesAndBeforeOrPrecedence() {
        String source = "FIND THESIS WHERE TOPIC = \"agriculture\" OR YEAR >= 2022 AND STATUS = \"PUBLISHED\" "
                + "USING TFIDF ORDER BY RELEVANCE DESC LIMIT 10";
        var result = parser.parse(lexer.tokenize(source).tokens());

        assertThat(result.valid()).isTrue();
        assertThat(result.query().target()).isEqualTo(ResearchQueryAst.Target.THESIS);
        assertThat(result.query().algorithm()).isEqualTo(ResearchQueryAst.Algorithm.TFIDF);
        assertThat(result.query().requestedLimit()).isEqualByComparingTo("10");
        assertThat(result.query().where()).isInstanceOfSatisfying(Logical.class, outer -> {
            assertThat(outer.operator()).isEqualTo(ResearchQueryAst.BooleanOperator.OR);
            assertThat(outer.right()).isInstanceOfSatisfying(Logical.class,
                    inner -> assertThat(inner.operator()).isEqualTo(ResearchQueryAst.BooleanOperator.AND));
        });
    }

    @Test
    void parsesRelatedContextAndParenthesizedExpression() {
        String source = "FIND RELATED TO TEXT \"offline flood warning\" WHERE (SIMILARITY > 70 OR YEAR = 2024) "
                + "ORDER BY SIMILARITY LIMIT 5";
        var result = parser.parse(lexer.tokenize(source).tokens());

        assertThat(result.valid()).isTrue();
        assertThat(result.query().context().kind()).isEqualTo(ResearchQueryAst.ContextKind.TEXT);
        assertThat(result.query().where()).isInstanceOf(Group.class);
        assertThat(result.query().ordering().direction()).isNull();
    }

    @Test
    void returnsReadableExpectedTokenDiagnosticForMalformedInput() {
        var result = parser.parse(lexer.tokenize("FIND THESIS WHERE YEAR 2022").tokens());

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.stage()).isEqualTo(QueryDiagnostic.Stage.PARSER);
            assertThat(diagnostic.code()).isEqualTo("PARSE_EXPECTED_TOKEN");
            assertThat(diagnostic.expected()).contains(">=", "=");
            assertThat(diagnostic.span().startColumn()).isPositive();
        });
    }

    @Test
    void rejectsExpressionsBeyondTheDocumentedNestingLimit() {
        String source = "FIND THESIS WHERE " + "(".repeat(17) + "YEAR = 2024" + ")".repeat(17);
        var result = parser.parse(lexer.tokenize(source).tokens());

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics()).singleElement().extracting(QueryDiagnostic::code)
                .isEqualTo("PARSE_DEPTH_LIMIT");
    }
}
