package com.ugnay.platform.warehouse;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class WarehouseContracts {
    private WarehouseContracts() {}

    public record LoadView(
            UUID id,
            String status,
            String currentStage,
            String assessmentStatus,
            String sourceSha256,
            int sourceCount,
            int acceptedCount,
            int rejectedCount,
            UUID snapshotId,
            Instant sourceCutoffAt,
            Instant startedAt,
            Instant completedAt,
            String failureReason,
            List<StageView> stages,
            QualitySummary quality) {
        public static LoadView unassessed() {
            return new LoadView(null, "UNASSESSED", null, "UNASSESSED", null, 0, 0, 0,
                    null, null, null, null, null, List.of(), QualitySummary.unassessed());
        }
    }

    public record StageView(
            String stage,
            int order,
            String status,
            int inputCount,
            int outputCount,
            String detailsJson,
            Instant startedAt,
            Instant completedAt) {}

    public record QualitySummary(
            String assessmentStatus,
            int issueCount,
            Map<String, Integer> bySeverity,
            Map<String, Integer> byCode) {
        public static QualitySummary unassessed() {
            return new QualitySummary("UNASSESSED", 0, Map.of(), Map.of());
        }
    }

    public record QualityIssueView(
            UUID id,
            UUID studyId,
            String code,
            String severity,
            String field,
            String message,
            Instant recordedAt) {}

    public record AnalyticsFilters(String department, Integer fromYear, Integer toYear) {}

    public record AnalyticsView(
            UUID snapshotId,
            Instant asOf,
            String assessmentStatus,
            AnalyticsFilters filters,
            int sourceStudyCount,
            int visibleStudyCount,
            int unavailableYearCount,
            List<YearCount> studiesPerYear,
            List<DepartmentCount> studiesPerDepartment,
            List<TopicCount> repeatedTopics,
            List<TopicCount> commonResearchAreas,
            List<TopicTrend> topicTrends,
            QualitySummary quality) {
        public static AnalyticsView unassessed(AnalyticsFilters filters) {
            return new AnalyticsView(null, null, "UNASSESSED", filters, 0, 0, 0,
                    List.of(), List.of(), List.of(), List.of(), List.of(), QualitySummary.unassessed());
        }
    }

    public record YearCount(int year, int studyCount) {}
    public record DepartmentCount(String departmentCode, String departmentName, int studyCount) {}
    public record TopicCount(UUID termId, String label, String termType, int studyCount) {}
    public record TopicTrend(UUID termId, String label, String termType, int year, int studyCount) {}

    public record ContinuationHistoryView(
            UUID snapshotId,
            Instant asOf,
            String assessmentStatus,
            int total,
            List<ContinuationHistoryItem> items) {
        public static ContinuationHistoryView unassessed() {
            return new ContinuationHistoryView(null, null, "UNASSESSED", 0, List.of());
        }
    }

    public record ContinuationHistoryItem(
            String factKey,
            String sourceKind,
            UUID sourceStudyId,
            String sourceStudyTitle,
            UUID targetStudyId,
            String targetStudyTitle,
            UUID successorProjectId,
            UUID continuationItemId,
            String relationshipType,
            String evidenceStatus,
            String rationale,
            Instant evidenceAt) {}
}
