package io.github.mochiuaena.triage.conversation;

import static io.github.mochiuaena.triage.conversation.ConversationModels.*;

import static org.assertj.core.api.Assertions.*;

import io.github.mochiuaena.triage.material.MaterialModels.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.*;
import java.util.*;

@SpringBootTest(
        properties = {
            "triage.mode=DEMO",
            "spring.datasource.url=jdbc:h2:mem:conversation-store;DB_CLOSE_DELAY=-1",
            "triage.settings.key-file=target/conversation-store-key"
        })
class ConversationStoreTest {
    @Autowired ConversationStore store;

    Material original() {
        return new Material(
                new Reference(
                        Type.SERVICE,
                        UUID.randomUUID(),
                        new Scope(null, null, "service"),
                        "fixed",
                        "hash",
                        Instant.now(),
                        "UNCONFIRMED",
                        "/?run=old"),
                "original",
                List.of("question"),
                List.of("no source"));
    }

    Attempt attempt(UUID id, String state) {
        return new Attempt(id, state, state, Instant.now(), null, null, null, null, "hash");
    }

    Message message(UUID id, String role) {
        return new Message(UUID.randomUUID(), id, role, "text", Instant.now(), List.of(), false);
    }

    @Test
    void budgetDefaultsPersistAtomicallyAndCannotChangeDuringAnAttempt() {
        var c = store.create(original());
        assertThat(c.budget()).isEqualTo(Budget.defaults());
        var budget = new Budget(16000, 2, 1000);
        store.budget(c.id(), 0, budget);
        assertThat(store.require(c.id()).budget()).isEqualTo(budget);
        assertThat(store.require(c.id()).revision()).isEqualTo(1);
        assertThatThrownBy(() -> store.budget(c.id(), 0, Budget.defaults()))
                .hasMessageContaining("对话已变化");
        var a = attempt(UUID.randomUUID(), "RUNNING");
        store.admit(c.id(), 1, message(a.id(), "USER"), a, true);
        assertThatThrownBy(() -> store.budget(c.id(), 2, Budget.defaults()))
                .hasMessageContaining("对话已变化");
        assertThat(store.require(c.id()).budget()).isEqualTo(budget);
    }

    @Test
    void admissionIsAtomicAndLateCancelledRepliesCannotPublish() {
        var c = store.create(original());
        var a = attempt(UUID.randomUUID(), "RUNNING");
        store.admit(c.id(), 0, message(a.id(), "USER"), a, true);
        assertThatThrownBy(
                        () ->
                                store.admit(
                                        c.id(),
                                        0,
                                        message(UUID.randomUUID(), "USER"),
                                        attempt(UUID.randomUUID(), "RUNNING"),
                                        true))
                .hasMessageContaining("对话已变化");
        assertThat(store.finish(c.id(), attempt(a.id(), "CANCELLED"), null)).isTrue();
        assertThat(store.finish(c.id(), attempt(a.id(), "SUCCEEDED"), message(a.id(), "ASSISTANT")))
                .isFalse();
        assertThat(store.require(c.id()).messages()).hasSize(1);
        assertThat(store.require(c.id()).original()).isEqualTo(c.original());
    }

    @Test
    void restartInterruptsWithoutRepeatingAndOtherConversationStaysIndependent() {
        var a = store.create(original());
        var b = store.create(original());
        var attempt = attempt(UUID.randomUUID(), "RUNNING");
        store.admit(a.id(), 0, message(attempt.id(), "USER"), attempt, true);
        store.interruptUnfinished();
        assertThat(store.require(a.id()).attempts().getFirst().state()).isEqualTo("INTERRUPTED");
        assertThat(store.require(a.id()).activeAttempt()).isNull();
        assertThat(store.require(b.id()).messages()).isEmpty();
        assertThat(store.require(b.id()).revision()).isZero();
    }
}
