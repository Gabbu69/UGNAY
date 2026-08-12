package com.ugnay.platform.workspace;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Repository
public class CatalogueMetadataRepository {
    private static final String NORMALIZER_VERSION = "NFKC_LOWER_WHITESPACE_V1";
    private static final Pattern SINGLE_YEAR = Pattern.compile("^(\\d{4})$");
    private static final Pattern YEAR_RANGE = Pattern.compile("^(\\d{4})-(\\d{4})$");
    private static final Set<String> VISIBILITIES = Set.of("PUBLIC", "CAMPUS", "RESTRICTED", "EMBARGOED");
    private static final Set<String> LIFECYCLES = Set.of("PUBLISHED", "COMPLETED", "INCOMPLETE", "SUSPENDED", "ARCHIVED");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public CatalogueMetadataRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public Metadata metadata(UUID studyId) {
        String program = jdbc.queryForObject("SELECT program_name FROM studies WHERE id=?", String.class, bytes(studyId));
        List<String> authors = jdbc.query("SELECT a.display_name FROM authors a JOIN study_authors sa ON sa.author_id=a.id WHERE sa.study_id=? ORDER BY sa.author_order",
                (row, index) -> row.getString(1), bytes(studyId));
        List<String> relationships = jdbc.query("SELECT relationship_type FROM study_relationships WHERE source_study_id=? OR target_study_id=? ORDER BY created_at DESC",
                (row, index) -> row.getString(1), bytes(studyId), bytes(studyId));
        return new Metadata(blankToNull(program), List.copyOf(authors), relationships.isEmpty() ? null : relationships.getFirst());
    }

    @Transactional
    public void updatePublication(UUID studyId, String program, List<String> authors, String doi, String repositoryIdentifier) {
        jdbc.update("UPDATE studies SET program_name=?, doi=?, repository_identifier=? WHERE id=?",
                blankToNull(program), blankToNull(doi), blankToNull(repositoryIdentifier), bytes(studyId));
        jdbc.update("DELETE FROM study_authors WHERE study_id=?", bytes(studyId));
        int order = 0;
        for (String value : distinct(authors)) {
            String name = required(value, "Author name");
            UUID authorId = UUID.nameUUIDFromBytes(("ugnay:author:" + normalize(name)).getBytes(StandardCharsets.UTF_8));
            int count = jdbc.queryForObject("SELECT COUNT(*) FROM authors WHERE id=?", Integer.class, bytes(authorId));
            if (count == 0) jdbc.update("INSERT INTO authors(id, display_name, institutional_identifier) VALUES(?,?,?)",
                    bytes(authorId), name, null);
            jdbc.update("INSERT INTO study_authors(study_id, author_id, author_order) VALUES(?,?,?)",
                    bytes(studyId), bytes(authorId), order++);
        }
    }

    /** Records optional curator-reviewed fields without inventing missing evidence. */
    @Transactional
    public void recordReviewedEvidence(UUID studyId, ReviewedEvidence evidence, String actorEmail, String provenance) {
        ReviewedEvidence safe = evidence == null ? ReviewedEvidence.empty() : evidence;
        byte[] departmentId = departmentId(safe.department());
        Integer year = completionYear(safe.academicYear());
        String visibility = allowListed(safe.visibility(), VISIBILITIES, "visibility", "RESTRICTED");
        String lifecycle = allowListed(safe.lifecycleStatus(), LIFECYCLES, "lifecycle status", "INCOMPLETE");
        jdbc.update("UPDATE studies SET department_id=COALESCE(?,department_id), academic_year=?, completion_year=?, " +
                        "results_text=?, data_sources_text=?, technology_text=?, intended_users_text=?, visibility=?, lifecycle_status=? WHERE id=?",
                departmentId, blankToNull(safe.academicYear()), year, blankToNull(safe.resultsText()),
                blankToNull(safe.dataSources()), blankToNull(safe.technology()), blankToNull(safe.intendedUsers()),
                visibility, lifecycle, bytes(studyId));
        replaceResearchAreas(studyId, safe.researchAreas());
        recordSnapshotAndProfile(studyId, provenance == null ? "CURATOR_REVIEW" : provenance, actorEmail);
    }

    @Transactional
    public void recordCurrentSnapshot(UUID studyId, String actorEmail, String provenance) {
        String rawYear = jdbc.queryForObject("SELECT academic_year FROM studies WHERE id=?", String.class, bytes(studyId));
        Integer parsed = completionYear(rawYear);
        if (parsed != null) jdbc.update("UPDATE studies SET completion_year=? WHERE id=?", parsed, bytes(studyId));
        recordSnapshotAndProfile(studyId, provenance == null ? "SYSTEM_SNAPSHOT" : provenance, actorEmail);
    }

    /**
     * Captures existing rows exactly once as version 1. This is provenance, not a
     * data-cleaning rewrite: absent authors, years, results, and links stay absent.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(100)
    @Transactional
    public void initializeLegacySnapshots() {
        List<UUID> ids = jdbc.query("SELECT id FROM studies ORDER BY created_at, id",
                (row, index) -> uuid(row.getBytes(1)));
        for (UUID id : ids) {
            int versions = jdbc.queryForObject("SELECT COUNT(*) FROM study_metadata_versions WHERE study_id=?", Integer.class, bytes(id));
            if (versions == 0) {
                String rawYear = jdbc.queryForObject("SELECT academic_year FROM studies WHERE id=?", String.class, bytes(id));
                Integer parsed = completionYear(rawYear);
                if (parsed != null) jdbc.update("UPDATE studies SET completion_year=? WHERE id=? AND completion_year IS NULL", parsed, bytes(id));
                recordSnapshotAndProfile(id, "LEGACY_SNAPSHOT", null);
            }
        }
        jdbc.update("UPDATE algorithm_configurations SET reproducibility_status='LEGACY_PARTIAL' " +
                "WHERE reproducibility_status IS NULL OR reproducibility_status='' OR configuration_json='{}'");
    }

    public UUID requirePublicationEligibleVersion(UUID extractionJobId) {
        List<byte[]> rows = jdbc.query("SELECT document_version_id FROM extraction_runs WHERE id=? AND publication_eligible=TRUE AND run_status='EXTRACTED'",
                (row, index) -> row.getBytes(1), bytes(extractionJobId));
        if (rows.isEmpty()) throw new IllegalArgumentException("The extraction job is not eligible for curator publication.");
        Integer used = jdbc.queryForObject("SELECT COUNT(*) FROM study_document_publications WHERE document_version_id=?", Integer.class, rows.getFirst());
        if (used != null && used > 0) throw new IllegalArgumentException("This document version is already attached to a published study.");
        return uuid(rows.getFirst());
    }

    @Transactional
    public void linkPublication(UUID studyId, UUID documentVersionId, String actorEmail) {
        byte[] actor = jdbc.queryForObject("SELECT id FROM user_accounts WHERE LOWER(email)=LOWER(?)", byte[].class, actorEmail);
        jdbc.update("INSERT INTO study_document_publications(study_id,document_version_id,published_by,published_at) VALUES(?,?,?,?)",
                bytes(studyId), bytes(documentVersionId), actor, Timestamp.from(Instant.now()));
        jdbc.update("UPDATE document_versions SET visibility='CAMPUS' WHERE id=?", bytes(documentVersionId));
    }

    private void replaceResearchAreas(UUID studyId, List<String> values) {
        jdbc.update("DELETE FROM study_terms WHERE study_id=? AND term_id IN (SELECT id FROM taxonomy_terms WHERE term_type='RESEARCH_AREA')",
                bytes(studyId));
        for (String label : distinct(values)) {
            UUID termId = UUID.nameUUIDFromBytes(("ugnay:research-area:" + normalize(label)).getBytes(StandardCharsets.UTF_8));
            int count = jdbc.queryForObject("SELECT COUNT(*) FROM taxonomy_terms WHERE term_type='RESEARCH_AREA' AND LOWER(canonical_label)=LOWER(?)",
                    Integer.class, label);
            if (count == 0) jdbc.update("INSERT INTO taxonomy_terms(id,term_type,canonical_label,filipino_label,active) VALUES(?,?,?,?,?)",
                    bytes(termId), "RESEARCH_AREA", label, null, true);
            else termId = jdbc.query("SELECT id FROM taxonomy_terms WHERE term_type='RESEARCH_AREA' AND LOWER(canonical_label)=LOWER(?)",
                    (row, index) -> uuid(row.getBytes(1)), label).getFirst();
            jdbc.update("INSERT INTO study_terms(study_id,term_id) VALUES(?,?)", bytes(studyId), bytes(termId));
        }
    }

    private void recordSnapshotAndProfile(UUID studyId, String provenance, String actorEmail) {
        Map<String, Object> snapshot = metadataSnapshot(studyId);
        String metadataJson = json(snapshot);
        String sourceSha = sha256(metadataJson);
        List<UUID> existing = jdbc.query("SELECT id FROM study_metadata_versions WHERE study_id=? AND source_sha256=?",
                (row, index) -> uuid(row.getBytes(1)), bytes(studyId), sourceSha);
        UUID metadataVersionId;
        if (existing.isEmpty()) {
            Integer maximum = jdbc.queryForObject("SELECT COALESCE(MAX(version_number),0) FROM study_metadata_versions WHERE study_id=?",
                    Integer.class, bytes(studyId));
            metadataVersionId = UUID.randomUUID();
            jdbc.update("INSERT INTO study_metadata_versions(id,study_id,version_number,provenance_type,source_sha256,metadata_json,recorded_by,recorded_at) VALUES(?,?,?,?,?,?,?,?)",
                    bytes(metadataVersionId), bytes(studyId), (maximum == null ? 0 : maximum) + 1, provenance, sourceSha,
                    metadataJson, actorId(actorEmail), Timestamp.from(Instant.now()));
        } else metadataVersionId = existing.getFirst();
        persistSearchProfile(studyId, metadataVersionId, snapshot);
    }

    private Map<String, Object> metadataSnapshot(UUID studyId) {
        Map<String, Object> snapshot = jdbc.queryForObject("SELECT s.*, d.code AS department_code, d.name AS department_name " +
                        "FROM studies s LEFT JOIN departments d ON d.id=s.department_id WHERE s.id=?",
                (row, index) -> studySnapshot(row), bytes(studyId));
        if (snapshot == null) throw new IllegalArgumentException("Study was not found.");
        snapshot.put("authors", jdbc.query("SELECT a.display_name, a.institutional_identifier FROM authors a JOIN study_authors sa ON sa.author_id=a.id WHERE sa.study_id=? ORDER BY sa.author_order",
                (row, index) -> orderedMap("displayName", row.getString(1), "institutionalIdentifier", row.getString(2)), bytes(studyId)));
        snapshot.put("objectives", jdbc.query("SELECT objective_order, statement_text FROM study_objectives WHERE study_id=? ORDER BY objective_order",
                (row, index) -> orderedMap("order", row.getInt(1), "statement", row.getString(2)), bytes(studyId)));
        snapshot.put("taxonomyTerms", jdbc.query("SELECT tt.term_type, tt.canonical_label FROM taxonomy_terms tt JOIN study_terms st ON st.term_id=tt.id WHERE st.study_id=? ORDER BY tt.term_type, tt.canonical_label",
                (row, index) -> orderedMap("type", row.getString(1), "label", row.getString(2)), bytes(studyId)));
        snapshot.put("continuationItems", jdbc.query("SELECT item_type,title,description,item_status,created_at FROM continuation_items WHERE study_id=? ORDER BY created_at,id",
                (row, index) -> orderedMap("type", row.getString(1), "title", row.getString(2), "description", row.getString(3),
                        "status", row.getString(4), "createdAt", instant(row.getTimestamp(5))), bytes(studyId)));
        snapshot.put("relationships", jdbc.query("SELECT source_study_id,target_study_id,relationship_type,rationale,created_at FROM study_relationships WHERE source_study_id=? OR target_study_id=? ORDER BY created_at,id",
                (row, index) -> orderedMap("sourceStudyId", uuid(row.getBytes(1)).toString(), "targetStudyId", uuid(row.getBytes(2)).toString(),
                        "type", row.getString(3), "rationale", row.getString(4), "createdAt", instant(row.getTimestamp(5))),
                bytes(studyId), bytes(studyId)));
        return snapshot;
    }

    private static Map<String, Object> studySnapshot(ResultSet row) throws SQLException {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("studyId", uuid(row.getBytes("id")).toString());
        value.put("institutionalCode", row.getString("institutional_code"));
        value.put("doi", row.getString("doi"));
        value.put("repositoryIdentifier", row.getString("repository_identifier"));
        value.put("departmentCode", row.getString("department_code"));
        value.put("departmentName", row.getString("department_name"));
        value.put("program", row.getString("program_name"));
        value.put("title", row.getString("title"));
        value.put("abstract", row.getString("abstract_text"));
        value.put("problem", row.getString("problem_statement"));
        value.put("methodology", row.getString("methodology"));
        value.put("features", row.getString("features_text"));
        value.put("dataSources", row.getString("data_sources_text"));
        value.put("technology", row.getString("technology_text"));
        value.put("intendedUsers", row.getString("intended_users_text"));
        value.put("stakeholders", row.getString("stakeholders_text"));
        value.put("siteContext", row.getString("site_context"));
        value.put("keywordsText", row.getString("keywords_text"));
        value.put("results", row.getString("results_text"));
        value.put("academicYear", row.getString("academic_year"));
        value.put("completionYear", nullableInteger(row, "completion_year"));
        value.put("lifecycleStatus", row.getString("lifecycle_status"));
        value.put("visibility", row.getString("visibility"));
        value.put("sourceRowVersion", row.getLong("row_version"));
        value.put("publishedAt", instant(row.getTimestamp("published_at")));
        value.put("archivedAt", instant(row.getTimestamp("archived_at")));
        return value;
    }

    @SuppressWarnings("unchecked")
    private void persistSearchProfile(UUID studyId, UUID metadataVersionId, Map<String, Object> snapshot) {
        String title = string(snapshot.get("title"));
        String problem = string(snapshot.get("problem"));
        List<Map<String, Object>> objectives = (List<Map<String, Object>>) snapshot.getOrDefault("objectives", List.of());
        String objectiveText = objectives.stream().map(item -> string(item.get("statement"))).filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.joining("\n"));
        String methodology = string(snapshot.get("methodology"));
        List<Map<String, Object>> terms = (List<Map<String, Object>>) snapshot.getOrDefault("taxonomyTerms", List.of());
        String keywordText = terms.stream().map(item -> string(item.get("label"))).filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.joining(" "));
        String combined = normalize(String.join("\n", title, problem, objectiveText, methodology, keywordText,
                string(snapshot.get("abstract")), string(snapshot.get("results")), string(snapshot.get("features")),
                string(snapshot.get("dataSources")), string(snapshot.get("technology")), string(snapshot.get("intendedUsers"))));
        String profileSha = sha256(combined);
        int existing = jdbc.queryForObject("SELECT COUNT(*) FROM study_search_profiles WHERE study_id=? AND normalizer_version=? AND profile_sha256=?",
                Integer.class, bytes(studyId), NORMALIZER_VERSION, profileSha);
        if (existing > 0) return;
        jdbc.update("UPDATE study_search_profiles SET profile_status='STALE' WHERE study_id=? AND profile_status='ACTIVE'", bytes(studyId));
        jdbc.update("INSERT INTO study_search_profiles(id,study_id,metadata_version_id,normalizer_version,title_text,problem_text,objectives_text,methodology_text,keyword_text,combined_text,profile_sha256,profile_status,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                bytes(UUID.randomUUID()), bytes(studyId), bytes(metadataVersionId), NORMALIZER_VERSION, normalize(title),
                normalize(problem), normalize(objectiveText), normalize(methodology), normalize(keywordText), combined,
                profileSha, "ACTIVE", Timestamp.from(Instant.now()));
    }

    private byte[] departmentId(String value) {
        if (value == null || value.isBlank()) return null;
        List<byte[]> ids = jdbc.query("SELECT id FROM departments WHERE LOWER(code)=LOWER(?) OR LOWER(name)=LOWER(?)",
                (row, index) -> row.getBytes(1), value.strip(), value.strip());
        if (ids.isEmpty()) throw new IllegalArgumentException("Department must match an existing department code or name.");
        return ids.getFirst();
    }

    private byte[] actorId(String actorEmail) {
        if (actorEmail == null || actorEmail.isBlank()) return null;
        List<byte[]> ids = jdbc.query("SELECT id FROM user_accounts WHERE LOWER(email)=LOWER(?)",
                (row, index) -> row.getBytes(1), actorEmail);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    static Integer completionYear(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.strip();
        Matcher single = SINGLE_YEAR.matcher(value);
        if (single.matches()) {
            int year = Integer.parseInt(single.group(1));
            return supportedYear(year) ? year : null;
        }
        Matcher range = YEAR_RANGE.matcher(value);
        if (!range.matches()) return null;
        int start = Integer.parseInt(range.group(1));
        int end = Integer.parseInt(range.group(2));
        return supportedYear(start) && supportedYear(end) && end == start + 1 ? end : null;
    }

    private static boolean supportedYear(int value) { return value >= 1900 && value <= 2200; }

    private static String allowListed(String value, Set<String> choices, String label, String fallback) {
        String normalized = value == null || value.isBlank() ? fallback : value.strip().toUpperCase(Locale.ROOT);
        if (!choices.contains(normalized)) throw new IllegalArgumentException("Unsupported " + label + ".");
        return normalized;
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JacksonException exception) { throw new IllegalStateException("Catalogue evidence could not be serialized.", exception); }
    }

    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    private static List<String> distinct(List<String> values) {
        if (values == null) return List.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) if (value != null && !value.isBlank()) result.add(value.strip());
        return List.copyOf(result);
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required.");
        return value.strip();
    }

    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.strip(); }
    private static String string(Object value) { return value == null ? "" : String.valueOf(value); }
    private static String instant(Timestamp value) { return value == null ? null : value.toInstant().toString(); }
    private static Integer nullableInteger(ResultSet row, String column) throws SQLException { int value = row.getInt(column); return row.wasNull() ? null : value; }
    private static byte[] bytes(UUID id) { return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array(); }
    private static UUID uuid(byte[] value) { ByteBuffer buffer = ByteBuffer.wrap(value); return new UUID(buffer.getLong(), buffer.getLong()); }

    private static Map<String, Object> orderedMap(Object... pairs) {
        Map<String, Object> value = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) value.put(String.valueOf(pairs[index]), pairs[index + 1]);
        return value;
    }

    public record Metadata(String program, List<String> authors, String relationship) {}
    public record ReviewedEvidence(String academicYear, String department, String resultsText, String dataSources,
                                   String technology, String intendedUsers, List<String> researchAreas,
                                   String visibility, String lifecycleStatus) {
        static ReviewedEvidence empty() { return new ReviewedEvidence(null, null, null, null, null, null, List.of(), null, null); }
    }
}
