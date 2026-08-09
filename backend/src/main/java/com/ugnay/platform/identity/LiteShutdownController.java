package com.ugnay.platform.identity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Loopback-only launcher hook. The lite profile itself binds the HTTP server to 127.0.0.1. */
@RestController
@Profile("lite")
@RequestMapping("/api/v1/system")
final class LiteShutdownController {
    private final ConfigurableApplicationContext context;
    private final String expectedToken;

    LiteShutdownController(ConfigurableApplicationContext context,
                           @Value("${UGNAY_SHUTDOWN_TOKEN:}") String expectedToken) {
        this.context = context;
        this.expectedToken = expectedToken;
    }

    @PostMapping("/shutdown")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, String> shutdown(@RequestHeader(value = "X-UGNAY-Shutdown", required = false) String suppliedToken) {
        if (expectedToken.isBlank() || suppliedToken == null || !constantTimeEquals(expectedToken, suppliedToken)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The local shutdown token is invalid.");
        }
        CompletableFuture.runAsync(() -> {
            try { Thread.sleep(250); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            context.close();
        });
        return Map.of("status", "SHUTTING_DOWN");
    }

    private static boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }
}
