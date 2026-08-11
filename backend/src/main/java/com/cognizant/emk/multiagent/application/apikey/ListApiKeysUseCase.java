package com.cognizant.emk.multiagent.application.apikey;

import com.cognizant.emk.multiagent.application.shared.PageSize;
import com.cognizant.emk.multiagent.domain.auth.ApiKey;
import com.cognizant.emk.multiagent.domain.shared.Cursor;
import com.cognizant.emk.multiagent.domain.shared.Page;

/**
 * Use case for {@code GET /admin/api-keys} (REQ-AUTH-012).
 *
 * <p>Returns a single page of API keys ordered newest-first. Metadata only — the
 * cleartext API key was shown once at creation and the BCrypt hash is never exposed
 * (REQ-SEC-004).
 */
public interface ListApiKeysUseCase {

    Page<ApiKey> list(ListApiKeysQuery query);

    /**
     * Inputs. {@code cursor} is {@code null} on the first page. The REST adapter is
     * responsible for decoding the opaque base64url query parameter into a {@link Cursor}
     * via {@code CursorCodec} (which lives in {@code infrastructure/web/pagination}); the
     * application layer never sees the wire form.
     */
    record ListApiKeysQuery(Cursor cursor, PageSize pageSize) {}
}
