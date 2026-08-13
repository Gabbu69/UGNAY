package com.ugnay.platform.analytics;

import com.ugnay.platform.shared.PlatformModels.AssessmentStatus;
import com.ugnay.platform.shared.PlatformModels.CompletionPackage;
import com.ugnay.platform.shared.PlatformModels.Coverage;
import com.ugnay.platform.shared.PlatformModels.Finding;
import com.ugnay.platform.shared.PlatformModels.FindingState;
import com.ugnay.platform.shared.PlatformModels.HealthDimension;
import com.ugnay.platform.shared.PlatformModels.Project;
import com.ugnay.platform.shared.PlatformModels.ProjectHealth;
import com.ugnay.platform.shared.PlatformModels.ScopeRisk;
import com.ugnay.platform.shared.PlatformModels.Severity;
import com.ugnay.platform.shared.PlatformModels.TestExecution;
import com.ugnay.platform.shared.PlatformModels.TraceItem;
import com.ugnay.platform.shared.PlatformModels.TraceItemType;
import com.ugnay.platform.shared.PlatformModels.TraceLink;
import com.ugnay.platform.shared.PlatformModels.Traceability;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Service
public final class AlignmentAnalyzer {
    public static final String RULE_VERSION = "alignment-v1.0.0";

    public Traceability analyze(Project project, List<TraceItem> sourceItems, List<TraceLink> links, List<TestExecution> executions) {
        Map<UUID, TraceItem> sourceById = sourceItems.stream().collect(Collectors.toMap(TraceItem::id, item -> item));
        List<TraceItem> items = sourceItems.stream().map(item -> withReadiness(item, links, sourceById)).toList();
        Map<UUID, TraceItem> byId = items.stream().collect(Collectors.toMap(TraceItem::id, item -> item));
        List<Finding> findings = new ArrayList<>();

        for (TraceItem item : items) {
            switch (item.type()) {
                case PROBLEM -> requireOutgoing(item, links, byId, type(TraceItemType.OBJECTIVE), findings,
                        "PROBLEM_WITHOUT_OBJECTIVE", Severity.HIGH, "Problem has no objective",
                        "Connect the approved problem to at least one objective.");
                case OBJECTIVE -> requireOutgoing(item, links, byId, type(TraceItemType.REQUIREMENT), findings,
                        "OBJECTIVE_WITHOUT_REQUIREMENT", Severity.HIGH, "Objective has no requirement",
                        "Define one or more verifiable requirements for this objective.");
                case REQUIREMENT -> analyzeRequirement(item, links, byId, findings);
                case FEATURE -> requireIncoming(item, links, byId, type(TraceItemType.REQUIREMENT), findings,
                        "UNJUSTIFIED_FEATURE", Severity.HIGH, "Feature is not justified by an approved requirement",
                        "Link the feature to a requirement or remove it from the approved scope.");
                case TEST_CASE -> requireIncoming(item, links, byId,
                        candidate -> candidate.type() == TraceItemType.REQUIREMENT || candidate.type() == TraceItemType.FEATURE,
                        findings, "TEST_WITHOUT_TARGET", Severity.MODERATE, "Test has no requirement or feature target",
                        "Connect the test to the behavior that it verifies.");
                case OUTPUT -> {
                    if (!reachableFromAny(items, links, item.id(), TraceItemType.OBJECTIVE)) {
                        findings.add(finding("OUTPUT_WITHOUT_OBJECTIVE_PATH", Severity.HIGH,
                                "Output is disconnected from project objectives",
                                "No trace path shows how this output satisfies an objective.",
                                "Connect the output through a requirement or feature to an objective.", item.id()));
                    }
                }
            }
        }

        links.stream().filter(link -> !"ACTIVE".equals(link.status())).forEach(link -> findings.add(finding(
                "STALE_TRACE_LINK", Severity.MODERATE, "Trace link targets obsolete evidence",
                "The link is " + link.status().toLowerCase() + " and cannot support the current baseline.",
                "Replace or reactivate the link using current item revisions.", link.sourceId(), link.targetId())));

        links.stream().filter(link -> "ACTIVE".equals(link.status()) && !supportsCurrentTypedRelationship(link, byId))
                .forEach(link -> findings.add(finding(
                        "INVALID_TRACE_RELATIONSHIP", Severity.HIGH, "Trace link cannot support the approved baseline",
                        "The link has an invalid type direction or connects a draft, obsolete, rejected, superseded, or missing revision.",
                        "Replace it with a valid typed relationship between current approved artifacts.", link.sourceId(), link.targetId())));

        executions.stream().filter(execution -> !execution.current()).forEach(execution -> findings.add(finding(
                "STALE_TEST_EVIDENCE", Severity.HIGH, "Test evidence is stale",
                "Execution " + execution.buildIdentifier() + " no longer matches the current baseline or build.",
                "Execute the test against the current baseline and attach evidence.", execution.testItemId())));

        Coverage coverage = coverage(items, links, executions, byId);
        return new Traceability(project.id(), project.currentBaselineId(), project.baselineNumber(),
                project.currentBaselineId() == null ? AssessmentStatus.UNASSESSED : AssessmentStatus.ASSESSED,
                items, links, executions, List.copyOf(findings), coverage);
    }

    public ProjectHealth health(Project project, Traceability traceability, ScopeRisk scopeRisk, CompletionPackage completionPackage) {
        if (traceability.assessmentStatus() == AssessmentStatus.UNASSESSED) {
            List<HealthDimension> dimensions = List.of(
                    dimension("alignment", "Alignment", AssessmentStatus.UNASSESSED, null, "No approved baseline."),
                    dimension("requirements", "Requirement readiness", AssessmentStatus.UNASSESSED, null, "No approved baseline."),
                    dimension("verification", "Verification", AssessmentStatus.UNASSESSED, null, "No approved baseline."),
                    dimension("scope", "Scope stability", AssessmentStatus.UNASSESSED, null, "No approved baseline."),
                    dimension("continuity", "Continuity readiness", AssessmentStatus.UNASSESSED, null, "Completion evidence has not been assessed."));
            return new ProjectHealth(project.id(), "UNASSESSED", dimensions, 0, 0, Instant.now(), RULE_VERSION);
        }

        long high = traceability.findings().stream().filter(f -> f.state() == FindingState.OPEN && f.severity().ordinal() >= Severity.HIGH.ordinal()).count();
        long moderate = traceability.findings().stream().filter(f -> f.state() == FindingState.OPEN && f.severity() == Severity.MODERATE).count();
        double alignment = clamp(100 - high * 18 - moderate * 7);
        List<TraceItem> requirements = traceability.items().stream().filter(type(TraceItemType.REQUIREMENT)).toList();
        Double readiness = requirements.isEmpty() ? null
                : requirements.stream().mapToDouble(TraceItem::readinessScore).average().orElseThrow();
        Double verification = traceability.coverage().status() == AssessmentStatus.UNASSESSED ? null
                : traceability.coverage().priorityWeightedPassingCoverage();
        Double scope = scopeRisk.status() == AssessmentStatus.UNASSESSED || scopeRisk.score() == null
                ? null : 100.0 - scopeRisk.score();
        Double continuity = completionPackage == null ? null : completionPackage.readinessScore();

        List<HealthDimension> dimensions = List.of(
                dimension("alignment", "Alignment", AssessmentStatus.ASSESSED, alignment, "Missing and invalid trace relationships reduce this dimension."),
                dimension("requirements", "Requirement readiness", requirements.isEmpty() ? AssessmentStatus.UNASSESSED : AssessmentStatus.ASSESSED,
                        readiness, requirements.isEmpty() ? "No current requirements have been assessed." : "Average deterministic readiness of active requirements."),
                dimension("verification", "Verification", traceability.coverage().status(), verification, "Priority-weighted current passing coverage."),
                dimension("scope", "Scope stability", scopeRisk.status(), scope, "Inverse of explained scope risk."),
                dimension("continuity", "Continuity readiness", continuity == null ? AssessmentStatus.UNASSESSED : AssessmentStatus.ASSESSED,
                        continuity, continuity == null ? "No completion package has been assessed." : "Evidence-weighted handoff readiness."));

        int critical = (int) traceability.findings().stream().filter(f -> f.state() == FindingState.OPEN && f.severity() == Severity.CRITICAL).count();
        boolean failedMust = failedOrUnverifiedMust(traceability);
        String overall = dimensions.stream().filter(d -> d.score() != null).map(HealthDimension::band)
                .min((left, right) -> Integer.compare(bandRank(left), bandRank(right))).orElse("UNASSESSED");
        if (critical > 0 || failedMust && project.status().name().equals("VALIDATING")) overall = "CRITICAL";
        int open = (int) traceability.findings().stream().filter(f -> f.state() == FindingState.OPEN).count();
        return new ProjectHealth(project.id(), overall, dimensions, open, critical, Instant.now(), RULE_VERSION);
    }

    public ScopeRisk scopeRisk(Project project, Traceability traceability, int approvedGrowth, int unapprovedGrowth, List<String> boundaryFlags) {
        if (project.currentBaselineId() == null) {
            return new ScopeRisk(AssessmentStatus.UNASSESSED, null, "UNASSESSED", null, null, null, null,
                    List.of("Scope risk requires an approved baseline."));
        }
        int governance = Math.min(35, Math.max(0, unapprovedGrowth));
        long activeRequirementsAndFeatures = traceability.items().stream()
                .filter(item -> item.type() == TraceItemType.REQUIREMENT || item.type() == TraceItemType.FEATURE).count();
        long untraced = traceability.findings().stream().filter(f -> f.code().equals("UNJUSTIFIED_FEATURE") || f.code().equals("REQUIREMENT_WITHOUT_OBJECTIVE")).count();
        int alignment = activeRequirementsAndFeatures == 0 ? 25 : (int) Math.round(Math.min(25, untraced * 25.0 / activeRequirementsAndFeatures));
        int growth = Math.min(20, Math.max(0, approvedGrowth));
        long distinctBoundaries = boundaryFlags == null ? 0 : boundaryFlags.stream()
                .filter(flag -> flag != null && !flag.isBlank()).map(String::toUpperCase).distinct().count();
        int boundary = Math.min(20, (int) distinctBoundaries * 5);
        int score = Math.min(100, governance + alignment + growth + boundary);
        boolean unapprovedObjectiveOrMust = traceability.items().stream().anyMatch(item ->
                "DRAFT".equals(item.lifecycleStatus()) && (item.type() == TraceItemType.OBJECTIVE
                        || item.type() == TraceItemType.REQUIREMENT && "MUST".equals(item.priority())));
        boolean sensitiveBoundary = boundaryFlags != null && boundaryFlags.stream().anyMatch(flag ->
                flag.equalsIgnoreCase("SENSITIVE_DATA") || flag.equalsIgnoreCase("SENSITIVE_DATA_CLASS")
                        || flag.equalsIgnoreCase("PERSONAL_CONTACT_DATA") || flag.equalsIgnoreCase("SECURITY"));
        if (unapprovedObjectiveOrMust) score = Math.max(score, 50);
        if (sensitiveBoundary) score = Math.max(score, 75);
        String band = score < 25 ? "LOW" : score < 50 ? "MODERATE" : score < 75 ? "HIGH" : "CRITICAL";
        List<String> explanations = new ArrayList<>();
        explanations.add("Governance contributes " + governance + "/35 from unapproved priority-weighted growth.");
        explanations.add("Alignment contributes " + alignment + "/25 from untraced requirements and features.");
        explanations.add("Controlled growth pressure contributes " + growth + "/20; approved growth is not labelled scope creep.");
        explanations.add("Boundary change contributes " + boundary + "/20 from unresolved new boundaries.");
        return new ScopeRisk(AssessmentStatus.ASSESSED, score, band, governance, alignment, growth, boundary, explanations);
    }

    private void analyzeRequirement(TraceItem item, List<TraceLink> links, Map<UUID, TraceItem> byId, List<Finding> findings) {
        requireIncoming(item, links, byId, type(TraceItemType.OBJECTIVE), findings,
                "REQUIREMENT_WITHOUT_OBJECTIVE", Severity.HIGH, "Requirement has no source objective",
                "Link the requirement to the objective and record its rationale.");
        if (isBlank(item.acceptanceCriteria())) findings.add(finding("MISSING_ACCEPTANCE_CRITERIA", Severity.HIGH,
                "Requirement lacks acceptance criteria", "The requirement cannot be verified objectively.",
                "Add measurable acceptance criteria.", item.id()));
        if (isBlank(item.verificationMethod())) findings.add(finding("MISSING_VERIFICATION_METHOD", Severity.HIGH,
                "Requirement lacks a verification method", "No method states how acceptance will be demonstrated.",
                "Choose inspection, analysis, demonstration, or test and describe the evidence.", item.id()));
        requireOutgoing(item, links, byId, type(TraceItemType.FEATURE), findings,
                "REQUIREMENT_WITHOUT_FEATURE", Severity.HIGH, "Functional requirement has no realizing feature",
                "Link the requirement to an implemented feature.");
        String text = (item.title() + " " + item.description() + " " + nullToEmpty(item.acceptanceCriteria())).toLowerCase();
        if (text.matches(".*\\b(fast|user-friendly|etc\\.|and/or)\\b.*")) findings.add(finding(
                "AMBIGUOUS_REQUIREMENT_LANGUAGE", Severity.MODERATE, "Requirement contains ambiguous language",
                "The wording includes a subjective or compound phrase that may be interpreted differently.",
                "Replace ambiguous language with one measurable behavior per requirement.", item.id()));
    }

    private Coverage coverage(List<TraceItem> items, List<TraceLink> links, List<TestExecution> executions, Map<UUID, TraceItem> byId) {
        List<TraceItem> requirements = items.stream().filter(type(TraceItemType.REQUIREMENT))
                .filter(AlignmentAnalyzer::approvedCurrent).toList();
        if (requirements.isEmpty()) return new Coverage(AssessmentStatus.ASSESSED, 0, 0, 0, 0, 0, 0);
        Map<UUID, TestExecution> latest = executions.stream().collect(Collectors.toMap(TestExecution::testItemId, e -> e, (a, b) -> a.executedAt().isAfter(b.executedAt()) ? a : b));
        int mapped = 0, executed = 0, passed = 0, verified = 0;
        double totalWeight = 0, passedWeight = 0;
        for (TraceItem requirement : requirements) {
            Set<UUID> tests = reachableTargets(requirement.id(), links, byId, TraceItemType.TEST_CASE);
            int weight = priorityWeight(requirement.priority());
            totalWeight += weight;
            if (!tests.isEmpty()) mapped++;
            boolean allExecuted = !tests.isEmpty() && tests.stream().allMatch(id -> latest.containsKey(id) && latest.get(id).current());
            boolean allPassed = allExecuted && tests.stream().allMatch(id -> "PASSED".equals(latest.get(id).status()));
            boolean allEvidence = allPassed && tests.stream().allMatch(id -> latest.get(id).hasEvidence());
            if (allExecuted) executed++;
            if (allPassed) passed++;
            if (allEvidence) {
                verified++;
                passedWeight += weight;
            }
        }
        int total = requirements.size();
        return new Coverage(AssessmentStatus.ASSESSED, percent(mapped, total), percent(executed, total),
                percent(passed, total), totalWeight == 0 ? 0 : round(passedWeight * 100 / totalWeight), total, verified);
    }

    private TraceItem withReadiness(TraceItem item, List<TraceLink> links, Map<UUID, TraceItem> byId) {
        if (item.type() != TraceItemType.REQUIREMENT) return item;
        double score = 0;
        boolean approvedSourceWithRationale = links.stream()
                .filter(link -> link.targetId().equals(item.id()))
                .filter(link -> supportsCurrentTypedRelationship(link, byId))
                .anyMatch(link -> byId.get(link.sourceId()).type() == TraceItemType.OBJECTIVE && !isBlank(link.rationale()));
        if (approvedSourceWithRationale) score += 20;
        String description = nullToEmpty(item.description()).toLowerCase();
        if ((description.contains("user") || description.contains("system")) && description.length() >= 40) score += 20;
        if (!isBlank(item.acceptanceCriteria())) score += 25;
        if (!isBlank(item.verificationMethod())) score += 10;
        if (!isBlank(item.priority())) score += 10;
        if (description.matches(".*\\b(error|data|security|performance|interface|privacy)\\b.*")) score += 15;
        return new TraceItem(item.id(), item.key(), item.type(), item.title(), item.description(), item.lifecycleStatus(),
                item.priority(), item.acceptanceCriteria(), item.verificationMethod(), item.currentRevision(), score);
    }

    private static boolean failedOrUnverifiedMust(Traceability traceability) {
        Set<UUID> mustIds = traceability.items().stream().filter(item -> item.type() == TraceItemType.REQUIREMENT
                        && "MUST".equals(item.priority()) && approvedCurrent(item))
                .map(TraceItem::id).collect(Collectors.toSet());
        if (mustIds.isEmpty()) return false;
        Map<UUID, TraceItem> byId = traceability.items().stream().collect(Collectors.toMap(TraceItem::id, item -> item));
        Map<UUID, TestExecution> executions = traceability.executions().stream().collect(Collectors.toMap(
                TestExecution::testItemId, e -> e, (a, b) -> a.executedAt().isAfter(b.executedAt()) ? a : b));
        for (UUID requirement : mustIds) {
            Set<UUID> tests = reachableTargets(requirement, traceability.links(), byId, TraceItemType.TEST_CASE);
            if (tests.isEmpty() || tests.stream().anyMatch(test -> !executions.containsKey(test) || !executions.get(test).current()
                    || !"PASSED".equals(executions.get(test).status()) || !executions.get(test).hasEvidence())) return true;
        }
        return false;
    }

    public static Set<UUID> approvedReachableTargets(UUID start, List<TraceLink> links, Map<UUID, TraceItem> byId, TraceItemType wanted) {
        return reachableTargets(start, links, byId, wanted);
    }

    private static Set<UUID> reachableTargets(UUID start, List<TraceLink> links, Map<UUID, TraceItem> byId, TraceItemType wanted) {
        Map<UUID, List<UUID>> outgoing = links.stream().filter(link -> supportsCurrentTypedRelationship(link, byId))
                .collect(Collectors.groupingBy(TraceLink::sourceId, Collectors.mapping(TraceLink::targetId, Collectors.toList())));
        Set<UUID> visited = new HashSet<>();
        Set<UUID> result = new HashSet<>();
        ArrayDeque<UUID> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            UUID current = queue.remove();
            if (!visited.add(current)) continue;
            for (UUID next : outgoing.getOrDefault(current, List.of())) {
                TraceItem item = byId.get(next);
                if (item != null && item.type() == wanted) result.add(next);
                queue.add(next);
            }
        }
        return result;
    }

    private static boolean reachableFromAny(List<TraceItem> items, List<TraceLink> links, UUID target, TraceItemType sourceType) {
        Map<UUID, TraceItem> byId = items.stream().collect(Collectors.toMap(TraceItem::id, item -> item));
        return items.stream().filter(type(sourceType)).anyMatch(source -> reachableTargets(source.id(), links, byId, TraceItemType.OUTPUT).contains(target));
    }

    private static void requireOutgoing(TraceItem item, List<TraceLink> links, Map<UUID, TraceItem> byId,
                                        Predicate<TraceItem> target, List<Finding> findings, String code, Severity severity,
                                        String title, String action) {
        boolean present = links.stream().filter(link -> link.sourceId().equals(item.id()) && supportsCurrentTypedRelationship(link, byId))
                .map(link -> byId.get(link.targetId())).anyMatch(candidate -> candidate != null && target.test(candidate));
        if (!present) findings.add(finding(code, severity, title, "No active outgoing trace link satisfies the required relationship.", action, item.id()));
    }

    private static void requireIncoming(TraceItem item, List<TraceLink> links, Map<UUID, TraceItem> byId,
                                        Predicate<TraceItem> source, List<Finding> findings, String code, Severity severity,
                                        String title, String action) {
        boolean present = links.stream().filter(link -> link.targetId().equals(item.id()) && supportsCurrentTypedRelationship(link, byId))
                .map(link -> byId.get(link.sourceId())).anyMatch(candidate -> candidate != null && source.test(candidate));
        if (!present) findings.add(finding(code, severity, title, "No active incoming trace link satisfies the required relationship.", action, item.id()));
    }

    private static Finding finding(String code, Severity severity, String title, String explanation, String nextAction, UUID... ids) {
        return new Finding(UUID.nameUUIDFromBytes((code + List.of(ids)).getBytes()), code, severity, FindingState.OPEN,
                title, explanation, nextAction, List.of(ids), RULE_VERSION);
    }

    private static Predicate<TraceItem> type(TraceItemType type) { return item -> item.type() == type; }
    private static boolean approvedCurrent(TraceItem item) {
        return item != null && item.currentRevision() > 0 && "APPROVED".equals(item.lifecycleStatus());
    }

    private static boolean supportsCurrentTypedRelationship(TraceLink link, Map<UUID, TraceItem> byId) {
        if (!"ACTIVE".equals(link.status())) return false;
        TraceItem source = byId.get(link.sourceId());
        TraceItem target = byId.get(link.targetId());
        if (!approvedCurrent(source) || !approvedCurrent(target)) return false;
        String type = nullToEmpty(link.type()).toUpperCase();
        return switch (source.type()) {
            case PROBLEM -> target.type() == TraceItemType.OBJECTIVE && type.equals("MOTIVATES");
            case OBJECTIVE -> target.type() == TraceItemType.REQUIREMENT && type.equals("DECOMPOSES_TO");
            case REQUIREMENT -> (target.type() == TraceItemType.FEATURE && type.equals("REALIZED_BY"))
                    || (target.type() == TraceItemType.TEST_CASE && type.equals("VERIFIED_BY"));
            case FEATURE -> (target.type() == TraceItemType.TEST_CASE && type.equals("VERIFIED_BY"))
                    || (target.type() == TraceItemType.OUTPUT && type.equals("CONTRIBUTES_TO"));
            case TEST_CASE, OUTPUT -> false;
        };
    }
    private static int priorityWeight(String priority) { return "MUST".equals(priority) ? 5 : "SHOULD".equals(priority) ? 3 : 1; }
    private static double percent(double numerator, double denominator) { return denominator == 0 ? 0 : round(numerator * 100 / denominator); }
    private static double round(double value) { return Math.round(value * 100.0) / 100.0; }
    private static double clamp(double value) { return Math.max(0, Math.min(100, value)); }
    private static boolean isBlank(String value) { return value == null || value.isBlank(); }
    private static String nullToEmpty(String value) { return value == null ? "" : value; }

    private static HealthDimension dimension(String key, String label, AssessmentStatus status, Double score, String explanation) {
        return new HealthDimension(key, label, status, score, score == null ? "UNASSESSED" : band(score), explanation);
    }

    private static String band(double score) { return score >= 85 ? "HEALTHY" : score >= 70 ? "WATCH" : score >= 50 ? "AT_RISK" : "CRITICAL"; }
    private static int bandRank(String band) { return switch (band) { case "CRITICAL" -> 0; case "AT_RISK" -> 1; case "WATCH" -> 2; case "HEALTHY" -> 3; default -> 4; }; }
}
