package com.cognizant.emk.multiagent.application.apikey;

import com.cognizant.emk.multiagent.domain.auth.ClientId;
import java.time.OffsetDateTime;

/**
 * Use case for {@code POST /admin/api-keys} (REQ-AUTH-007, REQ-AUTH-012).
 *
 * <p>Generates a fresh machine-to-machine credential, persists the BCrypt hash, and
 * returns the cleartext API key exactly once in the result so the REST adapter can
 * surface it in the response body. The cleartext is unrecoverable after that moment —
 * it never reaches the {@code ApiKey} domain aggregate (only the hash does) and is
 * never logged.
 */
public interface CreateApiKeyUseCase {

    CreateApiKeyResult create(CreateApiKeyCommand command);

    /** Inputs. {@code label} is nullable; the service trims it to {@code null} when blank. */
    record CreateApiKeyCommand(String label) {}

    /**
     * Outputs of a successful create. {@code cleartextApiKey} is the secret value the
     * caller must persist on their side — the platform stores only its BCrypt hash.
     */
    record CreateApiKeyResult(
            ClientId clientId,
            String cleartextApiKey,
            String label,
            boolean disabled,
            OffsetDateTime createdAt) {}
}
