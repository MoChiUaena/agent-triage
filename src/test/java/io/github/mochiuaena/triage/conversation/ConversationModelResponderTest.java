package io.github.mochiuaena.triage.conversation;

import static io.github.mochiuaena.triage.conversation.ConversationModels.*;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.util.*;

class ConversationModelResponderTest {
    final ConversationModelResponder parser =
            new ConversationModelResponder(null, null, new ObjectMapper());
    final List<Citation> citations = List.of(new Citation("report:fixed", null, null));

    @Test
    void acceptsOnlyStrictCompleteKnownCitations() {
        assertThat(
                        parser.parse(
                                        "{\"answer\":\"evidence"
                                            + " missing\",\"evidenceIds\":[\"report:fixed\"]}",
                                        citations)
                                .answer())
                .isEqualTo("evidence missing");
        for (String value :
                List.of(
                        "{\"answer\":\"x\",\"evidenceIds\":[\"unknown\"]}",
                        "{\"answer\":\"x\",\"evidenceIds\":[]}",
                        "{\"answer\":\"x\",\"evidenceIds\":[\"report:fixed\"],\"tool_calls\":[]}",
                        "{\"answer\":\"x\",\"answer\":\"y\",\"evidenceIds\":[\"report:fixed\"]}",
                        "{\"answer\":\"x\",\"evidenceIds\":[\"report:fixed\"]} {}",
                        "```json\n{}\n```"))
            assertThatThrownBy(() -> parser.parse(value, citations))
                    .isInstanceOf(IllegalArgumentException.class);
    }
}
