package com.ugnay.platform.workspace;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.UUID;
import java.sql.Timestamp;
import java.time.Instant;

@Repository
public class CatalogueMetadataRepository {
    private final JdbcTemplate jdbc;

    public CatalogueMetadataRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Metadata metadata(UUID studyId) {
        String program = jdbc.queryForObject("SELECT program_name FROM studies WHERE id=?", String.class, bytes(studyId));
        List<String> authors = jdbc.query("SELECT a.display_name FROM authors a JOIN study_authors sa ON sa.author_id=a.id WHERE sa.study_id=? ORDER BY sa.author_order",
                (row, index) -> row.getString(1), bytes(studyId));
        List<String> relationships = jdbc.query("SELECT relationship_type FROM study_relationships WHERE source_study_id=? OR target_study_id=? ORDER BY created_at DESC",
                (row, index) -> row.getString(1), bytes(studyId), bytes(studyId));
        return new Metadata(program == null || program.isBlank() ? "Program not recorded" : program,
                List.copyOf(authors), relationships.isEmpty() ? "SIMILAR" : relationships.getFirst());
    }

    @Transactional
    public void updatePublication(UUID studyId, String program, List<String> authors, String doi, String repositoryIdentifier) {
        jdbc.update("UPDATE studies SET program_name=?, doi=?, repository_identifier=? WHERE id=?",
                required(program, "Program"), blankToNull(doi), blankToNull(repositoryIdentifier), bytes(studyId));
        jdbc.update("DELETE FROM study_authors WHERE study_id=?", bytes(studyId));
        int order = 0;
        for (String value : authors == null ? List.<String>of() : authors) {
            String name = required(value, "Author name");
            UUID authorId = UUID.nameUUIDFromBytes(("ugnay:author:" + name.toLowerCase()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            int count = jdbc.queryForObject("SELECT COUNT(*) FROM authors WHERE id=?", Integer.class, bytes(authorId));
            if (count == 0) jdbc.update("INSERT INTO authors(id, display_name, institutional_identifier) VALUES(?,?,?)",
                    bytes(authorId), name, null);
            jdbc.update("INSERT INTO study_authors(study_id, author_id, author_order) VALUES(?,?,?)",
                    bytes(studyId), bytes(authorId), order++);
        }
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

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required.");
        return value.strip();
    }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.strip(); }
    private static byte[] bytes(UUID id) { return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array(); }
    private static UUID uuid(byte[] value) { ByteBuffer buffer = ByteBuffer.wrap(value); return new UUID(buffer.getLong(), buffer.getLong()); }
    public record Metadata(String program, List<String> authors, String relationship) {}
}
