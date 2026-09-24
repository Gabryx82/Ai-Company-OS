package com.aicompany.backend.user.service;

import com.aicompany.backend.user.model.SecurityEvent;
import com.aicompany.backend.user.repository.SecurityEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The security log (ADR-024 §5): every event goes to the {@code security_events}
 * table and to the {@code aicos.security} logger.
 *
 * <p>Its own transaction, so that a refusal whose transaction is rolled back is
 * still recorded. Values are cleaned before they are written: control characters
 * removed (no forged log lines) and lengths capped. Passwords and tokens are
 * never passed here -- the callers pass names and outcomes.
 */
@Service
public class SecurityLog {

    public static final String LOGIN_SUCCEEDED = "LOGIN_SUCCEEDED";
    public static final String LOGIN_FAILED = "LOGIN_FAILED";
    public static final String ACCOUNT_LOCKED = "ACCOUNT_LOCKED";
    public static final String LOGIN_THROTTLED = "LOGIN_THROTTLED";
    public static final String LOGOUT = "LOGOUT";
    public static final String PASSWORD_CHANGED = "PASSWORD_CHANGED";
    public static final String PASSWORD_RESET = "PASSWORD_RESET";
    public static final String ADMIN_BOOTSTRAPPED = "ADMIN_BOOTSTRAPPED";
    public static final String USER_CREATED = "USER_CREATED";
    public static final String USER_UPDATED = "USER_UPDATED";
    public static final String ACCESS_DENIED = "ACCESS_DENIED";
    public static final String DATA_DELETED = "DATA_DELETED";
    public static final String PROCESS_STARTED = "PROCESS_STARTED";

    private static final Logger LOG = LoggerFactory.getLogger("aicos.security");

    private final SecurityEventRepository events;

    public SecurityLog(SecurityEventRepository events) {
        this.events = events;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String type, String principal, String source, String detail) {
        String who = clean(principal, 64);
        String from = clean(source, 64);
        String what = clean(detail, 500);
        events.save(new SecurityEvent(type, who, from, what));
        LOG.info("{} principal={} source={} {}", type, who, from, what == null ? "" : what);
    }

    @Transactional(readOnly = true)
    public List<SecurityEvent> latest(int limit) {
        return events.findAllByOrderByOccurredAtDescIdDesc(PageRequest.of(0, Math.max(1, Math.min(limit, 500))));
    }

    static String clean(String value, int max) {
        if (value == null) {
            return null;
        }
        String visible = value.replaceAll("\\p{Cntrl}", " ").strip();
        return visible.length() > max ? visible.substring(0, max) : visible;
    }
}
