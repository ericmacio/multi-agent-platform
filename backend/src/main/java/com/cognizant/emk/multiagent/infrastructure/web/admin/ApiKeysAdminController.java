package com.cognizant.emk.multiagent.infrastructure.web.admin;

import com.cognizant.emk.multiagent.application.apikey.CreateApiKeyUseCase;
import com.cognizant.emk.multiagent.application.apikey.CreateApiKeyUseCase.CreateApiKeyCommand;
import com.cognizant.emk.multiagent.application.apikey.CreateApiKeyUseCase.CreateApiKeyResult;
import com.cognizant.emk.multiagent.application.apikey.ListApiKeysUseCase;
import com.cognizant.emk.multiagent.application.apikey.ListApiKeysUseCase.ListApiKeysQuery;
import com.cognizant.emk.multiagent.application.apikey.UpdateApiKeyUseCase;
import com.cognizant.emk.multiagent.application.apikey.UpdateApiKeyUseCase.UpdateApiKeyCommand;
import com.cognizant.emk.multiagent.application.shared.PageSize;
import com.cognizant.emk.multiagent.domain.auth.ApiKey;
import com.cognizant.emk.multiagent.domain.auth.ClientId;
import com.cognizant.emk.multiagent.domain.shared.Cursor;
import com.cognizant.emk.multiagent.domain.shared.Page;
import com.cognizant.emk.multiagent.infrastructure.web.pagination.CursorCodec;
import com.cognizant.emk.multiagent.infrastructure.web.pagination.PageDto;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST adapter for the admin API-key endpoints (REQ-AUTH-007 / REQ-AUTH-012,
 * design §6.2.3).
 *
 * <p>Class-level {@code @PreAuthorize("hasRole('ADMIN')")} is defense in depth on top
 * of the URL-level rule {@code /api/v1/admin/** → hasRole('ADMIN')} introduced in
 * US-04-009. Both layers reject STANDARD JWTs and SYSTEM API-key callers with 403.
 *
 * <p>No class-level {@code @RequestMapping}: the {@code /api/v1} prefix is applied
 * centrally by {@code WebConfig} (REQ-API-006).
 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
public class ApiKeysAdminController {

    private final CreateApiKeyUseCase createApiKeyUseCase;
    private final ListApiKeysUseCase listApiKeysUseCase;
    private final UpdateApiKeyUseCase updateApiKeyUseCase;
    private final CursorCodec cursorCodec;

    public ApiKeysAdminController(
            CreateApiKeyUseCase createApiKeyUseCase,
            ListApiKeysUseCase listApiKeysUseCase,
            UpdateApiKeyUseCase updateApiKeyUseCase,
            CursorCodec cursorCodec) {
        this.createApiKeyUseCase = createApiKeyUseCase;
        this.listApiKeysUseCase = listApiKeysUseCase;
        this.updateApiKeyUseCase = updateApiKeyUseCase;
        this.cursorCodec = cursorCodec;
    }

    // ------- US-04-006: POST /admin/api-keys -------

    @PostMapping("/admin/api-keys")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiKeyCreatedResponse create(@Valid @RequestBody(required = false) CreateApiKeyRequest request) {
        String label = request == null ? null : request.label();
        CreateApiKeyResult result = createApiKeyUseCase.create(new CreateApiKeyCommand(label));
        return new ApiKeyCreatedResponse(
                result.clientId().value(),
                result.cleartextApiKey(),
                result.label(),
                result.disabled(),
                result.createdAt());
    }

    // ------- US-04-007: GET /admin/api-keys -------

    @GetMapping("/admin/api-keys")
    public PageDto<ApiKeyResponse> list(
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "pageSize", required = false) Integer pageSize) {
        // Decode the wire cursor at the boundary; the application layer is cursor-codec-free.
        // A malformed cursor surfaces as VALIDATION_ERROR with field "cursor" via the
        // GlobalExceptionHandler (the codec throws ValidationException on bad input).
        Cursor decoded = cursorCodec.decode(cursor);
        PageSize ps = PageSize.fromQueryParam(pageSize);

        Page<ApiKey> page = listApiKeysUseCase.list(new ListApiKeysQuery(decoded, ps));
        return PageDto.of(page, cursorCodec, ApiKeyResponseMapper::toResponse);
    }

    // ------- US-04-008: PATCH /admin/api-keys/{clientId} -------

    @PatchMapping("/admin/api-keys/{clientId}")
    public ApiKeyResponse updateDisabled(
            @PathVariable("clientId") String clientId,
            @Valid @RequestBody UpdateApiKeyRequest request) {
        // The ClientId constructor enforces the format invariants; a malformed path
        // variable surfaces as VALIDATION_ERROR (field "clientId") via the global handler.
        ApiKey updated = updateApiKeyUseCase.updateDisabled(
                new UpdateApiKeyCommand(new ClientId(clientId), request.disabled()));
        return ApiKeyResponseMapper.toResponse(updated);
    }
}
