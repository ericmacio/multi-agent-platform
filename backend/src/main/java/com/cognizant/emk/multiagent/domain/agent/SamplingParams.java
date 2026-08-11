package com.cognizant.emk.multiagent.domain.agent;

import com.cognizant.emk.multiagent.domain.shared.ValidationException;

/**
 * Optional per-agent LLM overrides (REQ-AGT-001, §4.1). Every field is nullable —
 * when {@code llmModel} is {@code null} the platform default applies
 * (REQ-LLM-002, set in {@code app.llm.openai.default-model}).
 *
 * <p>Numeric range validation for {@code temperature} and {@code topP} is the
 * subject of TBD-4 in the design and is deliberately NOT enforced here; the value
 * object accepts any non-null number for now. {@code maxOutputTokens} is bounded
 * below at {@code 1} because any non-positive value is structurally meaningless,
 * regardless of TBD-4.
 *
 * <p>The static {@link #DEFAULTS} instance is all-{@code null} — the convenient
 * "use platform defaults" choice.
 */
public record SamplingParams(
        String llmModel,
        Double temperature,
        Integer maxOutputTokens,
        Double topP) {

    public static final SamplingParams DEFAULTS = new SamplingParams(null, null, null, null);

    private static final int MAX_LLM_MODEL_LENGTH = 64;

    public SamplingParams {
        if (llmModel != null && llmModel.length() > MAX_LLM_MODEL_LENGTH) {
            throw new ValidationException(
                    "llmModel", "must be at most " + MAX_LLM_MODEL_LENGTH + " characters");
        }
        if (maxOutputTokens != null && maxOutputTokens < 1) {
            throw new ValidationException("maxOutputTokens", "must be >= 1");
        }
    }
}
