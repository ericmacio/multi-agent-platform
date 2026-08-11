package com.cognizant.emk.multiagent.application.ratelimit;

import com.cognizant.emk.multiagent.domain.ratelimit.RateLimitConfig;
import com.cognizant.emk.multiagent.domain.ratelimit.RateLimitConfigRepository;
import org.springframework.stereotype.Service;

/** Default {@link GetRateLimitConfigUseCase} implementation — delegates to the repository. */
@Service
public class GetRateLimitConfigService implements GetRateLimitConfigUseCase {

    private final RateLimitConfigRepository repository;

    public GetRateLimitConfigService(RateLimitConfigRepository repository) {
        this.repository = repository;
    }

    @Override
    public RateLimitConfig load() {
        return repository.load();
    }
}
