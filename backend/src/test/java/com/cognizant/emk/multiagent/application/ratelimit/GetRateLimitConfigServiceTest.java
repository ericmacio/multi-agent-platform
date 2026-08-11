package com.cognizant.emk.multiagent.application.ratelimit;

import com.cognizant.emk.multiagent.domain.ratelimit.RateLimitConfig;
import com.cognizant.emk.multiagent.domain.ratelimit.RateLimitConfigRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetRateLimitConfigServiceTest {

    @Mock
    private RateLimitConfigRepository repository;

    @Test
    void delegates_to_repository_load() {
        RateLimitConfig stored = new RateLimitConfig(
                10, 50,
                OffsetDateTime.of(2026, 6, 19, 12, 0, 0, 0, ZoneOffset.UTC),
                Optional.empty());
        when(repository.load()).thenReturn(stored);

        GetRateLimitConfigService service = new GetRateLimitConfigService(repository);
        RateLimitConfig loaded = service.load();

        assertThat(loaded).isSameAs(stored);
        verify(repository).load();
    }
}
