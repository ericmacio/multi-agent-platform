package com.cognizant.emk.multiagent.application.apikey;

import com.cognizant.emk.multiagent.application.apikey.ListApiKeysUseCase.ListApiKeysQuery;
import com.cognizant.emk.multiagent.application.shared.PageSize;
import com.cognizant.emk.multiagent.domain.auth.ApiKey;
import com.cognizant.emk.multiagent.domain.auth.ApiKeyRepository;
import com.cognizant.emk.multiagent.domain.auth.ClientId;
import com.cognizant.emk.multiagent.domain.shared.Cursor;
import com.cognizant.emk.multiagent.domain.shared.Page;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ListApiKeysServiceTest {

    @Mock private ApiKeyRepository apiKeyRepository;
    @InjectMocks private ListApiKeysService service;

    @Test
    void forwards_a_null_cursor_to_the_repository_for_the_first_page() {
        OffsetDateTime now = OffsetDateTime.of(2026, 5, 12, 8, 0, 0, 0, ZoneOffset.UTC);
        ApiKey ak = new ApiKey(new ClientId("svc"), bcryptHash(), null, false, now);
        Page<ApiKey> repoPage = new Page<>(List.of(ak), null, 20);
        when(apiKeyRepository.listAll(null, 20)).thenReturn(repoPage);

        Page<ApiKey> result = service.list(new ListApiKeysQuery(null, PageSize.fromQueryParam(null)));

        assertThat(result).isSameAs(repoPage);
    }

    @Test
    void forwards_the_cursor_and_page_size_verbatim() {
        Cursor cursor = new Cursor(OffsetDateTime.now(ZoneOffset.UTC), "abc");
        Page<ApiKey> repoPage = new Page<>(List.of(), null, 50);
        when(apiKeyRepository.listAll(cursor, 50)).thenReturn(repoPage);

        Page<ApiKey> result = service.list(new ListApiKeysQuery(cursor, new PageSize(50)));

        assertThat(result).isSameAs(repoPage);
    }

    private static String bcryptHash() {
        return "$2a$10$00000000000000000000000000000000000000000000000000000";
    }
}
