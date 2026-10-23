package io.github.mochiuaena.triage.conversation;

import static io.github.mochiuaena.triage.conversation.ConversationModels.*;

import static org.springframework.http.HttpStatus.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mochiuaena.triage.knowledge.*;
import io.github.mochiuaena.triage.material.*;

import jakarta.annotation.PreDestroy;

import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@Service
public class ConversationService {
    private final ConversationStore store;
    private final MaterialService materials;
    private final SendPreviewService previews;
    private final KnowledgeService knowledge;
    private final KnowledgeSearchService search;
    private final KnowledgeRepository knowledgeStore;
    private final ConversationModelResponder responder;
    private final ObjectMapper json;
    private final Map<UUID, ScheduledFuture<?>> deadlines = new ConcurrentHashMap<>();
    private final Map<UUID, Prepared> prepared = new ConcurrentHashMap<>();
    private final Map<UUID, Future<?>> pending = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor workers =
            new ThreadPoolExecutor(
                    2,
                    2,
                    0,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(8),
                    Thread.ofPlatform().daemon(true).name("conversation-", 0).factory(),
                    new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledThreadPoolExecutor timer =
            new ScheduledThreadPoolExecutor(
                    1, Thread.ofPlatform().daemon(true).name("conversation-deadline").factory());

    public ConversationService(
            ConversationStore store,
            MaterialService materials,
            SendPreviewService previews,
            KnowledgeService knowledge,
            KnowledgeSearchService search,
            KnowledgeRepository knowledgeStore,
            ConversationModelResponder responder,
            ObjectMapper json) {
        this.store = store;
        this.materials = materials;
        this.previews = previews;
        this.knowledge = knowledge;
        this.search = search;
        this.knowledgeStore = knowledgeStore;
        this.responder = responder;
        this.json = json;
        timer.setRemoveOnCancelPolicy(true);
    }

    public Detail create(MaterialModels.Selection origin) {
        if (origin == null || origin.type() == MaterialModels.Type.KNOWLEDGE)
            throw new ResponseStatusException(BAD_REQUEST, "请从服务、PR、Issue、CI 或工作流报告开始追问。");
        return detail(store.create(materials.resolve(origin)).id());
    }

    public List<Summary> list() {
        return store.list();
    }

    public Detail detail(UUID id) {
        var c = store.require(id);
        return new Detail(
                c.id(),
                c.revision(),
                c.activeAttempt(),
                c.createdAt(),
                c.updatedAt(),
                c.original(),
                c.messages(),
                c.attempts(),
                previews.recipient(),
                "原报告为创建对话时的不可变快照；追问不会获取新观测或更新报告。"
                        + (c.original().reference().scope().projectId() == null
                                        && c.original().reference().type()
                                                == MaterialModels.Type.SERVICE
                                ? "此旧服务报告未保存项目身份，仅支持服务材料，不能附加当前项目文档。"
                                : ""));
    }

    public Detail reference(UUID id, long revision, String question) {
        var c = store.require(id);
        check(c, revision);
        question = question(question);
        UUID attempt = UUID.randomUUID();
        Instant now = Instant.now();
        store.admit(
                id,
                revision,
                new Message(UUID.randomUUID(), attempt, "USER", question, now, List.of(), false),
                new Attempt(
                        attempt,
                        "REFERENCE_ONLY",
                        "未调用模型；问题已保存，请查看原报告证据、规则说明及本机资料检索。",
                        now,
                        now,
                        null,
                        null,
                        null,
                        null),
                false);
        return detail(id);
    }

    public Prepared preview(
            UUID id, long revision, String question, List<ChunkSelection> selected) {
        var c = store.require(id);
        check(c, revision);
        Context context = context(c, question, selected);
        prepared.values().removeIf(p -> Instant.now().isAfter(p.preview().expiresAt()));
        var preview =
                previews.createBound(
                        context.fingerprint(),
                        List.of(c.original()),
                        context.text(),
                        context.omissions(),
                        context.documentsRequired());
        var result = new Prepared(context, preview);
        prepared.put(preview.id(), result);
        return result;
    }

    public synchronized Detail send(
            UUID id, UUID previewId, boolean confirmed, boolean documentsConfirmed) {
        Prepared p = prepared.get(previewId);
        if (p == null) throw new ResponseStatusException(GONE, "预览已过期、已消费或应用已重启。");
        if (!p.context().conversationId().equals(id)) throw ConversationStore.conflict();
        var c = store.require(id);
        check(c, p.context().revision());
        Context fresh = context(c, p.context().question(), p.context().selectedChunks());
        if (!fresh.fingerprint().equals(p.context().fingerprint())
                || !fresh.text().equals(p.preview().text())) throw ConversationStore.conflict();
        if (workers.getActiveCount() >= 2 && workers.getQueue().remainingCapacity() == 0)
            throw new ResponseStatusException(TOO_MANY_REQUESTS, "追问队列已满，请稍后重新预览。");
        previews.consumeBound(
                previewId, fresh.fingerprint(), fresh.text(), confirmed, documentsConfirmed);
        prepared.remove(previewId);
        UUID attemptId = UUID.randomUUID();
        Instant now = Instant.now();
        var a =
                new Attempt(
                        attemptId,
                        "RUNNING",
                        "生成中；仅发送已确认的预览正文。",
                        now,
                        null,
                        p.preview().recipient(),
                        null,
                        null,
                        p.preview().contentSha256());
        store.admit(
                id,
                c.revision(),
                new Message(
                        UUID.randomUUID(),
                        attemptId,
                        "USER",
                        p.context().question(),
                        now,
                        p.context().citations(),
                        p.context().documentsRequired()),
                a,
                true);
        FutureTask<Void> task =
                new FutureTask<>(
                        () -> {
                            run(id, a, p);
                            return null;
                        });
        pending.put(attemptId, task);
        deadlines.put(
                attemptId,
                timer.schedule(
                        () -> {
                            if (store.finish(
                                    id,
                                    terminal(a, "TIMED_OUT", "等待超过 30 秒，已停止发布回答；用量未知，可能已计费。", null),
                                    null)) {
                                task.cancel(true);
                                pending.remove(attemptId);
                            }
                            deadlines.remove(attemptId);
                        },
                        30,
                        TimeUnit.SECONDS));
        try {
            workers.execute(task);
        } catch (RejectedExecutionException e) {
            pending.remove(attemptId);
            clearDeadline(attemptId);
            store.finish(id, terminal(a, "BUSY", "队列已满，本次未调用模型。", null), null);
            return detail(id);
        }
        return detail(id);
    }

    private void run(UUID id, Attempt a, Prepared p) {
        try {
            if (!store.active(id, a.id())) return;
            Response answer =
                    responder.respond(
                            p,
                            () -> {
                                revalidate(p.context());
                                if (!store.active(id, a.id())
                                        || Thread.currentThread().isInterrupted())
                                    throw new CancellationException();
                            });
            var used =
                    p.context().citations().stream()
                            .filter(c -> answer.evidenceIds().contains(c.id()))
                            .toList();
            store.finish(
                    id,
                    terminal(a, "SUCCEEDED", "模型回答已通过格式与引用校验；需要新事实时请明确新建任务。", answer),
                    new Message(
                            UUID.randomUUID(),
                            a.id(),
                            "ASSISTANT",
                            answer.answer(),
                            Instant.now(),
                            used,
                            p.context().documentsRequired()));
        } catch (CancellationException e) {
            store.finish(id, terminal(a, "CANCELLED", "调用已取消；用量未知。", null), null);
        } catch (ConversationModelResponder.InvalidOutput e) {
            store.finish(
                    id,
                    terminal(
                            a,
                            "INVALID_OUTPUT",
                            "模型输出不完整或引用无效，已丢弃回答；原报告保留。用量仅按明确返回值记录。",
                            e.observation()),
                    null);
        } catch (IllegalArgumentException e) {
            store.finish(
                    id,
                    terminal(a, "INVALID_OUTPUT", "模型输出不完整或引用无效，已丢弃回答；原报告保留，用量未知。", null),
                    null);
        } catch (Exception e) {
            store.finish(
                    id, terminal(a, "FAILED", "调用失败或预览材料/接收方已变化；原报告保留。用量未知，不自动重试。", null), null);
        } finally {
            pending.remove(a.id());
            clearDeadline(a.id());
        }
    }

    public Detail cancel(UUID id, UUID attempt) {
        var c = store.require(id);
        var a =
                c.attempts().stream()
                        .filter(x -> x.id().equals(attempt))
                        .findFirst()
                        .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "调用不存在。"));
        if (store.finish(id, terminal(a, "CANCELLED", "用户取消；迟到回答不会发布，用量未知。", null), null)) {
            var f = pending.remove(attempt);
            if (f != null) f.cancel(true);
            clearDeadline(attempt);
            workers.purge();
        }
        return detail(id);
    }

    private void clearDeadline(UUID id) {
        var deadline = deadlines.remove(id);
        if (deadline != null) deadline.cancel(false);
    }

    private Attempt terminal(Attempt a, String state, String text, Response result) {
        return new Attempt(
                a.id(),
                state,
                text,
                a.createdAt(),
                Instant.now(),
                a.recipient(),
                result == null ? null : result.responseModel(),
                result == null ? null : result.usage(),
                a.payloadSha256());
    }

    private void check(Conversation c, long revision) {
        if (c.revision() != revision || c.activeAttempt() != null)
            throw ConversationStore.conflict();
    }

    private String question(String q) {
        if (q == null || q.isBlank() || q.length() > 4000)
            throw new ResponseStatusException(BAD_REQUEST, "问题须为 1—4000 字符。");
        return q;
    }

    private MaterialModels.Selection origin(Conversation c) {
        var r = c.original().reference();
        var s = r.scope();
        return new MaterialModels.Selection(
                r.type(), r.reportId(), s.projectId(), s.repositoryId(), s.service());
    }

    private void validateOriginal(Conversation c) {
        try {
            var fresh = materials.resolve(origin(c));
            var old = c.original();
            if (!fresh.reference().version().equals(old.reference().version())
                    || !fresh.text().equals(old.text())
                    || !fresh.reference().scope().equals(old.reference().scope()))
                throw ConversationStore.conflict();
        } catch (ResponseStatusException e) {
            throw new ResponseStatusException(CONFLICT, "原报告、登记或源码版本已变化/移除；旧快照可读，请明确新建任务后再发送。");
        }
    }

    private KnowledgeModels.Scope scope(Conversation c) {
        var s = c.original().reference().scope();
        if (s.projectId() == null && s.repositoryId() == null)
            throw new ResponseStatusException(CONFLICT, "原报告没有保存项目身份，不能附加项目资料。");
        return knowledge.scope(
                s.projectId() != null ? "PROJECT" : "REPOSITORY",
                s.projectId() != null ? s.projectId() : s.repositoryId());
    }

    private Citation chunk(Conversation c, ChunkSelection selection) {
        if (selection == null || selection.versionId() == null || selection.chunkId() == null)
            throw new ResponseStatusException(BAD_REQUEST, "请选择准确知识分段。");
        var s = scope(c);
        knowledge.validateSearchScope(s, c.original().reference().scope().service());
        knowledge.materialVersion(selection.versionId(), s);
        if (!knowledgeStore.activeVersion(selection.versionId()))
            throw new ResponseStatusException(CONFLICT, "知识版本不再有效，请重新检索。");
        var hit = search.chunk(s, selection.versionId(), selection.chunkId());
        return new Citation("chunk:" + hit.chunkId(), null, hit);
    }

    private Context context(Conversation c, String q, List<ChunkSelection> selected) {
        q = question(q);
        validateOriginal(c);
        selected = selected == null ? List.of() : List.copyOf(selected);
        if (selected.size() > 3 || new HashSet<>(selected).size() != selected.size())
            throw new ResponseStatusException(BAD_REQUEST, "最多选择 3 个不重复的知识分段。");
        var citations = new LinkedHashMap<String, Citation>();
        var original =
                new Citation("report:" + c.original().reference().reportId(), c.original(), null);
        citations.put(original.id(), original);
        var omissions = new ArrayList<String>(c.original().omissions());
        var history = new ArrayList<Message>();
        int count = 0;
        for (int i = c.messages().size() - 1; i >= 0; i--) {
            var m = c.messages().get(i);
            if (history.size() >= 12 || count + m.text().length() > 24000) break;
            history.addFirst(m);
            count += m.text().length();
        }
        if (history.size() < c.messages().size())
            omissions.add(
                    "仅发送最近 "
                            + history.size()
                            + " 条、最多 24000 字符历史；省略 "
                            + (c.messages().size() - history.size())
                            + " 条，原记录仍保存，无摘要。");
        boolean documents = history.stream().anyMatch(Message::documentsDerived);
        for (var m : history)
            for (var citation : m.citations()) {
                if (citation.chunk() != null) {
                    chunk(
                            c,
                            new ChunkSelection(
                                    citation.chunk().versionId(), citation.chunk().chunkId()));
                    documents = true;
                }
                citations.putIfAbsent(citation.id(), citation);
            }
        for (var selection : selected) {
            var citation = chunk(c, selection);
            citations.put(citation.id(), citation);
            documents = true;
        }
        if (documents) omissions.add("本次选段或纳入的历史问题/回答含项目资料；须独立确认资料外发，即使本次未新选分段。");
        omissions.add("不包含源码片段、完整日志/补丁，不获取新事实；知识仅发送列出的精确分段，其他分段未读取。最多 12 条历史，无自动摘要。");
        try {
            var payload = new LinkedHashMap<String, Object>();
            payload.put("conversationId", c.id());
            payload.put("revision", c.revision());
            payload.put("question", q);
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
            payload.put("citations", citations.values());
            payload.put("omissions", omissions);
            String text =
                    json.writeValueAsString(
                            Map.of(
                                    "messages",
                                    List.of(
                                            Map.of(
                                                    "role",
                                                    "system",
                                                    "content",
                                                    ConversationModelResponder.SYSTEM),
                                            Map.of(
                                                    "role",
                                                    "user",
                                                    "content",
                                                    json.writeValueAsString(payload))),
                                    "maxOutputTokens",
                                    responder.outputTokenLimit(),
                                    "toolChoice",
                                    "none"));
            if (text.length() > 64000)
                throw new ResponseStatusException(
                        BAD_REQUEST, "精确上下文超过 64000 字符，请减少分段或从报告新建对话；未静默省略资料。");
            return new Context(
                    c.id(),
                    c.revision(),
                    q,
                    List.copyOf(history),
                    List.copyOf(citations.values()),
                    selected,
                    documents,
                    List.copyOf(omissions),
                    text,
                    MaterialService.sha(text));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot encode conversation preview", e);
        }
    }

    private void revalidate(Context context) {
        var c = store.require(context.conversationId());
        validateOriginal(c);
        for (var citation : context.citations())
            if (citation.chunk() != null)
                chunk(
                        c,
                        new ChunkSelection(
                                citation.chunk().versionId(), citation.chunk().chunkId()));
    }

    @PreDestroy
    public void close() {
        workers.shutdownNow();
        timer.shutdownNow();
    }
}
