package com.ugnay.platform.warehouse;

import com.ugnay.platform.shared.JdbcAuditService;
import com.ugnay.platform.warehouse.WarehouseContracts.AnalyticsFilters;
import com.ugnay.platform.warehouse.WarehouseContracts.AnalyticsView;
import com.ugnay.platform.warehouse.WarehouseContracts.ContinuationHistoryView;
import com.ugnay.platform.warehouse.WarehouseContracts.LoadView;
import com.ugnay.platform.warehouse.WarehouseContracts.QualityIssueView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class WarehouseService {
    private static final Logger LOGGER = LoggerFactory.getLogger(WarehouseService.class);
    private final WarehouseRepository repository;
    private final JdbcAuditService audit;
    private final int maxStudies;
    private final int maxSourceRows;

    public WarehouseService(WarehouseRepository repository, JdbcAuditService audit,
            @Value("${ugnay.warehouse.max-source-studies:10000}") int maxStudies,
            @Value("${ugnay.warehouse.max-source-rows:250000}") int maxSourceRows) {
        this.repository = repository;
        this.audit = audit;
        this.maxStudies = Math.max(1, Math.min(maxStudies, 100_000));
        this.maxSourceRows = Math.max(this.maxStudies, Math.min(maxSourceRows, 2_000_000));
    }

    /**
     * A single bounded local refresh is intentional for the Lite deployment.
     * Each stage commits its own ledger evidence; published snapshots appear only
     * after the final analysis check succeeds.
     */
    public synchronized LoadView refresh(Authentication authentication) {
        requireCurator(authentication);
        return runRefresh(authentication.getName(), "CURATOR_REQUEST");
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    synchronized LoadView refreshQueued(String actor, WarehouseRefreshRequested.Trigger trigger) {
        return runRefresh(actor, trigger.name());
    }

    private LoadView runRefresh(String actor, String trigger) {
        UUID loadId = repository.createLoad(actor);
        try {
            repository.beginStage(loadId, "COLLECT");
            WarehouseRepository.Collected collected = repository.collect(loadId, maxStudies, maxSourceRows);
            repository.completeStage(loadId, "COLLECT", collected.sourceRows(), collected.studyCount(),
                    "{\"sourceStudies\":" + collected.studyCount() + ",\"sourceRows\":" + collected.sourceRows() + "}");

            var existing = repository.publishedSnapshotByHash(collected.sourceHash());
            if (existing.isPresent()) {
                repository.coalesce(loadId, existing.get(), collected.studyCount());
                audit.append(actor, "WAREHOUSE_REFRESH_UNCHANGED", "WAREHOUSE_LOAD", loadId,
                        "Warehouse refresh reused the published snapshot because the authoritative source hash was unchanged.",
                        Map.of("snapshotId", existing.get().toString(), "sourceSha256", collected.sourceHash(), "trigger", trigger));
                return repository.load(loadId);
            }

            repository.beginStage(loadId, "VALIDATE");
            WarehouseRepository.ValidationResult validated = repository.validate(loadId);
            repository.completeStage(loadId, "VALIDATE", validated.sourceCount(), validated.acceptedCount(),
                    "{\"accepted\":" + validated.acceptedCount() + ",\"rejected\":" + validated.rejectedCount()
                            + ",\"qualityIssues\":" + validated.issueCount() + "}");

            repository.beginStage(loadId, "CLEAN");
            int cleaned = repository.clean(loadId);
            repository.completeStage(loadId, "CLEAN", validated.acceptedCount(), cleaned,
                    "{\"normalization\":\"UNICODE_NFKC_WHITESPACE\"}");

            repository.beginStage(loadId, "TRANSFORM");
            int transformed = repository.transform(loadId);
            repository.completeStage(loadId, "TRANSFORM", cleaned, transformed,
                    "{\"result\":\"DIMENSIONS_AND_FACTS_PREPARED\"}");

            repository.beginStage(loadId, "STORE");
            UUID snapshotId = repository.store(loadId);
            repository.completeStage(loadId, "STORE", transformed, validated.acceptedCount(),
                    "{\"snapshotId\":\"" + snapshotId + "\",\"status\":\"BUILDING\"}");

            repository.beginStage(loadId, "ANALYZE");
            WarehouseRepository.SnapshotResult result = repository.analyzeAndPublish(loadId, snapshotId);
            audit.append(actor, "WAREHOUSE_SNAPSHOT_PUBLISHED", "WAREHOUSE_SNAPSHOT", snapshotId,
                    "Published an immutable historical-research warehouse snapshot after all six pipeline stages completed.",
                    Map.of("loadId", loadId.toString(), "sourceSha256", collected.sourceHash(),
                            "trigger", trigger,
                            "studyCount", result.studyCount(), "yearCount", result.yearCount(),
                            "departmentCount", result.departmentCount(), "topicCount", result.topicCount(),
                            "continuationCount", result.continuationCount()));
            return repository.load(loadId);
        } catch (RuntimeException exception) {
            String stage = repository.load(loadId).currentStage();
            LOGGER.warn("Warehouse refresh {} failed during {}.", loadId, stage, exception);
            repository.failLoad(loadId, "Warehouse refresh could not complete during " + stage + ".");
            audit.append(actor, "WAREHOUSE_REFRESH_FAILED", "WAREHOUSE_LOAD", loadId,
                    "Warehouse refresh failed without replacing the latest published snapshot.",
                    Map.of("stage", stage, "trigger", trigger));
            return repository.load(loadId);
        }
    }

    public LoadView latestLoad(Authentication authentication) {
        requireCurator(authentication);
        return repository.latestLoad();
    }

    public LoadView load(Authentication authentication, UUID loadId) {
        requireCurator(authentication);
        return repository.load(loadId);
    }

    public List<QualityIssueView> qualityIssues(Authentication authentication, UUID loadId) {
        requireCurator(authentication);
        return repository.qualityIssues(loadId);
    }

    public AnalyticsView analytics(Authentication authentication, String department, Integer fromYear, Integer toYear) {
        WarehouseRepository.ActorScope scope = actorScope(authentication);
        AnalyticsFilters filters = filters(department, fromYear, toYear);
        return repository.analytics(scope, filters);
    }

    public ContinuationHistoryView continuationHistory(Authentication authentication, int limit) {
        return repository.continuationHistory(actorScope(authentication), Math.max(1, Math.min(limit, 500)));
    }

    public String analyticsCsv(Authentication authentication, String department, Integer fromYear, Integer toYear) {
        AnalyticsView analytics = analytics(authentication, department, fromYear, toYear);
        StringBuilder csv = new StringBuilder("metric,dimension,secondary_dimension,value,assessment_status,snapshot_id,as_of\r\n");
        analytics.studiesPerYear().forEach(row -> csvRow(csv, "STUDIES_PER_YEAR", String.valueOf(row.year()), "", row.studyCount(), analytics));
        analytics.studiesPerDepartment().forEach(row -> csvRow(csv, "STUDIES_PER_DEPARTMENT", row.departmentCode(), row.departmentName(), row.studyCount(), analytics));
        analytics.repeatedTopics().forEach(row -> csvRow(csv, "REPEATED_TOPIC", row.label(), row.termType(), row.studyCount(), analytics));
        analytics.commonResearchAreas().forEach(row -> csvRow(csv, "COMMON_RESEARCH_AREA", row.label(), row.termType(), row.studyCount(), analytics));
        analytics.topicTrends().forEach(row -> csvRow(csv, "TOPIC_TREND", row.label(), row.termType() + ":" + row.year(), row.studyCount(), analytics));
        if (analytics.snapshotId() == null) csvRow(csv, "WAREHOUSE", "", "", "", analytics);
        return csv.toString();
    }

    public String continuationCsv(Authentication authentication, int limit) {
        ContinuationHistoryView history = continuationHistory(authentication, limit);
        StringBuilder csv = new StringBuilder("fact_key,source_kind,source_study_id,source_study_title,target_study_id,target_study_title,successor_project_id,continuation_item_id,relationship_type,evidence_status,rationale,evidence_at,assessment_status,snapshot_id,as_of\r\n");
        if (history.items().isEmpty() && history.snapshotId() == null) {
            appendCsv(csv, "", "", "", "", "", "", "", "", "", "", "", "",
                    history.assessmentStatus(), "", "");
        } else {
            history.items().forEach(item -> appendCsv(csv, item.factKey(), item.sourceKind(), value(item.sourceStudyId()),
                    item.sourceStudyTitle(), value(item.targetStudyId()), item.targetStudyTitle(), value(item.successorProjectId()),
                    value(item.continuationItemId()), item.relationshipType(), item.evidenceStatus(), item.rationale(), value(item.evidenceAt()),
                    history.assessmentStatus(), value(history.snapshotId()), value(history.asOf())));
        }
        return csv.toString();
    }

    private WarehouseRepository.ActorScope actorScope(Authentication authentication) {
        requireAuthenticated(authentication);
        return repository.requireActor(authentication.getName(), hasRole(authentication, "CURATOR"));
    }

    private static AnalyticsFilters filters(String department, Integer fromYear, Integer toYear) {
        String departmentValue = department == null || department.isBlank() ? null : department.strip();
        if (departmentValue != null && departmentValue.length() > 160) throw new IllegalArgumentException("Department filter cannot exceed 160 characters.");
        if (fromYear != null && (fromYear < 1900 || fromYear > 2200)) throw new IllegalArgumentException("fromYear must be between 1900 and 2200.");
        if (toYear != null && (toYear < 1900 || toYear > 2200)) throw new IllegalArgumentException("toYear must be between 1900 and 2200.");
        if (fromYear != null && toYear != null && fromYear > toYear) throw new IllegalArgumentException("fromYear cannot be later than toYear.");
        return new AnalyticsFilters(departmentValue, fromYear, toYear);
    }

    private static void requireCurator(Authentication authentication) {
        requireAuthenticated(authentication);
        if (!hasRole(authentication, "CURATOR")) throw new org.springframework.security.access.AccessDeniedException("Curator authority is required.");
    }

    private static void requireAuthenticated(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || "anonymousUser".equals(authentication.getName())) {
            throw new org.springframework.security.access.AccessDeniedException("Authentication is required.");
        }
    }

    private static boolean hasRole(Authentication authentication, String role) {
        return authentication.getAuthorities().stream().anyMatch(value -> value.getAuthority().equals("ROLE_" + role));
    }

    private static void csvRow(StringBuilder csv, String metric, String dimension, String secondary, Object value, AnalyticsView analytics) {
        appendCsv(csv, metric, dimension, secondary, value, analytics.assessmentStatus(), value(analytics.snapshotId()), value(analytics.asOf()));
    }

    private static void appendCsv(StringBuilder csv, Object... values) {
        for (int index = 0; index < values.length; index++) {
            if (index > 0) csv.append(',');
            csv.append(csvCell(value(values[index])));
        }
        csv.append("\r\n");
    }

    private static String csvCell(String raw) {
        String value = raw == null ? "" : raw;
        if (!value.isEmpty() && "=+-@".indexOf(value.charAt(0)) >= 0) value = "'" + value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static String value(Object value) { return value == null ? "" : String.valueOf(value); }
}
