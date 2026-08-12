package com.ugnay.platform.researchquery;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ResearchQueryContracts {
    private ResearchQueryContracts() {}

    public record ExecuteRequest(String source, Boolean includeTrace, UUID selectedProposalId) {
        public boolean wantsTrace() { return Boolean.TRUE.equals(includeTrace); }
    }

    public record ExecuteResponse(
            String languageVersion,
            boolean valid,
            String status,
            boolean traceIncluded,
            List<TokenView> tokens,
            AstNode ast,
            ValidationView validation,
            ActionView interpretedAction,
            String algorithmVersion,
            String semanticProvider,
            String assessmentStatus,
            WarehouseReference warehouse,
            List<QueryDiagnostic> diagnostics,
            List<ResultView> results,
            long latencyMillis) {
        public ExecuteResponse {
            tokens = tokens == null ? List.of() : List.copyOf(tokens);
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
            results = results == null ? List.of() : List.copyOf(results);
        }
    }

    public record TokenView(String type, String lexeme, Object literal, SourceSpan span) {}

    public record AstNode(String kind, String value, SourceSpan span, List<AstNode> children) {
        public AstNode { children = children == null ? List.of() : List.copyOf(children); }
    }

    public record ValidationView(boolean valid, String completedStage) {}

    public record ActionView(
            String target,
            String contextType,
            boolean contextAuthorized,
            String algorithmVersion,
            String sort,
            String direction,
            int limit,
            int filterCount,
            String executor) {}

    public record WarehouseReference(String status, UUID snapshotId, Instant asOf, String explanation) {}

    public record ResultView(
            int rank,
            UUID id,
            String code,
            String title,
            String academicYear,
            Integer year,
            String department,
            String lifecycleStatus,
            String visibility,
            String abstractText,
            String methodology,
            List<String> keywords,
            List<String> researchAreas,
            Double similarityScore,
            String scoreStatus,
            ScoreComponents components,
            List<String> matchedTerms,
            List<String> explanations,
            boolean restricted) {
        public ResultView {
            keywords = keywords == null ? List.of() : List.copyOf(keywords);
            researchAreas = researchAreas == null ? List.of() : List.copyOf(researchAreas);
            matchedTerms = matchedTerms == null ? List.of() : List.copyOf(matchedTerms);
            explanations = explanations == null ? List.of() : List.copyOf(explanations);
        }
    }

    public record ScoreComponents(double lexical, double tfIdf, Double semantic, double controlledConcept) {}
}
