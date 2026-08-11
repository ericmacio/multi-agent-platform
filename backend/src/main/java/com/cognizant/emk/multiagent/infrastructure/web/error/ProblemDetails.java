package com.cognizant.emk.multiagent.infrastructure.web.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * RFC 7807 Problem Details body used as the single error response shape across the API.
 *
 * <p>Shape matches design §9.3. The {@code code} field is a stable machine identifier the
 * frontend can switch on; {@link #errors} carries optional per-field validation details and
 * is omitted from the JSON when null. Serialized as {@code application/problem+json}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProblemDetails(
        String type,
        String title,
        int status,
        String detail,
        String instance,
        String code,
        List<FieldError> errors
) {

    public ProblemDetails {
        if (errors != null && errors.isEmpty()) {
            errors = null;
        }
    }

    public static ProblemDetails of(String code, String title, int status, String detail, String instance) {
        return new ProblemDetails(
                "https://errors.multi-agent-platform/" + code.toLowerCase().replace('_', '-'),
                title,
                status,
                detail,
                instance,
                code,
                null);
    }

    public static ProblemDetails of(
            String code, String title, int status, String detail, String instance, List<FieldError> errors) {
        return new ProblemDetails(
                "https://errors.multi-agent-platform/" + code.toLowerCase().replace('_', '-'),
                title,
                status,
                detail,
                instance,
                code,
                errors);
    }

    public record FieldError(String field, String message) {}
}
