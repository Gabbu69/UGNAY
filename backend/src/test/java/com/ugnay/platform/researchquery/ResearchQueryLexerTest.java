package com.ugnay.platform.researchquery;

import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ResearchQueryLexerTest {
    private final ResearchQueryLexer lexer = new ResearchQueryLexer();

    @Test
    void tokenizesCaseInsensitiveKeywordsEscapedStringsAndExactSpans() {
        var result = lexer.tokenize("find thesis where TITLE = \"A \\\"quoted\\\" path \\\\ file\"");

        assertThat(result.valid()).isTrue();
        assertThat(result.tokens()).extracting(QueryToken::type).containsExactly(
                QueryToken.Type.FIND, QueryToken.Type.THESIS, QueryToken.Type.WHERE, QueryToken.Type.TITLE,
                QueryToken.Type.EQUAL, QueryToken.Type.STRING, QueryToken.Type.EOF);
        QueryToken string = result.tokens().get(5);
        assertThat(string.stringLiteral()).isEqualTo("A \"quoted\" path \\ file");
        assertThat(string.span().startLine()).isEqualTo(1);
        assertThat(string.span().startColumn()).isEqualTo(27);
        assertThat(string.span().endOffset()).isEqualTo(53);
    }

    @Test
    void reportsUnsafeSqlSyntaxButAllowsSqlWordsInsideResearchText() {
        assertThat(lexer.tokenize("FIND THESIS; DROP TABLE studies").diagnostics())
                .extracting(QueryDiagnostic::code).contains("LEX_UNSAFE_INPUT", "LEX_SQL_KEYWORD");
        assertThat(lexer.tokenize("FIND THESIS WHERE TOPIC = \"select crop varieties\"").valid()).isTrue();
        assertThat(lexer.tokenize("FIND THESIS -- bypass").diagnostics())
                .extracting(QueryDiagnostic::code).contains("LEX_UNSAFE_INPUT");
    }

    @Test
    void enforcesSourceAndTokenLimitsBeforeParsing() {
        assertThat(lexer.tokenize("x".repeat(ResearchQueryLanguage.MAX_SOURCE_LENGTH + 1)).diagnostics())
                .singleElement().extracting(QueryDiagnostic::code).isEqualTo("LEX_SOURCE_LIMIT");
        String many = IntStream.range(0, ResearchQueryLanguage.MAX_TOKENS + 1)
                .mapToObj(index -> "FIND").collect(Collectors.joining(" "));
        assertThat(lexer.tokenize(many).diagnostics()).extracting(QueryDiagnostic::code).contains("LEX_TOKEN_LIMIT");
    }
}
