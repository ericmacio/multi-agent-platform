package com.cognizant.emk.multiagent.infrastructure.web.admin;

import com.cognizant.emk.multiagent.application.shared.PageSize;
import com.cognizant.emk.multiagent.application.user.CreateUserUseCase;
import com.cognizant.emk.multiagent.application.user.CreateUserUseCase.CreateUserCommand;
import com.cognizant.emk.multiagent.application.user.DeleteUserUseCase;
import com.cognizant.emk.multiagent.application.user.GetUserUseCase;
import com.cognizant.emk.multiagent.application.user.ListUsersUseCase;
import com.cognizant.emk.multiagent.application.user.ListUsersUseCase.ListUsersQuery;
import com.cognizant.emk.multiagent.application.user.UpdateUserUseCase;
import com.cognizant.emk.multiagent.application.user.UpdateUserUseCase.UpdateUserCommand;
import com.cognizant.emk.multiagent.domain.shared.Cursor;
import com.cognizant.emk.multiagent.domain.shared.Page;
import com.cognizant.emk.multiagent.domain.shared.ValidationException;
import com.cognizant.emk.multiagent.domain.user.Email;
import com.cognizant.emk.multiagent.domain.user.Password;
import com.cognizant.emk.multiagent.domain.user.Role;
import com.cognizant.emk.multiagent.domain.user.User;
import com.cognizant.emk.multiagent.domain.user.UserId;
import com.cognizant.emk.multiagent.infrastructure.web.pagination.CursorCodec;
import com.cognizant.emk.multiagent.infrastructure.web.pagination.PageDto;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST adapter for the admin user-management endpoints (REQ-USR-001 .. REQ-USR-006,
 * design §6.2.2).
 *
 * <p>Class-level {@code @PreAuthorize("hasRole('ADMIN')")} is defense in depth on top
 * of the URL-level rule {@code /api/v1/admin/** → hasRole('ADMIN')} from US-04-009.
 * Both layers reject STANDARD JWTs and SYSTEM API-key callers with 403.
 *
 * <p>No class-level {@code @RequestMapping}: the {@code /api/v1} prefix is applied
 * centrally by {@code WebConfig} (REQ-API-006). Mirrors the {@code ApiKeysAdminController}
 * pattern from EPIC-04.
 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
public class UsersAdminController {

    private final CreateUserUseCase createUserUseCase;
    private final ListUsersUseCase listUsersUseCase;
    private final GetUserUseCase getUserUseCase;
    private final UpdateUserUseCase updateUserUseCase;
    private final DeleteUserUseCase deleteUserUseCase;
    private final CursorCodec cursorCodec;

    public UsersAdminController(
            CreateUserUseCase createUserUseCase,
            ListUsersUseCase listUsersUseCase,
            GetUserUseCase getUserUseCase,
            UpdateUserUseCase updateUserUseCase,
            DeleteUserUseCase deleteUserUseCase,
            CursorCodec cursorCodec) {
        this.createUserUseCase = createUserUseCase;
        this.listUsersUseCase = listUsersUseCase;
        this.getUserUseCase = getUserUseCase;
        this.updateUserUseCase = updateUserUseCase;
        this.deleteUserUseCase = deleteUserUseCase;
        this.cursorCodec = cursorCodec;
    }

    // ------- US-05-004: POST /admin/users -------

    @PostMapping("/admin/users")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse create(@Valid @RequestBody CreateUserRequest request) {
        // Build the value objects at the boundary so any format / policy violation
        // surfaces as a per-field 400 VALIDATION_ERROR via the GlobalExceptionHandler.
        Email email = new Email(request.email());
        Password password = parsePassword(request.password());
        Role role = parseRole(request.role());
        User created = createUserUseCase.create(new CreateUserCommand(email, password, role));
        return UserResponseMapper.toResponse(created);
    }

    // ------- US-05-005: GET /admin/users -------

    @GetMapping("/admin/users")
    public PageDto<UserResponse> list(
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "pageSize", required = false) Integer pageSize) {
        Cursor decoded = cursorCodec.decode(cursor);
        PageSize ps = PageSize.fromQueryParam(pageSize);
        Page<User> page = listUsersUseCase.list(new ListUsersQuery(decoded, ps));
        return PageDto.of(page, cursorCodec, UserResponseMapper::toResponse);
    }

    // ------- US-05-006: GET /admin/users/{userId} -------

    @GetMapping("/admin/users/{userId}")
    public UserResponse get(@PathVariable("userId") UUID userId) {
        User user = getUserUseCase.get(new UserId(userId));
        return UserResponseMapper.toResponse(user);
    }

    // ------- US-05-007: PATCH /admin/users/{userId} -------

    @PatchMapping("/admin/users/{userId}")
    public UserResponse update(
            @PathVariable("userId") UUID userId,
            @Valid @RequestBody UpdateUserRequest request) {
        User updated = updateUserUseCase.updateDisabled(
                new UpdateUserCommand(new UserId(userId), request.disabled()));
        return UserResponseMapper.toResponse(updated);
    }

    // ------- US-05-008: DELETE /admin/users/{userId} -------

    @DeleteMapping("/admin/users/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable("userId") UUID userId) {
        deleteUserUseCase.delete(new UserId(userId));
    }

    // ------- helpers -------

    /**
     * Rethrows policy violations under field {@code "password"} (the default
     * {@code Password} constructor uses the same field name, but this wrapper future-proofs
     * against any future change to the value object's default).
     */
    private static Password parsePassword(String cleartext) {
        try {
            return new Password(cleartext);
        } catch (ValidationException ex) {
            throw new ValidationException("password", ex.getMessage());
        }
    }

    /**
     * Converts the request's string role into the {@code Role} enum. Unknown values
     * surface as 400 {@code VALIDATION_ERROR} with field {@code "role"} rather than the
     * generic Spring deserialization error.
     */
    private static Role parseRole(String raw) {
        try {
            return Role.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            throw new ValidationException("role", "must be one of ADMIN, STANDARD");
        }
    }
}
