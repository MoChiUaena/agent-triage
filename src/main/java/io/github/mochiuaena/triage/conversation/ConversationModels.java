package io.github.mochiuaena.triage.conversation;

import io.github.mochiuaena.triage.domain.TriageModel.TokenUsage;
import io.github.mochiuaena.triage.knowledge.KnowledgeSearchService;
import io.github.mochiuaena.triage.material.MaterialModels.*;

import java.time.Instant;
import java.util.*;

public final class ConversationModels {
    private ConversationModels() {}

    public record ChunkSelection(UUID versionId, UUID chunkId) {}

    public record MemorySelection(UUID memoryId, long revision) {}

    /** Compact citation of one exact confirmed memory version; the full basis never leaves local storage. */
    public record MemoryCitation(
            UUID memoryId,
            long revision,
            String scopeKind,
            UUID scopeId,
            String scopeService,
            String topic,
            String content,
            Instant confirmedAt,
            String bodySha256) {}

    public record Citation(String id, Material report, KnowledgeSearchService.Citation chunk, MemoryCitation memory) {
        public Citation(String id, Material report, KnowledgeSearchService.Citation chunk) {
            this(id, report, chunk, null);
        }
    }

    public record Message(
            UUID id,
            UUID attemptId,
            String role,
            String text,
            Instant createdAt,
            List<Citation> citations,
            boolean documentsDerived) {
        public Message {
            citations = List.copyOf(citations);
        }
    }

    public record Attempt(
            UUID id,
            String state,
            String message,
            Instant createdAt,
            Instant finishedAt,
            Recipient recipient,
            String responseModel,
            TokenUsage usage,
            String payloadSha256) {}

    public record Budget(int maxInputCharacters, int recentMessages, int summaryCharacters) {
        public Budget {
            if (maxInputCharacters < 8000
                    || maxInputCharacters > 64000
                    || recentMessages < 2
                    || recentMessages > 12
                    || summaryCharacters < 0
                    || summaryCharacters > 6000
                    || summaryCharacters >= maxInputCharacters)
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST, "上下文预算超出允许范围。");
        }

        public static Budget defaults() {
            return new Budget(48000, 8, 3000);
        }
    }

    public record Excerpt(
            UUID messageId,
            int ordinal,
            String role,
            String attribution,
            int startOffset,
            int endOffset,
            int originalCharacters,
            String text,
            List<Citation> citations,
            boolean documentsDerived) {}

    public record HistorySummary(
            String version,
            String provenance,
            List<Excerpt> excerpts,
            int totalMessages,
            int recentMessages,
            int excerptedMessages,
            int omittedMessages,
            int clippedCharacters,
            List<Integer> recentOrdinals,
            List<Integer> omittedOrdinals) {}

    public record Conversation(
            UUID id,
            long revision,
            UUID activeAttempt,
            Instant createdAt,
            Instant updatedAt,
            Material original,
            List<Message> messages,
            List<Attempt> attempts,
            Budget budget) {
        public Conversation(
                UUID id,
                long revision,
                UUID activeAttempt,
                Instant createdAt,
                Instant updatedAt,
                Material original,
                List<Message> messages,
                List<Attempt> attempts) {
            this(
                    id,
                    revision,
                    activeAttempt,
                    createdAt,
                    updatedAt,
                    original,
                    messages,
                    attempts,
                    Budget.defaults());
        }
    }

    public record Detail(
            UUID id,
            long revision,
            UUID activeAttempt,
            Instant createdAt,
            Instant updatedAt,
            Material original,
            List<Message> messages,
            List<Attempt> attempts,
            Recipient model,
            String notice,
            Budget budget,
            HistorySummary summary) {
        public Detail(
                UUID id,
                long revision,
                UUID activeAttempt,
                Instant createdAt,
                Instant updatedAt,
                Material original,
                List<Message> messages,
                List<Attempt> attempts,
                Recipient model,
                String notice) {
            this(
                    id,
                    revision,
                    activeAttempt,
                    createdAt,
                    updatedAt,
                    original,
                    messages,
                    attempts,
                    model,
                    notice,
                    Budget.defaults(),
                    null);
        }
    }

    public record Summary(
            UUID id,
            long revision,
            String title,
            String type,
            Instant updatedAt,
            UUID activeAttempt) {}

    public record Context(
            UUID conversationId,
            long revision,
            String question,
            List<Message> history,
            List<Citation> citations,
            List<ChunkSelection> selectedChunks,
            List<MemorySelection> selectedMemories,
            boolean documentsRequired,
            boolean memoriesRequired,
            List<String> omissions,
            String text,
            String fingerprint,
            Budget budget,
            HistorySummary summary,
            int serializedCharacters) {
        public Context(
                UUID conversationId,
                long revision,
                String question,
                List<Message> history,
                List<Citation> citations,
                List<ChunkSelection> selectedChunks,
                boolean documentsRequired,
                List<String> omissions,
                String text,
                String fingerprint) {
            this(
                    conversationId,
                    revision,
                    question,
                    history,
                    citations,
                    selectedChunks,
                    List.of(),
                    documentsRequired,
                    false,
                    omissions,
                    text,
                    fingerprint,
                    Budget.defaults(),
                    null,
                    text.length());
        }
    }

    public record Prepared(Context context, Preview preview) {}

    public record Response(
            String answer, List<String> evidenceIds, String responseModel, TokenUsage usage) {}
}
