package com.cognizant.emk.multiagent.infrastructure.web.ratelimit;

import com.cognizant.emk.multiagent.application.ratelimit.GetRateLimitConfigUseCase;
import com.cognizant.emk.multiagent.application.ratelimit.UpdateRateLimitConfigCommand;
import com.cognizant.emk.multiagent.application.ratelimit.UpdateRateLimitConfigUseCase;
import com.cognizant.emk.multiagent.domain.auth.UserPrincipal;
import com.cognizant.emk.multiagent.domain.ratelimit.RateLimitConfig;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin REST adapter for the live rate-limit configuration (REQ-RL-004,
 * design §6.2.4).
 *
 * <p>Class-level {@code @PreAuthorize("hasRole('ADMIN')")} is defense in depth
 * on top of the URL-level rule {@code /api/v1/admin/** → hasRole('ADMIN')}
 * from {@code SpringSecurityConfig}. STANDARD JWTs and SYSTEM API-key callers
 * both get 403 here.
 *
 * <p>No class-level {@code @RequestMapping}: the {@code /api/v1} prefix is
 * applied centrally by {@code WebConfig} (REQ-API-006).
 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
public class RateLimitAdminController {

    private final GetRateLimitConfigUseCase getUseCase;
    private final UpdateRateLimitConfigUseCase updateUseCase;

    public RateLimitAdminController(
            GetRateLimitConfigUseCase getUseCase,
            UpdateRateLimitConfigUseCase updateUseCase) {
        this.getUseCase = getUseCase;
        this.updateUseCase = updateUseCase;
    }

    @GetMapping("/admin/rate-limit")
    public RateLimitConfigResponse get() {
        return RateLimitConfigResponseMapper.toResponse(getUseCase.load());
    }

    @PutMapping("/admin/rate-limit")
    public RateLimitConfigResponse update(
            @Valid @RequestBody RateLimitConfigRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        RateLimitConfig updated = updateUseCase.update(new UpdateRateLimitConfigCommand(
                request.perMinute(),
                request.perHour(),
                principal.id()));
        return RateLimitConfigResponseMapper.toResponse(updated);
    }
}
