package com.cognizant.emk.multiagent.application.auth;

import com.cognizant.emk.multiagent.application.auth.LogoutUseCase.LogoutCommand;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class LogoutServiceTest {

    @Mock private JwtDenylist denylist;
    @InjectMocks private LogoutService logoutService;

    @Test
    void delegates_to_denylist_with_jti_and_expiresAt() {
        UUID jti = UUID.randomUUID();
        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(30);

        logoutService.logout(new LogoutCommand(jti, expiresAt));

        verify(denylist).add(jti, expiresAt);
    }

    @Test
    void calling_twice_with_the_same_jti_is_idempotent_at_the_use_case_boundary() {
        UUID jti = UUID.randomUUID();
        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(30);

        logoutService.logout(new LogoutCommand(jti, expiresAt));
        logoutService.logout(new LogoutCommand(jti, expiresAt));

        // The use case forwards both calls; idempotency is enforced by the denylist itself
        // (a ConcurrentHashMap.put with the same value overwrites identically). Asserting
        // exactly two invocations documents that the service does no extra work.
        verify(denylist, times(2)).add(jti, expiresAt);
    }
}
