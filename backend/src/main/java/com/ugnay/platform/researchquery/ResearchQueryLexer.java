package com.ugnay.platform.researchquery;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.ugnay.platform.researchquery.QueryDiagnostic.Stage.LEXER;
import static com.ugnay.platform.researchquery.QueryToken.Type;

/** Hand-written tokenizer for the deliberately small, non-SQL UGNAY language. */
public final class ResearchQueryLexer {
    private static final Map<String, Type> KEYWORDS = keywords();
    private static final Set<String> UNSAFE_SQL_WORDS = Set.of(
            "SELECT", "INSERT", "DELETE", "UPDATE", "DROP", "ALTER", "CREATE", "TRUNCATE",
            "UNION", "GRANT", "REVOKE", "CALL", "EXEC", "EXECUTE", "MERGE", "FROM", "JOIN",
            "HAVING", "GROUP", "INTO");

    public LexResult tokenize(String input) {
        if (input == null) {
            SourceSpan span = new SourceSpan(0, 0, 1, 1, 1, 1);
            return new LexResult(List.of(new QueryToken(Type.EOF, "", null, span)),
                    List.of(new QueryDiagnostic(LEXER, "LEX_SOURCE_REQUIRED",
                            "Enter a UGNAY research query.", span, List.of("FIND"))));
        }
        if (input.length() > ResearchQueryLanguage.MAX_SOURCE_LENGTH) {
            SourceSpan span = new SourceSpan(0, input.length(), 1, 1, lineAtEnd(input), columnAtEnd(input));
            return new LexResult(List.of(new QueryToken(Type.EOF, "", null,
                            new SourceSpan(0, 0, 1, 1, 1, 1))),
                    List.of(new QueryDiagnostic(LEXER, "LEX_SOURCE_LIMIT",
                            "The query exceeds the 4,096-character safety limit.", span, List.of())));
        }
        return new Scanner(input).scan();
    }

    public record LexResult(List<QueryToken> tokens, List<QueryDiagnostic> diagnostics) {
        public LexResult {
            tokens = List.copyOf(tokens);
            diagnostics = List.copyOf(diagnostics);
        }

        public boolean valid() { return diagnostics.isEmpty(); }
    }

    private static final class Scanner {
        private final String source;
        private final List<QueryToken> tokens = new ArrayList<>();
        private final List<QueryDiagnostic> diagnostics = new ArrayList<>();
        private int offset;
        private int line = 1;
        private int column = 1;

        private Scanner(String source) { this.source = source; }

        private LexResult scan() {
            while (!atEnd()) {
                skipWhitespace();
                if (atEnd()) break;
                if (tokens.size() >= ResearchQueryLanguage.MAX_TOKENS) {
                    SourceSpan span = pointSpan();
                    diagnostics.add(new QueryDiagnostic(LEXER, "LEX_TOKEN_LIMIT",
                            "The query exceeds the 256-token safety limit.", span, List.of()));
                    break;
                }
                scanToken();
            }
            SourceSpan eof = pointSpan();
            tokens.add(new QueryToken(Type.EOF, "", null, eof));
            return new LexResult(tokens, diagnostics);
        }

        private void scanToken() {
            int startOffset = offset;
            int startLine = line;
            int startColumn = column;
            char current = advance();
            switch (current) {
                case '(' -> add(Type.LEFT_PAREN, startOffset, startLine, startColumn, null);
                case ')' -> add(Type.RIGHT_PAREN, startOffset, startLine, startColumn, null);
                case '=' -> add(Type.EQUAL, startOffset, startLine, startColumn, null);
                case '!' -> {
                    if (match('=')) add(Type.NOT_EQUAL, startOffset, startLine, startColumn, null);
                    else unsafeCharacter(startOffset, startLine, startColumn, "'!' is valid only as part of '!='.");
                }
                case '>' -> add(match('=') ? Type.GREATER_EQUAL : Type.GREATER,
                        startOffset, startLine, startColumn, null);
                case '<' -> add(match('=') ? Type.LESS_EQUAL : Type.LESS,
                        startOffset, startLine, startColumn, null);
                case '"' -> string(startOffset, startLine, startColumn);
                case ';' -> unsafeCharacter(startOffset, startLine, startColumn,
                        "Semicolons are not part of UGNAY RQL; enter one FIND statement only.");
                case '#' -> unsafeCharacter(startOffset, startLine, startColumn,
                        "Comments are not allowed in UGNAY RQL.");
                case '-' -> {
                    if (peek() == '-') advance();
                    unsafeCharacter(startOffset, startLine, startColumn,
                            "Comments and negative literals are not allowed in UGNAY RQL.");
                }
                case '/' -> {
                    if (peek() == '*') advance();
                    unsafeCharacter(startOffset, startLine, startColumn,
                            "SQL comments are not allowed in UGNAY RQL.");
                }
                case '*' -> {
                    if (peek() == '/') advance();
                    unsafeCharacter(startOffset, startLine, startColumn,
                            "Wildcards and SQL comments are not allowed in UGNAY RQL.");
                }
                default -> {
                    if (Character.isDigit(current)) number(startOffset, startLine, startColumn);
                    else if (isIdentifierStart(current)) identifier(startOffset, startLine, startColumn);
                    else unsafeCharacter(startOffset, startLine, startColumn,
                                "Unsupported character '" + current + "'.");
                }
            }
        }

        private void string(int startOffset, int startLine, int startColumn) {
            StringBuilder decoded = new StringBuilder();
            boolean terminated = false;
            while (!atEnd()) {
                char value = advance();
                if (value == '"') {
                    terminated = true;
                    break;
                }
                if (value != '\\') {
                    decoded.append(value);
                    continue;
                }
                if (atEnd()) break;
                char escaped = advance();
                if (escaped == '"' || escaped == '\\') decoded.append(escaped);
                else {
                    SourceSpan span = span(offset - 2, line, Math.max(1, column - 2));
                    diagnostics.add(new QueryDiagnostic(LEXER, "LEX_INVALID_ESCAPE",
                            "Only \\\" and \\\\ escapes are supported inside strings.", span, List.of("\\\"", "\\\\")));
                    decoded.append(escaped);
                }
            }
            if (!terminated) {
                diagnostics.add(new QueryDiagnostic(LEXER, "LEX_UNTERMINATED_STRING",
                        "The string is missing its closing quote.", span(startOffset, startLine, startColumn), List.of("\"")));
                return;
            }
            add(Type.STRING, startOffset, startLine, startColumn, decoded.toString());
        }

        private void number(int startOffset, int startLine, int startColumn) {
            while (Character.isDigit(peek())) advance();
            if (peek() == '.' && Character.isDigit(peekNext())) {
                advance();
                while (Character.isDigit(peek())) advance();
            }
            String value = source.substring(startOffset, offset);
            try {
                add(Type.NUMBER, startOffset, startLine, startColumn, new BigDecimal(value));
            } catch (NumberFormatException exception) {
                diagnostics.add(new QueryDiagnostic(LEXER, "LEX_INVALID_NUMBER",
                        "The numeric literal is not valid.", span(startOffset, startLine, startColumn), List.of("number")));
            }
        }

        private void identifier(int startOffset, int startLine, int startColumn) {
            while (isIdentifierPart(peek())) advance();
            String value = source.substring(startOffset, offset);
            String upper = value.toUpperCase(Locale.ROOT);
            if (UNSAFE_SQL_WORDS.contains(upper)) {
                diagnostics.add(new QueryDiagnostic(LEXER, "LEX_SQL_KEYWORD",
                        "'" + value + "' is a SQL keyword, not a UGNAY RQL keyword.",
                        span(startOffset, startLine, startColumn), List.of()));
                add(Type.UNKNOWN, startOffset, startLine, startColumn, null);
                return;
            }
            add(KEYWORDS.getOrDefault(upper, Type.IDENTIFIER), startOffset, startLine, startColumn, null);
        }

        private void unsafeCharacter(int startOffset, int startLine, int startColumn, String message) {
            SourceSpan span = span(startOffset, startLine, startColumn);
            diagnostics.add(new QueryDiagnostic(LEXER, "LEX_UNSAFE_INPUT", message, span, List.of()));
            add(Type.UNKNOWN, startOffset, startLine, startColumn, null);
        }

        private void skipWhitespace() {
            while (!atEnd() && Character.isWhitespace(peek())) advance();
        }

        private void add(Type type, int startOffset, int startLine, int startColumn, Object literal) {
            tokens.add(new QueryToken(type, source.substring(startOffset, offset), literal,
                    span(startOffset, startLine, startColumn)));
        }

        private SourceSpan span(int startOffset, int startLine, int startColumn) {
            return new SourceSpan(startOffset, offset, startLine, startColumn, line, column);
        }

        private SourceSpan pointSpan() {
            return new SourceSpan(offset, offset, line, column, line, column);
        }

        private boolean atEnd() { return offset >= source.length(); }
        private char peek() { return atEnd() ? '\0' : source.charAt(offset); }
        private char peekNext() { return offset + 1 >= source.length() ? '\0' : source.charAt(offset + 1); }

        private boolean match(char expected) {
            if (peek() != expected) return false;
            advance();
            return true;
        }

        private char advance() {
            char value = source.charAt(offset++);
            if (value == '\n') {
                line++;
                column = 1;
            } else column++;
            return value;
        }
    }

    private static boolean isIdentifierStart(char value) {
        return Character.isLetter(value) || value == '_';
    }

    private static boolean isIdentifierPart(char value) {
        return Character.isLetterOrDigit(value) || value == '_';
    }

    private static int lineAtEnd(String value) {
        int line = 1;
        for (int index = 0; index < value.length(); index++) if (value.charAt(index) == '\n') line++;
        return line;
    }

    private static int columnAtEnd(String value) {
        int last = value.lastIndexOf('\n');
        return value.length() - last;
    }

    private static Map<String, Type> keywords() {
        Map<String, Type> values = new HashMap<>();
        for (Type type : Type.values()) {
            switch (type) {
                case EQUAL, NOT_EQUAL, GREATER, GREATER_EQUAL, LESS, LESS_EQUAL, LEFT_PAREN,
                        RIGHT_PAREN, STRING, NUMBER, IDENTIFIER, UNKNOWN, EOF -> { }
                default -> values.put(type.name(), type);
            }
        }
        return Map.copyOf(values);
    }
}
