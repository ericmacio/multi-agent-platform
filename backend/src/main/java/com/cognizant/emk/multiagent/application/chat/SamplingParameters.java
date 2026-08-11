package com.cognizant.emk.multiagent.application.chat;

import com.cognizant.emk.multiagent.domain.shared.ValidationException;

/**
 * Per-turn LLM sampling overrides (design §12, REQ-AGT-001). Every field is nullable —
 * {@code null} means "use the provider / Spring AI default", matching the optional
 * agent attributes {@code temperature}, {@code maxOutputTokens}, {@code topP}.
 *
 * <p>The non-null ranges are conservative caps so the adapter never forwards a
 * malformed value to the provider (TBD-4 in the design carries the broader
 * tightening question; v1 commits to OpenAI's documented ranges).
 */
public record SamplingParameters(Double temperature, Integer maxOutputTokens, Double topP) {

    private static final SamplingParameters NONE = new SamplingParameters(null, null, null);

    public SamplingParameters {
        if (temperature != null && (temperature < 0.0 || temperature > 2.0)) {
            throw new ValidationException("temperature", "must be in [0.0, 2.0]");
        }
        if (maxOutputTokens != null && maxOutputTokens < 1) {
            throw new ValidationException("maxOutputTokens", "must be >= 1");
        }
        if (topP != null && (topP <= 0.0 || topP > 1.0)) {
            throw new ValidationException("topP", "must be in (0.0, 1.0]");
        }
    }

    public static SamplingParameters none() {
        return NONE;
    }
}
