package com.cognizant.emk.multiagent.application.user;

import com.cognizant.emk.multiagent.application.shared.PageSize;
import com.cognizant.emk.multiagent.domain.shared.Cursor;
import com.cognizant.emk.multiagent.domain.shared.Page;
import com.cognizant.emk.multiagent.domain.user.User;

/**
 * Use case for {@code GET /admin/users} (REQ-USR-005, REQ-API-005).
 *
 * <p>Returns a single page of users ordered newest-first. The REST adapter decodes the
 * opaque wire cursor into a domain {@link Cursor} before constructing the query, so
 * the application layer remains free of any {@code CursorCodec} dependency (same
 * pattern as {@code ListApiKeysQuery}).
 */
public interface ListUsersUseCase {

    Page<User> list(ListUsersQuery query);

    /** Inputs. {@code cursor} is {@code null} on the first page. */
    record ListUsersQuery(Cursor cursor, PageSize pageSize) {}
}
