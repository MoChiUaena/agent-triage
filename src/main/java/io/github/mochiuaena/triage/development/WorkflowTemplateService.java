package io.github.mochiuaena.triage.development;

import io.github.mochiuaena.triage.development.WorkflowModels.Spec;
import io.github.mochiuaena.triage.development.WorkflowModels.Template;

import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;

/**
 * First-round configurable workflow templates: a form-driven task list over the fixed read-only task
 * catalog. The server validates task ids, duplicate entries, missing references, dependency cycles,
 * list-order consistency, hard material requirements (CI consumes the PR head, RECHECK follows CHECK,
 * SUMMARY stays terminal) and per-repository budget. Execution always runs the frozen task snapshot
 * inside the plan, so template edits never affect in-flight workflows.
 */
@Service
public class WorkflowTemplateService {
    /** id, title, hard requires, description; "after" defaults are derived from the selected set. */
    private record Entry(String id, String title, List<String> requires, String description) {}

    private static final Map<String, Entry> CATALOG = buildCatalog();
    private static final int MAX_TEMPLATES = 10;
    private static final int NAME_LIMIT = 80;
    private final GithubRepositoryService repositories;
    private final WorkflowTemplateStore store;

    public WorkflowTemplateService(GithubRepositoryService repositories, WorkflowTemplateStore store) {
        this.repositories = repositories;
        this.store = store;
    }

    public List<Map<String, Object>> catalog() {
        var values = new ArrayList<Map<String, Object>>();
        for (Entry entry : CATALOG.values())
            values.add(new LinkedHashMap<>(Map.of("id", entry.id(), "title", entry.title(),
                "requires", entry.requires(), "description", entry.description())));
        return List.copyOf(values);
    }

    public Template create(UUID repositoryId, String name, List<TaskInput> tasks) {
        var snapshot = repositories.snapshot(repositoryId);
        var specs = resolve(name, tasks);
        if (store.count(repositoryId) >= MAX_TEMPLATES)
            throw new ResponseStatusException(CONFLICT, "每个仓库最多保存 " + MAX_TEMPLATES + " 个模板，请先删除不再使用的模板。");
        Instant now = Instant.now();
        var template = new Template(UUID.randomUUID(), repositoryId, snapshot.registration().owner() + "/"
            + snapshot.registration().name(), name, specs, 1, now, now);
        store.insert(template);
        return template;
    }

    public Template update(UUID id, int revision, String name, List<TaskInput> tasks) {
        Template current = store.require(id);
        if (current.revision() != revision)
            throw new ResponseStatusException(CONFLICT, "模板已被其他修改更新，请刷新后重试。");
        String nextName = name == null ? current.name() : name;
        List<Spec> specs = tasks == null ? current.tasks() : resolve(nextName, tasks);
        var next = new Template(current.id(), current.repositoryId(), current.repository(), nextName, specs,
            current.revision() + 1, current.createdAt(), Instant.now());
        store.update(next);
        return next;
    }

    public void delete(UUID id) { store.require(id); store.delete(id); }

    public List<Template> list(UUID repositoryId) { repositories.snapshot(repositoryId); return store.list(repositoryId); }

    public Template get(UUID id) { return store.require(id); }

    /** Revalidated at plan time so a template that no longer satisfies the rules cannot start a workflow. */
    public Template requireValid(UUID repositoryId, UUID templateId) {
        repositories.snapshot(repositoryId);
        Template template = store.require(templateId);
        if (!template.repositoryId().equals(repositoryId))
            throw new ResponseStatusException(BAD_REQUEST, "模板不属于当前仓库。");
        resolve(template.name(), template.tasks().stream().map(spec -> new TaskInput(spec.id(), spec.requires(), spec.after())).toList());
        return template;
    }

    public record TaskInput(String id, List<String> requires, List<String> after) {}

    /** Fills catalog defaults for omitted dependencies, then applies every validation rule. */
    private List<Spec> resolve(String name, List<TaskInput> tasks) {
        if (name == null || name.isBlank() || name.strip().length() > NAME_LIMIT)
            throw new ResponseStatusException(BAD_REQUEST, "模板名称不能为空且最长 " + NAME_LIMIT + " 字符。");
        if (tasks == null || tasks.size() < 2 || tasks.size() > CATALOG.size())
            throw new ResponseStatusException(BAD_REQUEST, "模板需要 2 至 " + CATALOG.size() + " 个任务。");
        var present = new LinkedHashMap<String, TaskInput>();
        for (TaskInput task : tasks) {
            if (task == null || !CATALOG.containsKey(task.id()))
                throw new ResponseStatusException(BAD_REQUEST, "包含未知任务类型：" + (task == null ? "（空）" : task.id()) + "。");
            if (present.putIfAbsent(task.id(), task) != null)
                throw new ResponseStatusException(BAD_REQUEST, "任务重复：" + task.id() + "。");
        }
        if (!present.containsKey("SUMMARY")) throw new ResponseStatusException(BAD_REQUEST, "模板必须包含 SUMMARY（综合报告）任务。");
        if (!tasks.get(tasks.size() - 1).id().equals("SUMMARY"))
            throw new ResponseStatusException(BAD_REQUEST, "SUMMARY（综合报告）必须是模板的最后一个任务。");
        if (present.containsKey("RECHECK") && !present.containsKey("CHECK"))
            throw new ResponseStatusException(BAD_REQUEST, "RECHECK（有界补查）需要 CHECK（核对证据）任务。");
        if (present.containsKey("PR") && !present.containsKey("REPOSITORY"))
            throw new ResponseStatusException(BAD_REQUEST, "PR 任务需要 REPOSITORY（读取仓库概览）排在它之前。");
        if (present.containsKey("ISSUE") && !present.containsKey("REPOSITORY"))
            throw new ResponseStatusException(BAD_REQUEST, "ISSUE 任务需要 REPOSITORY（读取仓库概览）排在它之前。");
        if (present.containsKey("CI") && !present.containsKey("PR"))
            throw new ResponseStatusException(BAD_REQUEST, "CI 任务需要 PR（同一提交的材料来源）排在它之前。");
        var selected = present.keySet();
        var resolved = new ArrayList<Spec>();
        for (TaskInput task : tasks) {
            Entry entry = CATALOG.get(task.id());
            for (String reference : task.requires() == null ? List.<String>of() : task.requires())
                checkReference(task.id(), reference, present, "前置");
            for (String reference : task.after() == null ? List.<String>of() : task.after())
                checkReference(task.id(), reference, present, "顺序");
            var requires = new ArrayList<>(task.requires() == null ? entry.requires() : task.requires());
            for (String value : entry.requires()) if (!requires.contains(value)) requires.add(value);
            var after = new ArrayList<>(task.after() == null ? defaultAfter(task.id(), selected) : task.after());
            for (String value : defaultAfter(task.id(), selected)) if (!after.contains(value)) after.add(value);
            resolved.add(new Spec(task.id(), entry.title(), List.copyOf(requires), List.copyOf(after)));
        }
        var order = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < resolved.size(); i++) order.put(resolved.get(i).id(), i);
        for (Spec spec : resolved) {
            for (String reference : spec.requires()) checkOrder(spec, reference, order);
            for (String reference : spec.after()) checkOrder(spec, reference, order);
        }
        return List.copyOf(resolved);
    }

    private static void checkOrder(Spec spec, String reference, Map<String, Integer> order) {
        if (reference.equals(spec.id())) throw new ResponseStatusException(BAD_REQUEST, "任务不能依赖自身：" + spec.id() + "。");
        if (order.get(reference) >= order.get(spec.id()))
            throw new ResponseStatusException(BAD_REQUEST, "任务顺序与依赖不一致：" + spec.id() + " 依赖的 " + reference + " 必须排在它之前。");
    }

    private static void checkReference(String id, String reference, Map<String, TaskInput> present, String kind) {
        if (!present.containsKey(reference))
            throw new ResponseStatusException(BAD_REQUEST, kind + "引用了模板中不存在的任务：" + reference + "。");
    }

    private static List<String> defaultAfter(String id, java.util.Collection<String> selected) {
        return switch (id) {
            case "RECHECK" -> List.of("CHECK");
            case "CHECK" -> selected.stream().filter(value -> List.of("REPOSITORY", "PR", "ISSUE", "CI").contains(value)).toList();
            case "SUMMARY" -> selected.stream().filter(value -> !value.equals("SUMMARY")).toList();
            default -> List.of();
        };
    }

    private static Map<String, Entry> buildCatalog() {
        var values = new LinkedHashMap<String, Entry>();
        values.put("REPOSITORY", new Entry("REPOSITORY", "读取仓库概览", List.of(), "读取 Issue、PR 与默认分支 CI 概览抽样并标注缺口。"));
        values.put("PR", new Entry("PR", "审查固定 PR 版本", List.of("REPOSITORY"), "按计划冻结的 base/head 生成只读 PR 规则审查报告。"));
        values.put("ISSUE", new Entry("ISSUE", "Issue 分诊", List.of("REPOSITORY"), "对所选或 PR 引用的 Issue 生成本机规则分诊；没有材料时跳过。"));
        values.put("CI", new Entry("CI", "排查同一提交的 CI", List.of("PR"), "读取与 PR head 同一提交的失败作业并生成规则排障报告。"));
        values.put("CHECK", new Entry("CHECK", "核对证据与版本", List.of(), "核对 PR/Issue 元数据、CI 提交、来源与尝试次数。"));
        values.put("RECHECK", new Entry("RECHECK", "一次有界补查", List.of(), "对证据冲突做最多一次有界补查；不循环查询。"));
        values.put("SUMMARY", new Entry("SUMMARY", "生成综合报告", List.of(), "汇总所选任务结果、缺口与证据来源。"));
        return java.util.Collections.unmodifiableMap(values);
    }
}
