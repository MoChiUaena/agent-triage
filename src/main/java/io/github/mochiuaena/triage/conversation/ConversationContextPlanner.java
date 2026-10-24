package io.github.mochiuaena.triage.conversation;

import static io.github.mochiuaena.triage.conversation.ConversationModels.*;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mochiuaena.triage.material.MaterialService;

import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/** Pure local extraction. No model, source lookup, admission, or mutation. Offsets are UTF-16. */
public final class ConversationContextPlanner {
    private final ObjectMapper json;

    public ConversationContextPlanner(ObjectMapper json) {
        this.json = json;
    }

    private static final String PROVENANCE =
            "LOCAL_EXTRACTIVE: verbatim historical excerpts, attributed user statements / prior"
                + " assistant answers; not verified facts, memory, or new observations. Excluded"
                + " text was not read by the model.";

    public HistorySummary summary(Conversation c) {
        int recent = Math.min(c.budget().recentMessages(), c.messages().size());
        return summarize(c, recent, c.budget().summaryCharacters());
    }

    private HistorySummary summarize(Conversation c, int recent, int allowance) {
        var excerpts = new ArrayList<Excerpt>();
        int older = c.messages().size() - recent;
        for (int i = older - 1; i >= 0 && allowance > 0; i--) {
            var m = c.messages().get(i);
            int length = Math.min(m.text().length(), allowance);
            // Never split a surrogate pair; exact source ranges remain reversible.
            if (length > 0
                    && length < m.text().length()
                    && Character.isHighSurrogate(m.text().charAt(length - 1))) length--;
            if (length == 0) continue;
            excerpts.addFirst(
                    new Excerpt(
                            m.id(),
                            i + 1,
                            m.role(),
                            "USER".equals(m.role())
                                    ? "Quoted user statement (unverified)"
                                    : "Quoted prior assistant answer (unverified)",
                            0,
                            length,
                            m.text().length(),
                            m.text().substring(0, length),
                            m.citations(),
                            m.documentsDerived()));
            allowance -= length;
        }
        var excerpted = new HashSet<Integer>();
        excerpts.forEach(e -> excerpted.add(e.ordinal()));
        var omitted = new ArrayList<Integer>();
        var recentOrdinals = new ArrayList<Integer>();
        int clipped = 0;
        for (int i = 0; i < c.messages().size(); i++) {
            if (i >= older) recentOrdinals.add(i + 1);
            else if (!excerpted.contains(i + 1)) {
                omitted.add(i + 1);
                clipped += c.messages().get(i).text().length();
            }
        }
        for (var e : excerpts) clipped += e.originalCharacters() - e.text().length();
        String version =
                MaterialService.sha(encode(List.of(c.budget(), excerpts, recentOrdinals, omitted)));
        return new HistorySummary(
                version,
                PROVENANCE,
                List.copyOf(excerpts),
                c.messages().size(),
                recent,
                excerpts.size(),
                omitted.size(),
                clipped,
                List.copyOf(recentOrdinals),
                List.copyOf(omitted));
    }

    public Context plan(
            Conversation c,
            String question,
            List<Citation> selectedCitations,
            List<ChunkSelection> selections,
            int outputLimit) {
        return plan(c, question, selectedCitations, selections, List.of(), List.of(), outputLimit);
    }

    public Context plan(
            Conversation c,
            String question,
            List<Citation> selectedCitations,
            List<ChunkSelection> selections,
            List<Citation> selectedMemories,
            List<MemorySelection> memorySelections,
            int outputLimit) {
        int recent = Math.min(c.budget().recentMessages(), c.messages().size());
        int summaryLimit = c.budget().summaryCharacters();
        while (true) {
            var summary = summarize(c, recent, summaryLimit);
            var history =
                    List.copyOf(
                            c.messages()
                                    .subList(c.messages().size() - recent, c.messages().size()));
            var citations = new LinkedHashMap<String, Citation>();
            var report =
                    new Citation(
                            "report:" + c.original().reference().reportId(), c.original(), null);
            citations.put(report.id(), report);
            selectedCitations.forEach(x -> citations.putIfAbsent(x.id(), x));
            selectedMemories.forEach(x -> citations.putIfAbsent(x.id(), x));
            history.forEach(m -> m.citations().forEach(x -> citations.putIfAbsent(x.id(), x)));
            summary.excerpts()
                    .forEach(e -> e.citations().forEach(x -> citations.putIfAbsent(x.id(), x)));
            boolean documents =
                    !selectedCitations.isEmpty()
                            || history.stream().anyMatch(Message::documentsDerived)
                            || summary.excerpts().stream().anyMatch(Excerpt::documentsDerived)
                            || citations.values().stream().anyMatch(x -> x.chunk() != null);
            boolean memories =
                    !selectedMemories.isEmpty()
                            || citations.values().stream().anyMatch(x -> x.memory() != null);
            var omissions = new ArrayList<String>(c.original().omissions());
            omissions.add(
                    "本机逐字历史摘录，并非已验证事实或新观测；完整近期 "
                            + recent
                            + " 条，摘录 "
                            + summary.excerptedMessages()
                            + " 条，省略 "
                            + summary.omittedMessages()
                            + " 条；未纳入字符 "
                            + summary.clippedCharacters()
                            + "。消息序号/摘录偏移见 summary，原记录保留。summaryCharacters=0 禁用较旧摘录。");
            if (documents) omissions.add("纳入的资料、历史或摘要派生自项目资料，须独立确认资料外发；未选/已省略资料不发送。");
            if (memories)
                omissions.add(
                        "纳入的已确认项目记忆是用户审阅的历史经验，未经独立验证，不代表当前运行观测或 GitHub 状态，运行观测与当前状态优先；发送须单独确认 memoriesConfirmed，未选择或已省略记忆不发送。");
            omissions.add("不包含新观测、源码读取或工具调用；仅发送列出的准确引用。预算计算最终序列化系统/用户正文，非模型窗口或实际 token 用量保证。");
            var payload = new LinkedHashMap<String, Object>();
            payload.put("conversationId", c.id());
            payload.put("revision", c.revision());
            payload.put("budget", c.budget());
            payload.put("question", question);
            payload.put(
                    "history",
                    history.stream()
                            .map(
                                    m ->
                                            Map.of(
                                                    "id",
                                                    m.id(),
                                                    "role",
                                                    m.role(),
                                                    "text",
                                                    m.text(),
                                                    "evidenceIds",
                                                    m.citations().stream()
                                                            .map(Citation::id)
                                                            .toList(),
                                                    "documentsDerived",
                                                    m.documentsDerived()))
                            .toList());
            payload.put("summary", wireSummary(summary));
            payload.put("citations", List.copyOf(citations.values()));
            payload.put("omissions", omissions);
            String text =
                    encode(
                            Map.of(
                                    "messages",
                                    List.of(
                                            Map.of(
                                                    "role",
                                                    "system",
                                                    "content",
                                                    ConversationModelResponder.SYSTEM),
                                            Map.of("role", "user", "content", encode(payload))),
                                    "maxOutputTokens",
                                    outputLimit,
                                    "toolChoice",
                                    "none"));
            if (text.length() <= c.budget().maxInputCharacters())
                return new Context(
                        c.id(),
                        c.revision(),
                        question,
                        history,
                        List.copyOf(citations.values()),
                        selections,
                        memorySelections,
                        documents,
                        memories,
                        List.copyOf(omissions),
                        text,
                        MaterialService.sha(text),
                        c.budget(),
                        summary,
                        text.length());
            // Prefer recent full messages, then fit the optional older excerpts; mandatory input
            // never changes.
            if (summaryLimit > 0) {
                summaryLimit = Math.max(0, summaryLimit - 256);
                continue;
            }
            if (recent > 0) {
                recent--;
                continue;
            }
            throw new ResponseStatusException(
                    BAD_REQUEST, "必要的原报告、当前问题及新选分段超过上下文预算；请提高预算或减少新选分段，未静默替换必要材料。");
        }
    }

    private Map<String, Object> wireSummary(HistorySummary summary) {
        var wire =
                json.convertValue(
                        summary,
                        new com.fasterxml.jackson.core.type.TypeReference<
                                LinkedHashMap<String, Object>>() {});
        wire.put(
                "excerpts",
                summary.excerpts().stream()
                        .map(
                                e -> {
                                    var excerpt = new LinkedHashMap<String, Object>();
                                    excerpt.put("messageId", e.messageId());
                                    excerpt.put("ordinal", e.ordinal());
                                    excerpt.put("role", e.role());
                                    excerpt.put("attribution", e.attribution());
                                    excerpt.put("startOffset", e.startOffset());
                                    excerpt.put("endOffset", e.endOffset());
                                    excerpt.put("originalCharacters", e.originalCharacters());
                                    excerpt.put("text", e.text());
                                    excerpt.put(
                                            "evidenceIds",
                                            e.citations().stream().map(Citation::id).toList());
                                    excerpt.put("documentsDerived", e.documentsDerived());
                                    return excerpt;
                                })
                        .toList());
        return wire;
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot encode local context", e);
        }
    }
}
