package com.ugnay.platform.shared;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PlatformModels {
    private PlatformModels() {}

    public enum Recommendation { NEW, IMPROVE, CONTINUE, POSSIBLE_DUPLICATE, REVIEW_REQUIRED }
    public enum DecisionDisposition { APPROVE_NEW, APPROVE_IMPROVE, APPROVE_CONTINUE, RETURN_FOR_REVISION, CLOSE_AS_DUPLICATE }
    public enum TraceItemType { PROBLEM, OBJECTIVE, REQUIREMENT, FEATURE, TEST_CASE, OUTPUT }
    public enum AssessmentStatus { ASSESSED, UNASSESSED, STALE, PARTIAL }
    public enum FindingState { OPEN, ACCEPTED, RESOLVED, REOPENED }
    public enum LineageType { CONTINUES, IMPROVES, ADAPTS, REPLICATES, REFERENCES }
    public enum Severity { INFO, LOW, MODERATE, HIGH, CRITICAL }
    public enum ProjectStatus { BASELINING, ACTIVE, VALIDATING, COMPLETED, SUSPENDED }
    public enum ChangeOperationType { ADD, REVISE, RETIRE, RELINK }
    public enum ChangeDecisionDisposition { APPROVE, REJECT, RETURN_FOR_REVISION }
    public enum ContinuationClaimOutcome { COMPLETED, PARTIAL, DEFERRED, INVALIDATED }

    public record UserSummary(UUID id, String displayName, String email, String department, List<String> roles) {}

    public record Study(
            UUID id,
            String institutionalCode,
            String title,
            String academicYear,
            String department,
            String lifecycleStatus,
            String visibility,
            String abstractText,
            String problemStatement,
            List<String> objectives,
            List<String> keywords,
            String methodology,
            String features,
            String dataSources,
            String technology,
            String intendedUsers,
            String stakeholders,
            String siteContext,
            List<ContinuationItem> continuationItems) {}

    public record ContinuationItem(
            UUID id,
            UUID studyId,
            String type,
            String title,
            String description,
            String status,
            boolean claimed) {}

    public record ProblemCase(
            UUID id,
            String title,
            String problemStatement,
            String stakeholder,
            String affectedUsers,
            String siteContext,
            String desiredOutcome,
            String constraints,
            String privacyClassification,
            String status,
            int evidenceCount,
            Instant createdAt,
            long rowVersion) {}

    public record Proposal(
            UUID id,
            String title,
            String problemStatement,
            String stakeholder,
            String affectedUsers,
            String siteContext,
            String desiredOutcome,
            String constraints,
            String privacyClassification,
            List<String> objectives,
            String proposedSolution,
            String methodology,
            String dataSources,
            String technology,
            String intendedUsers,
            String status,
            Instant submittedAt,
            long rowVersion) {}

    public record ComponentScore(
            String component,
            double rawScore,
            double weight,
            double weightedScore,
            String explanation,
            List<String> matchedTerms) {}

    public record CandidateEvidence(
            String field,
            String proposalExcerpt,
            String studyExcerpt,
            List<ComponentScore> components) {}

    public record DiscoveryCandidate(
            int rank,
            UUID studyId,
            String studyTitle,
            double problemScore,
            double objectiveScore,
            double solutionScore,
            double confidence,
            String similarityBand,
            boolean exactMatch,
            List<CandidateEvidence> evidence) {}

    public record DiscoveryRun(
            UUID id,
            UUID proposalId,
            AssessmentStatus assessmentStatus,
            Recommendation recommendation,
            double confidence,
            String algorithmVersion,
            String inputHash,
            String semanticProvider,
            String explanation,
            List<String> revisionChecklist,
            List<DiscoveryCandidate> candidates,
            Instant createdAt) {}

    public record ProposalDecision(
            UUID id,
            UUID proposalId,
            UUID discoveryRunId,
            DecisionDisposition disposition,
            String rationale,
            String decidedBy,
            Instant decidedAt,
            UUID primaryPredecessorId) {}

    public record Project(
            UUID id,
            String code,
            String title,
            ProjectStatus status,
            Recommendation route,
            String department,
            UUID currentBaselineId,
            int baselineNumber,
            List<String> team,
            Instant updatedAt,
            long rowVersion) {}

    public record TraceItem(
            UUID id,
            String key,
            TraceItemType type,
            String title,
            String description,
            String lifecycleStatus,
            String priority,
            String acceptanceCriteria,
            String verificationMethod,
            int currentRevision,
            double readinessScore) {}

    public record TraceLink(
            UUID id,
            UUID sourceId,
            UUID targetId,
            String type,
            String status,
            String rationale) {}

    public record TestExecution(
            UUID id,
            UUID testItemId,
            String status,
            String buildIdentifier,
            boolean current,
            boolean hasEvidence,
            Instant executedAt) {}

    public record Finding(
            UUID id,
            String code,
            Severity severity,
            FindingState state,
            String title,
            String explanation,
            String nextAction,
            List<UUID> implicatedItemIds,
            String ruleVersion) {}

    public record Coverage(
            AssessmentStatus status,
            double mappedCoverage,
            double executedCoverage,
            double passingCoverage,
            double priorityWeightedPassingCoverage,
            int totalRequirements,
            int verifiedRequirements) {}

    public record Traceability(
            UUID projectId,
            UUID baselineId,
            int baselineNumber,
            AssessmentStatus assessmentStatus,
            List<TraceItem> items,
            List<TraceLink> links,
            List<TestExecution> executions,
            List<Finding> findings,
            Coverage coverage) {}

    public record HealthDimension(String key, String label, AssessmentStatus status, Double score, String band, String explanation) {}

    public record ProjectHealth(
            UUID projectId,
            String overallStatus,
            List<HealthDimension> dimensions,
            int openFindings,
            int criticalFindings,
            Instant calculatedAt,
            String ruleVersion) {}

    public record ScopeRisk(
            AssessmentStatus status,
            Integer score,
            String band,
            int governance,
            int alignment,
            int controlledGrowth,
            int boundary,
            List<String> explanations) {}

    public record ChangeRequest(
            UUID id,
            UUID projectId,
            UUID basedOnBaselineId,
            String title,
            String rationale,
            String status,
            List<UUID> changedItemIds,
            List<String> boundaryFlags,
            Instant createdAt,
            long rowVersion) {}

    public record ChangeOperation(
            UUID id,
            UUID changeRequestId,
            int order,
            ChangeOperationType type,
            UUID targetItemId,
            TraceItemType itemType,
            String itemKey,
            String title,
            String description,
            String priority,
            String acceptanceCriteria,
            String verificationMethod,
            UUID sourceItemId,
            UUID linkTargetItemId,
            String relationshipType,
            boolean removeRelationship,
            String rationale) {}

    public record ImpactedArtifact(
            UUID itemId,
            String itemKey,
            TraceItemType itemType,
            String title,
            int hopCount,
            List<UUID> path,
            Severity severity,
            boolean evidenceBecomesStale,
            String reason) {}

    public record ImpactPreview(
            UUID changeRequestId,
            UUID basedOnBaselineId,
            boolean baselineCurrent,
            ScopeRisk scopeRisk,
            List<ImpactedArtifact> impactedArtifacts,
            List<String> documentsToRevise,
            Instant calculatedAt) {}

    public record LineageNode(UUID id, String kind, String title, String status, String year, boolean current) {}
    public record LineageEdge(UUID id, UUID sourceId, UUID targetId, LineageType type, boolean primary, String rationale) {}
    public record Lineage(UUID projectId, List<LineageNode> nodes, List<LineageEdge> edges) {}

    public record ContinuityCriterion(String key, String label, int weight, double completion, String explanation) {}
    public record CompletionPackage(
            UUID id,
            UUID projectId,
            String status,
            double readinessScore,
            boolean codeDataRightsConfirmed,
            List<ContinuityCriterion> criteria,
            List<String> blockers,
            String repositoryUrl,
            String commitHash,
            String setupInstructions,
            List<String> limitations,
            List<String> recommendations,
            List<String> unfinishedWork) {}

    public record ReviewQueueItem(
            UUID id,
            String type,
            String title,
            String projectCode,
            Severity severity,
            String requiredRole,
            String reason,
            Instant dueAt) {}

    public record Dashboard(
            Map<String, Integer> counts,
            List<ProjectHealth> projectHealth,
            List<ReviewQueueItem> reviewQueue,
            List<Study> recentStudies,
            List<Project> activeProjects) {}

    public record Workspace(
            String product,
            String apiVersion,
            UserSummary demoUser,
            List<Study> studies,
            List<ProblemCase> problems,
            List<Proposal> proposals,
            List<DiscoveryRun> discoveryRuns,
            List<ProposalDecision> decisions,
            List<Project> projects,
            List<Traceability> traceability,
            List<ProjectHealth> health,
            List<ChangeRequest> changeRequests,
            List<ImpactPreview> impactPreviews,
            List<Lineage> lineage,
            List<CompletionPackage> completionPackages,
            List<ReviewQueueItem> reviewQueue,
            Map<String, Object> algorithmDisclosure) {}
}
