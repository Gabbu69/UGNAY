package com.ugnay.platform.identity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LoginThrottleService {
    private final Map<String, AttemptWindow> attempts = new ConcurrentHashMap<>();
    private final int maximumFailures;
    private final Duration observationWindow;
    private final Duration lockDuration;
    private final Clock clock;

    @Autowired
    public LoginThrottleService(
            @Value("${ugnay.security.login-throttle.max-failures:5}") int maximumFailures,
            @Value("${ugnay.security.login-throttle.window-minutes:10}") long windowMinutes,
            @Value("${ugnay.security.login-throttle.lock-minutes:15}") long lockMinutes) {
        this(maximumFailures, Duration.ofMinutes(windowMinutes), Duration.ofMinutes(lockMinutes), Clock.systemUTC());
    }

    LoginThrottleService(int maximumFailures, Duration observationWindow, Duration lockDuration, Clock clock) {
        this.maximumFailures = maximumFailures;
        this.observationWindow = observationWindow;
        this.lockDuration = lockDuration;
        this.clock = clock;
    }

    public synchronized boolean allowed(String key) {
        AttemptWindow window = attempts.get(key);
        if (window == null) return true;
        Instant now = clock.instant();
        if (window.lockedUntil != null && window.lockedUntil.isAfter(now)) return false;
        prune(window, now);
        if (window.failures.size() < maximumFailures) return true;
        window.lockedUntil = now.plus(lockDuration);
        return false;
    }

    public synchronized void failure(String key) {
        Instant now = clock.instant();
        AttemptWindow window = attempts.computeIfAbsent(key, ignored -> new AttemptWindow());
        prune(window, now);
        window.failures.addLast(now);
        if (window.failures.size() >= maximumFailures) window.lockedUntil = now.plus(lockDuration);
    }

    public void success(String key) { attempts.remove(key); }

    private void prune(AttemptWindow window, Instant now) {
        while (!window.failures.isEmpty() && window.failures.getFirst().isBefore(now.minus(observationWindow))) window.failures.removeFirst();
        if (window.lockedUntil != null && !window.lockedUntil.isAfter(now)) window.lockedUntil = null;
    }

    private static final class AttemptWindow {
        private final ArrayDeque<Instant> failures = new ArrayDeque<>();
        private Instant lockedUntil;
    }
}
