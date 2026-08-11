package com.cognizant.emk.multiagent.application.apikey;

import com.cognizant.emk.multiagent.application.apikey.CreateApiKeyUseCase.CreateApiKeyCommand;
import com.cognizant.emk.multiagent.application.apikey.CreateApiKeyUseCase.CreateApiKeyResult;
import com.cognizant.emk.multiagent.application.auth.ApiKeyGenerator;
import com.cognizant.emk.multiagent.application.auth.ApiKeyGenerator.GeneratedApiKey;
import com.cognizant.emk.multiagent.application.auth.ApiKeyHasher;
import com.cognizant.emk.multiagent.domain.auth.ApiKey;
import com.cognizant.emk.multiagent.domain.auth.ApiKeyRepository;
import com.cognizant.emk.multiagent.domain.auth.ClientId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreateApiKeyServiceTest {

    @Mock private ApiKeyGenerator apiKeyGenerator;
    @Mock private ApiKeyHasher apiKeyHasher;
    @Mock private ApiKeyRepository apiKeyRepository;

    private Clock clock;
    private CreateApiKeyService service;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-05-12T08:00:00Z"), ZoneOffset.UTC);
        service = new CreateApiKeyService(apiKeyGenerator, apiKeyHasher, apiKeyRepository, clock);
    }

    @Test
    void happy_path_returns_cleartext_and_persists_only_the_hash() {
        ClientId clientId = new ClientId("svc-ci");
        String cleartext = "cleartext-secret";
        String hash = "$2a$10$abcdefghijklmnopqrstuuJqf2QHm/rEZx8L0a3T1aPgI8Vm/tnsW";
        when(apiKeyGenerator.generate()).thenReturn(new GeneratedApiKey(clientId, cleartext));
        when(apiKeyHasher.hash(cleartext)).thenReturn(hash);
        when(apiKeyRepository.save(any(ApiKey.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateApiKeyResult result = service.create(new CreateApiKeyCommand("ci"));

        assertThat(result.clientId()).isEqualTo(clientId);
        assertThat(result.cleartextApiKey()).isEqualTo(cleartext);
        assertThat(result.label()).isEqualTo("ci");
        assertThat(result.disabled()).isFalse();
        assertThat(result.createdAt()).isEqualTo(clock.instant().atOffset(ZoneOffset.UTC));

        ArgumentCaptor<ApiKey> captor = ArgumentCaptor.forClass(ApiKey.class);
        org.mockito.Mockito.verify(apiKeyRepository).save(captor.capture());
        ApiKey persisted = captor.getValue();
        // The persisted aggregate carries only the BCrypt hash, never the cleartext.
        assertThat(persisted.apiKeyHash()).isEqualTo(hash);
        assertThat(persisted.apiKeyHash()).isNotEqualTo(cleartext);
        assertThat(persisted.disabled()).isFalse();
    }

    @Test
    void null_label_round_trips_as_null_through_the_pipeline() {
        when(apiKeyGenerator.generate())
                .thenReturn(new GeneratedApiKey(new ClientId("svc"), "ct"));
        when(apiKeyHasher.hash("ct")).thenReturn("$2a$10$00000000000000000000000000000000000000000000000000000");
        when(apiKeyRepository.save(any(ApiKey.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateApiKeyResult result = service.create(new CreateApiKeyCommand(null));

        assertThat(result.label()).isNull();
    }

    @Test
    void blank_label_is_normalized_to_null_by_the_domain() {
        when(apiKeyGenerator.generate())
                .thenReturn(new GeneratedApiKey(new ClientId("svc"), "ct"));
        when(apiKeyHasher.hash("ct")).thenReturn("$2a$10$00000000000000000000000000000000000000000000000000000");
        when(apiKeyRepository.save(any(ApiKey.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateApiKeyResult result = service.create(new CreateApiKeyCommand("   "));

        assertThat(result.label()).isNull();
    }
}
