package io.github.mochiuaena.triage.memory;

import static io.github.mochiuaena.triage.memory.MemoryModels.*;
import static io.github.mochiuaena.triage.memory.MemoryStore.bad;

import static org.springframework.http.HttpStatus.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mochiuaena.triage.conversation.ConversationStore;
import io.github.mochiuaena.triage.development.GithubRepositoryStore;
import io.github.mochiuaena.triage.knowledge.*;
import io.github.mochiuaena.triage.material.*;
import io.github.mochiuaena.triage.source.SourceProjectRepository;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.regex.Pattern;

@Service
public class MemoryService {
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}_]+");
    private static final int SCAN_LIMIT = 100;
    private static final String COVERAGE =
            "仅召回此范围状态为 CONFIRMED 的当前版本；候选、已停用、已删除或确认后被编辑的记忆立即退出召回。"
                    + "匹配为本机连续关键词（大小写不敏感），问题中所有词都须命中，结果按命中分数和更新时间排序。"
                    + "召回内容是用户审阅的历史经验，未经独立验证，不代表当前运行观测或 GitHub 状态；实时状态以原报告和重新执行任务为准。"
                    + "适用性提示仅核对登记绑定与依据版本，不检测代码行为变化。";

    private final MemoryStore store;
    private final MaterialService materials;
    private final KnowledgeSearchService knowledge;
    private final KnowledgeRepository knowledgeStore;
    private final SourceProjectRepository projects;
    private final GithubRepositoryStore repositories;
    private final ConversationStore conversations;
    private final ObjectMapper json;

    public MemoryService(
            MemoryStore store,
            MaterialService materials,
            KnowledgeSearchService knowledge,
            KnowledgeRepository knowledgeStore,
            SourceProjectRepository projects,
            GithubRepositoryStore repositories,
            ConversationStore conversations,
            ObjectMapper json) {
        this.store = store;
        this.materials = materials;
        this.knowledge = knowledge;
        this.knowledgeStore = knowledgeStore;
        this.projects = projects;
        this.repositories = repositories;
        this.conversations = conversations;
        this.json = json;
    }

    public List<MaterialModels.ScopeOption> scopes() {
        return materials.scopes().stream().filter(s -> !s.kind().equals("SERVICE")).toList();
    }

    public Scope scope(String kind, UUID id, String service, boolean live) {
        if (id == null || !Set.of("PROJECT", "REPOSITORY").contains(Objects.toString(kind, "")))
            throw bad("请选择准确的已登记项目或仓库 UUID；不支持仅服务范围。");
        if (!live) return new Scope(kind, id, service);
        String bound = null;
        if (kind.equals("PROJECT")) bound = projects.require(id).service();
        else if (repositories.list().stream().noneMatch(r -> r.id().equals(id)))
            throw new ResponseStatusException(NOT_FOUND, "登记的仓库不存在。");
        if (service != null && !Objects.equals(service, bound))
            throw bad("服务必须是所选项目的服务器绑定；仓库不推断服务。");
        return new Scope(kind, id, bound);
    }

    public Version create(
            String kind,
            UUID id,
            String service,
            String topic,
            String content,
            String note,
            List<Reference> references) {
        Scope scope = scope(kind, id, service, true);
        return store.create(scope, new Body(topic, content, basis(scope, note, references, null)));
    }

    public Version edit(
            UUID id,
            String kind,
            UUID sid,
            String service,
            long revision,
            String topic,
            String content,
            String note,
            List<Reference> references) {
        Scope scope = scope(kind, sid, service, true);
        var old = store.current(id, new Scope(kind, sid, null));
        assertBinding(old.scope(), scope);
        // Explicit retained references may be reviewed as immutable historical evidence. New
        // references
        // must be resolved again. Conversation provenance is always retained and never client
        // supplied.
        var b = basis(scope, note, references, old.body().basis());
        return store.edit(id, new Scope(kind, sid, null), revision, new Body(topic, content, b));
    }

    private Basis basis(Scope scope, String note, List<Reference> references, Basis previous) {
        if (note == null) note = "";
        if (note.length() > 2000 || (references != null && references.size() > 3))
            throw bad("用户陈述或引用超出上限。");
        List<Snapshot> snapshots =
                references == null && previous != null
                        ? previous.references()
                        : resolve(scope, references == null ? List.of() : references);
        return new Basis(
                note,
                snapshots,
                previous == null ? null : previous.conversationId(),
                previous == null ? List.of() : previous.messageIds(),
                previous == null ? List.of() : previous.messages(),
                previous == null ? "用户陈述，未经独立验证；服务器保存的历史依据，不代表当前观察，不授权外发。" : previous.provenance(),
                previous == null ? null : previous.conversationOriginal());
    }

    private List<Snapshot> resolve(Scope scope, List<Reference> refs) {
        var result = new ArrayList<Snapshot>();
        for (var ref : refs) {
            if (ref == null || ref.type() == null) throw bad("请选择报告或准确知识分段引用。");
            if (ref.type().equals("CHUNK")) {
                var citation =
                        knowledge.chunk(
                                new KnowledgeModels.Scope(scope.kind(), scope.id()),
                                ref.versionId(),
                                ref.chunkId());
                result.add(new Snapshot(ref, citation));
            } else {
                MaterialModels.Type type;
                try {
                    type = MaterialModels.Type.valueOf(ref.type());
                } catch (IllegalArgumentException e) {
                    throw bad("引用类型无效。");
                }
                if (type == MaterialModels.Type.KNOWLEDGE) throw bad("知识依据必须选择准确 CHUNK 版本及分段。");
                var material =
                        materials.resolve(
                                new MaterialModels.Selection(
                                        type,
                                        ref.reportId(),
                                        scope.kind().equals("PROJECT") ? scope.id() : null,
                                        scope.kind().equals("REPOSITORY") ? scope.id() : null,
                                        null));
                var actual = material.reference().scope();
                UUID actualId =
                        scope.kind().equals("PROJECT") ? actual.projectId() : actual.repositoryId();
                if (!scope.id().equals(actualId))
                    throw new ResponseStatusException(CONFLICT, "报告不属于所选范围。");
                result.add(new Snapshot(ref, material));
            }
        }
        return List.copyOf(result);
    }

    public Version transition(
            UUID id,
            String kind,
            UUID sid,
            String service,
            long revision,
            String action,
            boolean confirmed,
            String hash) {
        Scope live = scope(kind, sid, null, true);
        Scope selected = new Scope(kind, sid, service);
        if (action.equals("CONFIRMED"))
            assertBinding(store.current(id, selected).scope(), live);
        return store.transition(id, selected, revision, action, confirmed, hash);
    }

    public List<Summary> list(String kind, UUID id, String service, String status, int limit) {
        scope(kind, id, service, true);
        return store.list(new Scope(kind, id, service), status, limit);
    }

    /** Bounded local recall of current CONFIRMED memories; never a grant to send or share. */
    public RecallResult recall(String kind, UUID id, String question, Integer limit) {
        Scope live = scope(kind, id, null, true);
        if (question == null || question.isBlank() || question.length() > 4000)
            throw bad("问题须为 1—4000 字符。");
        var tokens =
                TOKEN.matcher(question.toLowerCase(Locale.ROOT)).results()
                        .map(m -> m.group())
                        .distinct()
                        .toList();
        if (tokens.isEmpty()) throw bad("请输入文字或数字关键词。");
        int max = limit == null ? 5 : limit;
        if (max < 1 || max > 10) throw bad("召回条数须为 1—10。");
        Scope selected = new Scope(live.kind(), live.id(), null);
        var scanned = store.confirmedScan(selected, SCAN_LIMIT);
        long available = store.confirmedCount(selected);
        var peers = new HashMap<String, List<MemoryStore.Scanned>>();
        for (var entry : scanned)
            peers.computeIfAbsent(normalized(entry.version().body().topic()), k -> new ArrayList<>())
                    .add(entry);
        var hits = new ArrayList<MemoryStore.Scanned>();
        for (var entry : scanned) if (score(entry.version().body(), tokens) > 0) hits.add(entry);
        hits.sort(
                Comparator.comparingInt((MemoryStore.Scanned e) -> score(e.version().body(), tokens))
                        .reversed()
                        .thenComparing(
                                e -> e.version().updatedAt(), Comparator.reverseOrder())
                        .thenComparing(MemoryStore.Scanned::id));
        var results = new ArrayList<RecallItem>();
        for (var hit : hits.subList(0, Math.min(max, hits.size()))) {
            var v = hit.version();
            var conflicts =
                    peers.get(normalized(v.body().topic())).stream()
                            .filter(p -> !p.id().equals(hit.id()))
                            .map(
                                    p ->
                                            new ConflictPeer(
                                                    p.id(),
                                                    p.revision(),
                                                    p.version().body().topic(),
                                                    p.version().confirmedAt()))
                            .sorted(Comparator.comparing(ConflictPeer::id))
                            .toList();
            results.add(
                    new RecallItem(
                            hit.id(),
                            hit.revision(),
                            v.body().topic(),
                            excerpt(v.body().content(), tokens),
                            score(v.body(), tokens),
                            v.confirmedAt(),
                            v.scope().service(),
                            applicability(live, v),
                            conflicts));
        }
        return new RecallResult(
                live,
                question,
                "KEYWORD_CONFIRMED",
                List.copyOf(results),
                scanned.size(),
                available,
                available > scanned.size(),
                COVERAGE);
    }

    /** Exact confirmed revision for model sends; registration drift stays visible via scope. */
    public Version recallable(UUID id, String kind, UUID sid, long revision) {
        scope(kind, sid, null, true);
        return store.recallable(id, new Scope(kind, sid, null), revision);
    }

    private List<String> applicability(Scope live, Version v) {
        var notes = new ArrayList<String>();
        if (live.kind().equals("PROJECT") && !Objects.equals(live.service(), v.scope().service()))
            notes.add(
                    "项目当前绑定服务为 "
                            + live.service()
                            + "，与记忆保存服务 "
                            + v.scope().service()
                            + " 不同；请先核对是否仍适用。");
        for (var snapshot : v.body().basis().references()) {
            var ref = snapshot.reference();
            if (ref == null || ref.type() == null) continue;
            if (ref.type().equals("CHUNK")) {
                try {
                    if (!knowledgeStore.activeVersion(ref.versionId()))
                        notes.add("依据的知识版本 " + ref.versionId() + " 已不再有效，请核对最新资料。");
                } catch (DataAccessException e) {
                    notes.add("依据的知识版本状态暂不可核对，请先人工确认。");
                }
            } else {
                MaterialModels.Type type;
                try {
                    type = MaterialModels.Type.valueOf(ref.type());
                } catch (IllegalArgumentException e) {
                    continue;
                }
                if (type == MaterialModels.Type.KNOWLEDGE) continue;
                String savedVersion = savedReportVersion(snapshot.saved());
                try {
                    var fresh =
                            materials.resolve(
                                    new MaterialModels.Selection(
                                            type,
                                            ref.reportId(),
                                            live.kind().equals("PROJECT") ? live.id() : null,
                                            live.kind().equals("REPOSITORY") ? live.id() : null,
                                            null));
                    if (savedVersion != null && !savedVersion.equals(fresh.reference().version()))
                        notes.add(
                                "依据的 "
                                        + type
                                        + " 报告版本已从 "
                                        + savedVersion
                                        + " 变为 "
                                        + fresh.reference().version()
                                        + "，请核对适用性。");
                } catch (ResponseStatusException e) {
                    notes.add(
                            "依据的 "
                                    + type
                                    + " 报告已移除或不在当前范围，历史快照仍可阅读。");
                }
            }
        }
        return notes;
    }

    private String savedReportVersion(Object saved) {
        if (saved == null) return null;
        try {
            return json.convertValue(saved, MaterialModels.Material.class).reference().version();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static int score(Body body, List<String> tokens) {
        String topic = body.topic().toLowerCase(Locale.ROOT);
        String content = body.content().toLowerCase(Locale.ROOT);
        int score = 0;
        for (String token : tokens) {
            int value = topic.contains(token) ? 8 : 0;
            int count = 0, from = 0, at;
            while (count < 3 && (at = content.indexOf(token, from)) >= 0) {
                count++;
                from = at + token.length();
            }
            value += count * 2;
            if (value == 0) return 0;
            score += value;
        }
        return score;
    }

    private static String excerpt(String content, List<String> tokens) {
        String folded = content.toLowerCase(Locale.ROOT);
        int foldedAt =
                tokens.stream().mapToInt(folded::indexOf).filter(i -> i >= 0).min().orElse(0);
        int at = 0, foldedPosition = 0;
        for (int offset = 0; offset < content.length();) {
            int width = Character.charCount(content.codePointAt(offset));
            int foldedWidth = content.substring(offset, offset + width).toLowerCase(Locale.ROOT).length();
            if (foldedPosition + foldedWidth > foldedAt) {
                at = offset;
                break;
            }
            foldedPosition += foldedWidth;
            offset += width;
        }
        int start = Math.max(0, at - 100), end = Math.min(content.length(), start + 360);
        if (start > 0 && start < content.length() && Character.isLowSurrogate(content.charAt(start)))
            start++;
        if (end > start && end < content.length() && Character.isHighSurrogate(content.charAt(end - 1)))
            end--;
        return content.substring(start, end);
    }

    private static String normalized(String topic) {
        return topic == null
                ? ""
                : topic.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    public Version current(UUID id, String kind, UUID sid, String service) {
        return store.current(id, scope(kind, sid, service, false));
    }

    public Version version(UUID id, String kind, UUID sid, String service, long revision) {
        return store.version(id, scope(kind, sid, service, false), revision);
    }

    public List<Summary> history(UUID id, String kind, UUID sid, String service) {
        return store.history(id, scope(kind, sid, service, false));
    }

    public Version candidate(UUID conversationId, List<UUID> selected) {
        if (conversationId == null
                || selected == null
                || selected.isEmpty()
                || selected.size() > 3
                || new HashSet<>(selected).size() != selected.size())
            throw bad("请选择 1–3 条已保存消息，不能重复。");
        var c = conversations.require(conversationId);
        var origin = c.original().reference().scope();
        if (origin.projectId() == null && origin.repositoryId() == null)
            throw bad("旧服务对话没有保存项目身份，不能猜测今日绑定项目；请从含项目或仓库身份的报告新建对话。");
        if (origin.projectId() != null && origin.repositoryId() != null) throw bad("对话原始范围无效。");
        Scope scope =
                scope(
                        origin.projectId() != null ? "PROJECT" : "REPOSITORY",
                        origin.projectId() != null ? origin.projectId() : origin.repositoryId(),
                        null,
                        true);
        assertBinding(new Scope(scope.kind(), scope.id(), origin.service()), scope);
        var messages =
                selected.stream()
                        .map(
                                mid ->
                                        c.messages().stream()
                                                .filter(m -> m.id().equals(mid))
                                                .findFirst()
                                                .orElseThrow(() -> bad("选择的消息不属于此对话。")))
                        .toList();
        String text = String.join("\n\n", messages.stream().map(m -> m.text()).toList());
        boolean clipped = text.length() > 4000;
        String provenance =
                "逐字提取已选择保存消息；对话 "
                        + c.id()
                        + "；消息 ID "
                        + selected
                        + "。用户说法/旧回答未经独立验证；原始报告及消息引用是历史快照，不是当前观察，不授权外发。"
                        + (clipped ? " 候选正文裁剪为至多前4000字符（保留完整字符边界）；完整选中消息保存在依据中。" : "");
        var basis =
                new Basis(
                        "",
                        List.of(),
                        c.id(),
                        List.copyOf(selected),
                        new ArrayList<>(messages),
                        provenance,
                        c.original());
        int end = Math.min(text.length(), 4000);
        if (end > 0
                && end < text.length()
                && Character.isHighSurrogate(text.charAt(end - 1))
                && Character.isLowSurrogate(text.charAt(end))) end--;
        return store.create(scope, new Body("对话经验候选", text.substring(0, end), basis));
    }

    private void assertBinding(Scope saved, Scope live) {
        if (!Objects.equals(saved.service(), live.service()))
            throw new ResponseStatusException(CONFLICT, "项目服务绑定已变化；旧经验仅可阅读或撤回，不能改绑、编辑或确认。");
    }
}
