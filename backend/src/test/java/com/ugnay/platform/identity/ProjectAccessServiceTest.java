package com.ugnay.platform.identity;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectAccessServiceTest {

    @Test
    void publicDemoModeNeverGrantsAnonymousProjectAccess() {
        ProjectAccessService publicDemo = new ProjectAccessService(null, null, true);
        var anonymous = new AnonymousAuthenticationToken("test", "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

        assertThat(publicDemo.canAccess(null, UUID.randomUUID())).isFalse();
        assertThat(publicDemo.canAccess(anonymous, UUID.randomUUID())).isFalse();
        assertThat(publicDemo.canAccess(
                UsernamePasswordAuthenticationToken.unauthenticated("reader@example.test", ""), UUID.randomUUID()))
                .isFalse();
    }

    @Test
    void curatorAuthorityRemainsSeparateFromProjectMembership() {
        ProjectAccessService service = new ProjectAccessService(null, null, false);
        var curator = UsernamePasswordAuthenticationToken.authenticated("curator@example.test", "",
                AuthorityUtils.createAuthorityList("ROLE_CURATOR"));

        assertThat(service.canAccess(curator, UUID.randomUUID())).isTrue();
    }
}
