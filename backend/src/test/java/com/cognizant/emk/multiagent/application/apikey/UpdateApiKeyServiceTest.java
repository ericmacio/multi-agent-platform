package com.cognizant.emk.multiagent.application.apikey;

import com.cognizant.emk.multiagent.application.apikey.UpdateApiKeyUseCase.UpdateApiKeyCommand;
import com.cognizant.emk.multiagent.domain.auth.ApiKey;
import com.cognizant.emk.multiagent.domain.auth.ApiKeyNotFoundException;
import com.cognizant.emk.multiagent.domain.auth.ApiKeyRepository;
import com.cognizant.emk.multiagent.domain.auth.ClientId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UpdateApiKeyServiceTest {

    @Mock private ApiKeyRepository apiKeyRepository;
    @InjectMocks private UpdateApiKeyService service;

    private static final ClientId CLIENT_ID = new ClientId("svc-ci");
    private static final String HASH = "$2a$10$abcdefghijklmnopqrstuuJqf2QHm/rEZx8L0a3T1aPgI8Vm/tnsW";

    @Test
    void toggles_disabled_to_true_writes_the_partial_update_and_returns_updated_aggregate() {
        OffsetDateTime when = OffsetDateTime.now(ZoneOffset.UTC);
        ApiKey existing = new ApiKey(CLIENT_ID, HASH, "ci", false, when);
        when(apiKeyRepository.findByClientId(CLIENT_ID)).thenReturn(Optional.of(existing));

        ApiKey result = service.updateDisabled(new UpdateApiKeyCommand(CLIENT_ID, true));

        assertThat(result.disabled()).isTrue();
        assertThat(result.clientId()).isEqualTo(CLIENT_ID);
        assertThat(result.label()).isEqualTo("ci");
        assertThat(result.apiKeyHash()).isEqualTo(HASH);
        assertThat(result.createdAt()).isEqualTo(when);

        verify(apiKeyRepository).updateDisabled(CLIENT_ID, true);
    }

    @Test
    void unknown_client_id_raises_ApiKeyNotFoundException_without_writing() {
        when(apiKeyRepository.findByClientId(CLIENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateDisabled(new UpdateApiKeyCommand(CLIENT_ID, true)))
                .isInstanceOf(ApiKeyNotFoundException.class);

        verify(apiKeyRepository, never()).updateDisabled(CLIENT_ID, true);
    }

    @Test
    void toggling_back_to_false_is_symmetric() {
        OffsetDateTime when = OffsetDateTime.now(ZoneOffset.UTC);
        ApiKey existing = new ApiKey(CLIENT_ID, HASH, "ci", true, when);
        when(apiKeyRepository.findByClientId(CLIENT_ID)).thenReturn(Optional.of(existing));

        ApiKey result = service.updateDisabled(new UpdateApiKeyCommand(CLIENT_ID, false));

        assertThat(result.disabled()).isFalse();
        verify(apiKeyRepository).updateDisabled(CLIENT_ID, false);
    }
}
