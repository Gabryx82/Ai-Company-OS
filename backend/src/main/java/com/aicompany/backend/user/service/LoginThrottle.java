package com.aicompany.backend.user.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * At most {@code limit} sign-in attempts per client address per minute
 * (ADR-024 §2). The per-account lock stops guessing one password; this stops
 * trying many usernames. In memory on purpose: it protects one process on one
 * machine, and a restart forgetting it is harmless.
 */
@Component
public class LoginThrottle {

    private static final long WINDOW_MILLIS = 60_000;

    private final int limit;
    private final Clock clock;
    private final Map<String, Deque<Long>> attempts = new ConcurrentHashMap<>();

    @Autowired
    public LoginThrottle(@Value("${aicos.security.login.attempts-per-minute:20}") int limit) {
        this(limit, Clock.systemUTC());
    }

    LoginThrottle(int limit, Clock clock) {
        this.limit = limit;
        this.clock = clock;
    }

    /** Records an attempt; false when this source is over its budget. */
    public boolean allow(String source) {
        long now = clock.millis();
        Deque<Long> window = attempts.computeIfAbsent(source == null ? "?" : source, key -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && window.peekFirst() < now - WINDOW_MILLIS) {
                window.pollFirst();
            }
            if (window.size() >= limit) {
                return false;
            }
            window.addLast(now);
            return true;
        }
    }

    public void reset() {
        attempts.clear();
    }
}
