package com.aicompany.backend.user.service;

import java.time.Clock;

/** Reaches the package-private clock constructor of {@link LoginThrottle} from other test packages. */
public final class LoginThrottleProbe {

    private LoginThrottleProbe() {
    }

    public static LoginThrottle of(int limit, Clock clock) {
        return new LoginThrottle(limit, clock);
    }
}
