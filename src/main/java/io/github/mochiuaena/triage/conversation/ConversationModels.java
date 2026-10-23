package io.github.mochiuaena.triage.conversation;

import io.github.mochiuaena.triage.material.MaterialModels.*;
import io.github.mochiuaena.triage.knowledge.KnowledgeSearchService;
import io.github.mochiuaena.triage.domain.TriageModel.TokenUsage;
import java.time.Instant;
import java.util.*;

public final class ConversationModels {
 private ConversationModels() {}
 public record ChunkSelection(UUID versionId, UUID chunkId) {}
 public record Citation(String id, Material report, KnowledgeSearchService.Citation chunk) {}
 public record Message(UUID id,UUID attemptId,String role,String text,Instant createdAt,List<Citation> citations,boolean documentsDerived) {
  public Message {citations=List.copyOf(citations);}
 }
 public record Attempt(UUID id,String state,String message,Instant createdAt,Instant finishedAt,
   Recipient recipient,String responseModel,TokenUsage usage,String payloadSha256) {}
 public record Conversation(UUID id,long revision,UUID activeAttempt,Instant createdAt,Instant updatedAt,
   Material original,List<Message> messages,List<Attempt> attempts) {}
 public record Detail(UUID id,long revision,UUID activeAttempt,Instant createdAt,Instant updatedAt,
   Material original,List<Message> messages,List<Attempt> attempts,Recipient model,String notice) {}
 public record Summary(UUID id,long revision,String title,String type,Instant updatedAt,UUID activeAttempt) {}
 public record Context(UUID conversationId,long revision,String question,List<Message> history,List<Citation> citations,
   List<ChunkSelection> selectedChunks,boolean documentsRequired,List<String> omissions,String text,String fingerprint) {}
 public record Prepared(Context context,Preview preview) {}
 public record Response(String answer,List<String> evidenceIds,String responseModel,TokenUsage usage) {}
}
