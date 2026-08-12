package com.ugnay.platform.researchquery;

import com.ugnay.platform.researchquery.ResearchQueryAst.Algorithm;
import com.ugnay.platform.researchquery.ResearchQueryAst.ContextKind;
import com.ugnay.platform.researchquery.ResearchQueryAst.Direction;
import com.ugnay.platform.researchquery.ResearchQueryAst.Expression;
import com.ugnay.platform.researchquery.ResearchQueryAst.SortKey;
import com.ugnay.platform.researchquery.ResearchQueryAst.Target;

import java.util.UUID;

/** Typed intermediate representation consumed by the interpreter. It cannot contain SQL. */
public record QueryPlan(
        Target target,
        ContextSpec context,
        Expression filter,
        Algorithm algorithm,
        String algorithmVersion,
        SortKey sortKey,
        Direction direction,
        int limit,
        int filterCount) {

    public record ContextSpec(ContextKind kind, String reference, UUID selectedProposalId, SourceSpan span) {}

    public static String version(Algorithm algorithm) {
        return switch (algorithm) {
            case LEXICAL -> "LEXICAL_KEYWORD_V1";
            case TFIDF -> "TF_IDF_COSINE_V1";
            case SEMANTIC -> "SEMANTIC_E5_V1";
            case HYBRID -> "HYBRID_V1_1";
        };
    }
}
