package com.ugnay.platform.identity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityContextRepository repository,
            @Value("${ugnay.security.public-demo-read:true}") boolean publicDemoRead) throws Exception {
        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookiePath("/");
        http.cors(Customizer.withDefaults())
                .csrf(csrf -> {
                    csrf.csrfTokenRepository(csrfRepository).ignoringRequestMatchers("/api/v1/auth/login",
                            "/api/v1/auth/invitations/accept", "/api/v1/system/shutdown");
                })
                .securityContext(context -> context.securityContextRepository(repository))
                .authorizeHttpRequests(authorize -> {
                    authorize.requestMatchers("/", "/index.html", "/favicon.ico", "/assets/**",
                            "/atlas", "/intake", "/decision", "/alignment", "/changes", "/continuity", "/reviews",
                            "/research-lab/query", "/research-lab/evaluation", "/research-lab/warehouse",
                            "/projects/*/alignment", "/projects/*/changes", "/projects/*/continuity", "/projects/*/reviews",
                            "/actuator/health", "/actuator/health/**", "/error", "/openapi.yaml", "/api/v1/auth/**",
                            "/api/v1/system/shutdown").permitAll();
                    authorize.requestMatchers("/h2-console/**").permitAll();
                    authorize.requestMatchers(HttpMethod.GET, "/api/v1/users", "/api/v1/invitations", "/api/v1/audit-events").hasRole("CURATOR");
                    authorize.requestMatchers(HttpMethod.GET, "/api/v1/imports/**").hasRole("CURATOR");
                    if (publicDemoRead) authorize.requestMatchers(HttpMethod.POST, "/api/v1/discovery-runs").permitAll();
                    if (publicDemoRead) authorize.requestMatchers(HttpMethod.GET, "/api/v1/**").permitAll();
                    else authorize.requestMatchers(HttpMethod.GET, "/api/v1/**").authenticated();
                    authorize.requestMatchers(HttpMethod.POST, "/api/v1/imports/**").hasRole("CURATOR");
                    authorize.requestMatchers(HttpMethod.POST, "/api/v1/invitations/**").hasRole("CURATOR");
                    authorize.requestMatchers(HttpMethod.POST, "/api/v1/proposal-decisions/**").hasRole("COORDINATOR");
                    authorize.requestMatchers(HttpMethod.POST, "/api/v1/projects/*/complete").hasRole("COORDINATOR");
                    authorize.requestMatchers(HttpMethod.POST, "/api/v1/projects/*/baselines/approve").hasRole("COORDINATOR");
                    authorize.requestMatchers(HttpMethod.POST, "/api/v1/projects/*/analysis-runs").hasAnyRole("ADVISER", "COORDINATOR");
                    authorize.requestMatchers(HttpMethod.POST, "/api/v1/projects/*/trace-items",
                            "/api/v1/projects/*/trace-items/*/revisions", "/api/v1/projects/*/trace-links",
                            "/api/v1/projects/*/test-executions", "/api/v1/projects/*/completion-package/evidence")
                            .hasAnyRole("STUDENT", "ADVISER", "COORDINATOR");
                    authorize.requestMatchers(HttpMethod.POST, "/api/v1/problems", "/api/v1/proposals", "/api/v1/discovery-runs",
                            "/api/v1/intakes")
                            .hasAnyRole("STUDENT", "ADVISER", "COORDINATOR");
                    authorize.requestMatchers(HttpMethod.POST, "/api/v1/change-requests", "/api/v1/change-requests/*/preview-impact")
                            .hasAnyRole("STUDENT", "ADVISER", "COORDINATOR");
                    authorize.requestMatchers(HttpMethod.POST, "/api/v1/lineage/check").authenticated();
                    authorize.anyRequest().authenticated();
                })
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                .httpBasic(httpBasic -> httpBasic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable());
        return http.build();
    }

    @Bean
    SessionAuthenticationStrategy sessionAuthenticationStrategy() {
        return new ChangeSessionIdAuthenticationStrategy();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${ugnay.security.allowed-origins:}") String origins) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.stream(origins.split(",")).map(String::trim).filter(value -> !value.isBlank()).toList());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN", "If-Match", "Idempotency-Key", "Accept"));
        configuration.setExposedHeaders(List.of("ETag", "Location", "X-Page", "X-Page-Size", "X-Total-Count", "X-Truncated"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
