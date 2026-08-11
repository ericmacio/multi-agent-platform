package com.cognizant.emk.multiagent.application.auth;

import org.springframework.stereotype.Service;

/**
 * Default {@link LogoutUseCase} implementation.
 *
 * <p>Idempotency comes for free from {@link JwtDenylist#add(java.util.UUID, java.time.OffsetDateTime)}
 * — the underlying {@code ConcurrentHashMap.put} simply overwrites with the same value. No
 * extra check is needed in this service.
 */
@Service
public class LogoutService implements LogoutUseCase {

    private final JwtDenylist denylist;

    public LogoutService(JwtDenylist denylist) {
        this.denylist = denylist;
    }

    @Override
    public void logout(LogoutCommand command) {
        denylist.add(command.jti(), command.expiresAt());
    }
}
