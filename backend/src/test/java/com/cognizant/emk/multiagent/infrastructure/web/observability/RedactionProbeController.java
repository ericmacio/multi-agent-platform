package com.cognizant.emk.multiagent.infrastructure.web.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only probe controller for the EPIC-15 / US-15-004 redaction
 * regression test. Lives in {@code src/test/java} only — never on the
 * main classpath — per the US-CR1-002 convention recorded in
 * {@code backend/CLAUDE.md}.
 *
 * <p>Each endpoint emits a single INFO log statement carrying one of the
 * three sensitive patterns the {@link
 * com.cognizant.emk.multiagent.infrastructure.web.error
 * .SensitiveDataMaskingConverter} is supposed to mask:
 * {@code Bearer <jwt>}, BCrypt hash, bare JWT. The integration test
 * captures the encoded output and asserts the raw substrings are absent.
 *
 * <p>{@code isAuthenticated()} keeps the probe consistent with the rest
 * of the feature surface; the integration test logs in once to obtain a
 * JWT before invoking it.
 */
@RestController
public class RedactionProbeController {

    private static final Logger LOG = LoggerFactory.getLogger(RedactionProbeController.class);

    @PostMapping("/_redaction_probe/log-bearer")
    @PreAuthorize("isAuthenticated()")
    public String logBearer() {
        LOG.info("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.test.signature");
        return "ok";
    }

    @PostMapping("/_redaction_probe/log-bcrypt")
    @PreAuthorize("isAuthenticated()")
    public String logBcrypt() {
        LOG.info("BCrypt hash: $2b$12$abcdefghijklmnopqrstuv.ABCDEFGHIJKLMNOPQRSTUVWXYZ012345");
        return "ok";
    }

    @PostMapping("/_redaction_probe/log-jwt")
    @PreAuthorize("isAuthenticated()")
    public String logJwt() {
        LOG.info("Bare JWT: eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZXN0In0.signature-bytes-long-enough");
        return "ok";
    }

    @PostMapping("/_redaction_probe/log-clean")
    @PreAuthorize("isAuthenticated()")
    public String logClean() {
        LOG.info("non-sensitive content passes through");
        return "ok";
    }
}
