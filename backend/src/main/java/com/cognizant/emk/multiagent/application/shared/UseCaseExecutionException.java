package com.cognizant.emk.multiagent.application.shared;

import com.cognizant.emk.multiagent.domain.shared.BusinessException;

/**
 * Wraps an unrecoverable orchestration failure inside an application use case
 * (design §9.1, REQ-ARC-007). Surfaces as 500 {@code INTERNAL_ERROR} at the REST
 * boundary; the wrapped cause is logged at {@code ERROR} but never returned in
 * the response body (REQ-API-004, REQ-SEC-004).
 *
 * <p>Use cases SHOULD let domain {@link BusinessException} and infrastructure
 * {@code ExternalServiceException} flow through unchanged — they have their
 * own typed handlers. Reach for this type only when neither applies, e.g.,
 * a contract-impossible state between two ports.
 */
public final class UseCaseExecutionException extends RuntimeException {

    public UseCaseExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
