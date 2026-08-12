package com.ugnay.platform.researchquery;

import java.util.List;
import java.util.Map;

public final class ResearchQueryLanguage {
    public static final String VERSION = "ugnay-rql-1.0.0";
    public static final int MAX_SOURCE_LENGTH = 4_096;
    public static final int MAX_TOKENS = 256;
    public static final int MAX_AST_DEPTH = 16;
    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;
    public static final int EXECUTION_TIMEOUT_SECONDS = 5;

    public static final String EBNF = """
            query       ::= FIND target [context] [WHERE expression]
                            [USING algorithm]
                            [ORDER BY sortKey [direction]]
                            [LIMIT integer] EOF ;
            target      ::= THESIS | RELATED ;
            context     ::= TO PROPOSAL string | TO THESIS string | TO TEXT string ;
            expression  ::= orExpr ;
            orExpr      ::= andExpr (OR andExpr)* ;
            andExpr     ::= primary (AND primary)* ;
            primary     ::= "(" expression ")" | predicate ;
            predicate   ::= field comparator value ;
            field       ::= TOPIC | TITLE | KEYWORD | YEAR | DEPARTMENT
                          | METHODOLOGY | RESEARCH_AREA | STATUS | SIMILARITY ;
            comparator  ::= "=" | "!=" | ">" | ">=" | "<" | "<=" | CONTAINS ;
            algorithm   ::= LEXICAL | TFIDF | SEMANTIC | HYBRID ;
            sortKey     ::= RELEVANCE | SIMILARITY | YEAR | TITLE ;
            direction   ::= ASC | DESC ;
            value       ::= string | number ;
            """;

    private ResearchQueryLanguage() {}

    public static GrammarDescription grammar() {
        return new GrammarDescription(VERSION, EBNF,
                List.of("TOPIC", "TITLE", "KEYWORD", "YEAR", "DEPARTMENT", "METHODOLOGY",
                        "RESEARCH_AREA", "STATUS", "SIMILARITY"),
                List.of("=", "!=", ">", ">=", "<", "<=", "CONTAINS"),
                List.of("LEXICAL", "TFIDF", "SEMANTIC", "HYBRID"),
                List.of(
                        "FIND THESIS WHERE TOPIC = \"agriculture\" AND YEAR >= 2022 ORDER BY RELEVANCE",
                        "FIND RELATED TO TEXT \"offline flood warning for campuses\" WHERE SIMILARITY > 70 USING HYBRID",
                        "FIND RELATED WHERE SIMILARITY > 70 ORDER BY SIMILARITY DESC LIMIT 10"),
                Map.of("sourceCharacters", MAX_SOURCE_LENGTH, "tokens", MAX_TOKENS, "astDepth", MAX_AST_DEPTH,
                        "defaultResults", DEFAULT_LIMIT, "maximumResults", MAX_LIMIT,
                        "executionSeconds", EXECUTION_TIMEOUT_SECONDS),
                "The interpreter accepts only this grammar, creates a typed execution plan, uses bound database parameters, "
                        + "never accepts SQL, and never makes an academic decision.");
    }

    public record GrammarDescription(
            String version,
            String ebnf,
            List<String> fields,
            List<String> comparators,
            List<String> algorithms,
            List<String> examples,
            Map<String, Integer> limits,
            String safety) {}
}
