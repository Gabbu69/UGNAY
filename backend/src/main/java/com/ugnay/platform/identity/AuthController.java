package com.ugnay.platform.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository contextRepository;
    private final JdbcIdentityService identities;
    private final LoginThrottleService throttle;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;

    public AuthController(AuthenticationManager authenticationManager, SecurityContextRepository contextRepository,
                          JdbcIdentityService identities, LoginThrottleService throttle,
                          SessionAuthenticationStrategy sessionAuthenticationStrategy) {
        this.authenticationManager = authenticationManager;
        this.contextRepository = contextRepository;
        this.identities = identities;
        this.throttle = throttle;
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
    }

    @PostMapping("/login")
    public AuthView login(@Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest, HttpServletResponse servletResponse) {
        String key = servletRequest.getRemoteAddr() + "|" + request.email().toLowerCase();
        if (!throttle.allowed(key)) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many failed login attempts. Try again later.");
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(request.email().toLowerCase(), request.password()));
            throttle.success(key);
        } catch (AuthenticationException exception) {
            throttle.failure(key);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password.");
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        sessionAuthenticationStrategy.onAuthentication(authentication, servletRequest, servletResponse);
        contextRepository.saveContext(context, servletRequest, servletResponse);
        return view(authentication);
    }

    @GetMapping("/me")
    public AuthView me(Authentication authentication) {
        return authentication == null ? new AuthView(false, null, List.of()) : view(authentication);
    }

    @GetMapping("/csrf")
    public CsrfView csrf(CsrfToken token, HttpServletRequest request) {
        // Materialize the pre-authentication server session so a successful login can rotate it.
        request.getSession(true);
        return new CsrfView(token.getHeaderName(), token.getParameterName(), token.getToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        if (request.getSession(false) != null) request.getSession(false).invalidate();
        SecurityContextHolder.clearContext();
        contextRepository.saveContext(SecurityContextHolder.createEmptyContext(), request, response);
    }

    @PostMapping("/invitations/accept")
    @ResponseStatus(HttpStatus.CREATED)
    public JdbcIdentityService.UserView acceptInvitation(@Valid @RequestBody AcceptInvitationRequest request) {
        return identities.accept(request.token(), request.displayName(), request.password());
    }

    private static AuthView view(Authentication authentication) {
        List<String> roles = authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .filter(authority -> authority.startsWith("ROLE_"))
                .map(authority -> authority.substring("ROLE_".length())).toList();
        return new AuthView(true, authentication.getName(), roles);
    }

    public record LoginRequest(@NotBlank @Email @Size(max = 254) String email, @NotBlank @Size(max = 128) String password) {}
    public record AcceptInvitationRequest(@NotBlank @Size(max = 256) String token,
                                          @NotBlank @Size(min = 2, max = 160) String displayName,
                                          @NotBlank @Size(min = 12, max = 128) String password) {}
    public record AuthView(boolean authenticated, String email, List<String> roles) {}
    public record CsrfView(String headerName, String parameterName, String token) {}
}
