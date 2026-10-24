package io.github.mochiuaena.triage.conversation;

import static io.github.mochiuaena.triage.conversation.ConversationModels.*;
import static io.github.mochiuaena.triage.material.MaterialModels.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mochiuaena.triage.domain.TriageModel.ModelSource;
import io.github.mochiuaena.triage.material.MaterialService;
import io.github.mochiuaena.triage.material.SendPreviewService;
import io.github.mochiuaena.triage.settings.ProviderConfig;
import io.github.mochiuaena.triage.settings.ProviderRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

class ConversationPreviewLifecycleTest {
    @Test
    void repeatedProviderInvalidationsReleaseBoundedSnapshotsButConsentFailuresRemainRetryable() {
        var materials = mock(MaterialService.class);
        var providers = mock(ProviderRegistry.class);
        var store = mock(ConversationStore.class);
        var responder = mock(ConversationModelResponder.class);
        var version = new AtomicLong(1);
        UUID provider = UUID.randomUUID(),
                conversationId = UUID.randomUUID(),
                reportId = UUID.randomUUID();
        Instant now = Instant.now();
        var original =
                new Material(
                        new Reference(
                                Type.SERVICE,
                                reportId,
                                new Scope(null, null, "fixture"),
                                "fixed",
                                "hash",
                                now,
                                "UNCONFIRMED",
                                "/?run=" + reportId),
                        "saved report",
                        List.of("question"),
                        List.of());
        // Included document-derived history exercises the separate, retryable document consent.
        var history =
                new Message(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "ASSISTANT",
                        "prior document-derived answer",
                        now,
                        List.of(),
                        true);
        var conversation =
                new Conversation(
                        conversationId, 0, null, now, now, original, List.of(history), List.of());
        when(store.require(conversationId)).thenReturn(conversation);
        when(materials.resolve(any())).thenReturn(original);
        when(responder.outputTokenLimit()).thenReturn(256);
        when(providers.selection())
                .thenReturn(new ProviderConfig.Selection("MODEL", provider, "LOCAL"));
        when(providers.activeModel())
                .thenAnswer(
                        invocation ->
                                new ProviderRegistry.ModelAccess(
                                        null,
                                        "fixture-model",
                                        new ModelSource(provider, "fixture", version.get()),
                                        Duration.ofSeconds(1),
                                        "http://127.0.0.1:18199/v1"));
        var previews = new SendPreviewService(materials, providers);
        var service =
                new ConversationService(
                        store,
                        materials,
                        previews,
                        null,
                        null,
                        null,
                        responder,
                        new ObjectMapper().findAndRegisterModules());
        try {
            for (int i = 0; i < 101; i++)
                service.review(conversationId, 0, "no-key local review", List.of());
            assertThat(retained(service)).isEmpty();
            verify(responder, never()).respond(any(), any());
            for (int batch = 0; batch < 2; batch++) {
                var created = new ArrayList<Prepared>();
                for (int i = 0; i < 100; i++)
                    created.add(service.preview(conversationId, 0, "same question", List.of()));
                assertThat(retained(service).size()).isEqualTo(100);
                assertThatThrownBy(
                                () -> service.preview(conversationId, 0, "one too many", List.of()))
                        .isInstanceOfSatisfying(
                                ResponseStatusException.class,
                                error -> assertThat(error.getStatusCode().value()).isEqualTo(429));
                UUID first = created.getFirst().preview().id();
                assertThatThrownBy(() -> service.send(conversationId, first, false, true))
                        .isInstanceOfSatisfying(
                                ResponseStatusException.class,
                                error -> assertThat(error.getStatusCode().value()).isEqualTo(400));
                assertThatThrownBy(() -> service.send(conversationId, first, true, false))
                        .isInstanceOfSatisfying(
                                ResponseStatusException.class,
                                error -> assertThat(error.getStatusCode().value()).isEqualTo(400));
                assertThat(retained(service)).containsKey(first);
                version.incrementAndGet(); // Same effective token cap, new recipient configuration
                // version.
                for (int i = 0; i < created.size(); i++) {
                    UUID id = created.get(i).preview().id();
                    assertThatThrownBy(() -> service.send(conversationId, id, true, true))
                            .isInstanceOfSatisfying(
                                    ResponseStatusException.class,
                                    error ->
                                            assertThat(error.getStatusCode().value())
                                                    .isEqualTo(409));
                    assertThat(retained(service))
                            .as("retained snapshots after invalidation %s", i)
                            .hasSize(99 - i);
                }
            }
            UUID expired =
                    service.preview(conversationId, 0, "will expire", List.of()).preview().id();
            ReflectionTestUtils.setField(
                    previews, "clock", Clock.fixed(now.plusSeconds(660), ZoneOffset.UTC));
            assertThatThrownBy(() -> service.send(conversationId, expired, true, true))
                    .isInstanceOfSatisfying(
                            ResponseStatusException.class,
                            error -> assertThat(error.getStatusCode().value()).isEqualTo(410));
            assertThat(retained(service)).isEmpty();
            verify(responder, never()).respond(any(), any());
            verify(store, never()).admit(any(), anyLong(), any(), any(), anyBoolean());
        } finally {
            service.close();
        }
    }

    private Map<UUID, ?> retained(ConversationService service) {
        return (Map<UUID, ?>) ReflectionTestUtils.getField(service, "prepared");
    }
}
