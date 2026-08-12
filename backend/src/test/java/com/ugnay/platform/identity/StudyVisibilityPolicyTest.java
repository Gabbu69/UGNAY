package com.ugnay.platform.identity;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StudyVisibilityPolicyTest {
    private final StudyVisibilityPolicy policy = new StudyVisibilityPolicy(null);
    private final StudyVisibilityPolicy.Scope cics = new StudyVisibilityPolicy.Scope(
            true, false, UUID.randomUUID(), "CICS", "College of Information and Computing Sciences");

    @Test
    void curatorCanReadEveryVisibilityWhileAnonymousReadsNothing() {
        assertThat(policy.canView(StudyVisibilityPolicy.Scope.curatorScope(), "RESTRICTED", "Another college"))
                .isTrue();
        assertThat(policy.canView(StudyVisibilityPolicy.Scope.anonymousScope(), "PUBLIC", "CICS"))
                .isFalse();
    }

    @Test
    void publicAndCampusAreGlobalButRestrictedEvidenceIsAlwaysCuratorOnly() {
        assertThat(policy.canView(cics, "PUBLIC", "Another college")).isTrue();
        assertThat(policy.canView(cics, "CAMPUS", "Another college")).isTrue();
        assertThat(policy.canView(cics, "RESTRICTED", "College of Information and Computing Sciences")).isFalse();
        assertThat(policy.canView(cics, "EMBARGOED", "CICS")).isFalse();
    }

    @Test
    void otherNonRestrictedVisibilityRequiresTheSameDepartment() {
        assertThat(policy.canView(cics, "INTERNAL", "CICS")).isTrue();
        assertThat(policy.canView(cics, "INTERNAL", "College of Information and Computing Sciences")).isTrue();
        assertThat(policy.canView(cics, "INTERNAL", "College of Engineering")).isFalse();
        assertThat(policy.canView(cics, null, "CICS")).isFalse();
    }

    @Test
    void sqlRestrictionIsFailClosedAndBindsDepartmentInsteadOfEmbeddingIt() {
        StudyVisibilityPolicy.SqlRestriction anonymous = policy.studyTableRestriction(
                StudyVisibilityPolicy.Scope.anonymousScope());
        StudyVisibilityPolicy.SqlRestriction scoped = policy.studyTableRestriction(cics);

        assertThat(anonymous.clause()).contains("1=0");
        assertThat(scoped.clause()).contains("s.department_id=?", "RESTRICTED", "EMBARGOED");
        assertThat(scoped.parameters()).hasSize(1);
        assertThat(policy.studyTableRestriction(StudyVisibilityPolicy.Scope.curatorScope()).clause()).isEmpty();
    }
}
