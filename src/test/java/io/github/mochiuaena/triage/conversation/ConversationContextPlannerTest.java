package io.github.mochiuaena.triage.conversation;

import static io.github.mochiuaena.triage.conversation.ConversationModels.*;
import static io.github.mochiuaena.triage.material.MaterialModels.*;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

class ConversationContextPlannerTest {
    final ConversationContextPlanner planner =
            new ConversationContextPlanner(new ObjectMapper().findAndRegisterModules());

    Conversation conversation(Budget budget, String original, int count) {
        var now = Instant.parse("2026-10-24T00:00:00Z");
        var report =
                new Material(
                        new Reference(
                                Type.SERVICE,
                                UUID.randomUUID(),
                                new Scope(null, null, "service"),
                                "v1",
                                "hash",
                                now,
                                "UNCONFIRMED",
                                "/?run=old"),
                        original,
                        List.of(),
                        List.of());
        var messages = new ArrayList<Message>();
        for (int i = 0; i < count; i++)
            messages.add(
                    new Message(
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            i % 2 == 0 ? "USER" : "ASSISTANT",
                            "用户说：已确认？仍需检查。\"😀\n".repeat(130) + i,
                            now,
                            List.of(),
                            i == 0));
        return new Conversation(
                UUID.randomUUID(), 0, null, now, now, report, messages, List.of(), budget);
    }

    @Test
    void boundedSerializedEnvelopeRetainsOriginalRecentAndAttributedExcerptsDeterministically()
            throws Exception {
        var c = conversation(Budget.defaults(), "ORIGINAL_QUESTION", 18);
        var a = planner.plan(c, "CURRENT_QUESTION", List.of(), List.of(), 2048);
        var b = planner.plan(c, "CURRENT_QUESTION", List.of(), List.of(), 2048);
        assertThat(a.text()).hasSizeLessThanOrEqualTo(c.budget().maxInputCharacters());
        assertThat(a.text()).contains("ORIGINAL_QUESTION", "CURRENT_QUESTION");
        assertThat(a.history()).hasSize(8);
        assertThat(a.summary().excerpts()).isNotEmpty();
        assertThat(a.summary().version()).isEqualTo(b.summary().version());
        assertThat(a.summary().provenance()).contains("verbatim", "not verified");
        assertThat(
                        a.summary().recentMessages()
                                + a.summary().excerptedMessages()
                                + a.summary().omittedMessages())
                .isEqualTo(18);
        for (var e : a.summary().excerpts())
            assertThat(e.text())
                    .isEqualTo(
                            c.messages()
                                    .get(e.ordinal() - 1)
                                    .text()
                                    .substring(e.startOffset(), e.endOffset()));
        assertThat(new ObjectMapper().readTree(a.text()).path("messages")).hasSize(2);
    }

    @Test
    void wireSummaryReferencesDeduplicatedCitationSnapshots() throws Exception {
        var source = conversation(Budget.defaults(), "DEDUPLICATED_REPORT_MARKER", 3);
        var old = source.messages().getFirst();
        var messages = new ArrayList<>(source.messages());
        messages.set(
                0,
                new Message(
                        old.id(),
                        old.attemptId(),
                        old.role(),
                        old.text(),
                        old.createdAt(),
                        List.of(
                                new Citation(
                                        "report:" + source.original().reference().reportId(),
                                        source.original(),
                                        null)),
                        false));
        var c =
                new Conversation(
                        source.id(),
                        source.revision(),
                        null,
                        source.createdAt(),
                        source.updatedAt(),
                        source.original(),
                        messages,
                        List.of(),
                        new Budget(48000, 2, 3000));
        var p = planner.plan(c, "question", List.of(), List.of(), 2048);
        var payload =
                new ObjectMapper()
                        .readTree(
                                new ObjectMapper()
                                        .readTree(p.text())
                                        .path("messages")
                                        .get(1)
                                        .path("content")
                                        .asText());
        assertThat(payload.path("summary").path("excerpts").get(0).has("citations")).isFalse();
        assertThat(payload.path("summary").path("excerpts").get(0).path("evidenceIds")).hasSize(1);
        assertThat(payload.path("citations")).hasSize(1);
        assertThat(p.summary().excerpts().getFirst().citations()).hasSize(1);
    }

    @Test
    void zeroSummaryExplicitlyOmitsOlderMessagesAndBudgetAccountsForEscapes() {
        var c = conversation(new Budget(8000, 2, 0), "原始\"😀".repeat(30), 16);
        var p = planner.plan(c, "现问\"😀", List.of(), List.of(), 2048);
        assertThat(p.summary().excerpts()).isEmpty();
        assertThat(p.summary().omittedMessages()).isGreaterThanOrEqualTo(14);
        assertThat(p.serializedCharacters()).isEqualTo(p.text().length()).isLessThanOrEqualTo(8000);
    }

    @Test
    void mandatoryMaterialCannotBeSilentlyReplacedAndBudgetsAreValidated() {
        assertThatThrownBy(() -> new Budget(7999, 8, 3000)).hasMessageContaining("预算");
        assertThatThrownBy(() -> new Budget(48000, 13, 3000)).hasMessageContaining("预算");
        assertThatThrownBy(() -> new Budget(48000, 8, 6001)).hasMessageContaining("预算");
        var c = conversation(new Budget(8000, 2, 0), "x".repeat(9000), 0);
        assertThatThrownBy(() -> planner.plan(c, "question", List.of(), List.of(), 2048))
                .hasMessageContaining("必要");
    }
}
