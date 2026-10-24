package io.github.mochiuaena.triage.memory;

import static io.github.mochiuaena.triage.memory.MemoryModels.*;
import static io.github.mochiuaena.triage.memory.MemoryStore.bad;

import static org.springframework.http.HttpStatus.*;

import io.github.mochiuaena.triage.conversation.ConversationStore;
import io.github.mochiuaena.triage.development.GithubRepositoryStore;
import io.github.mochiuaena.triage.knowledge.*;
import io.github.mochiuaena.triage.material.*;
import io.github.mochiuaena.triage.source.SourceProjectRepository;

import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@Service
public class MemoryService {
    private final MemoryStore store;
    private final MaterialService materials;
    private final KnowledgeSearchService knowledge;
    private final SourceProjectRepository projects;
    private final GithubRepositoryStore repositories;
    private final ConversationStore conversations;

    public MemoryService(
            MemoryStore store,
            MaterialService materials,
            KnowledgeSearchService knowledge,
            SourceProjectRepository projects,
            GithubRepositoryStore repositories,
            ConversationStore conversations) {
        this.store = store;
        this.materials = materials;
        this.knowledge = knowledge;
        this.projects = projects;
        this.repositories = repositories;
        this.conversations = conversations;
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
