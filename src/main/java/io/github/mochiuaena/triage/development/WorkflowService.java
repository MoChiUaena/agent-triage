package io.github.mochiuaena.triage.development;

import io.github.mochiuaena.triage.development.WorkflowModels.*;
import jakarta.annotation.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;
import static io.github.mochiuaena.triage.development.WorkflowModels.terminal;

@Service
public class WorkflowService {
    /** Transient upstream failures a user may verify and retry through a bounded resume. */
    private static final Set<Integer> RETRYABLE = Set.of(502, 503, 504);
    private static final int MAX_RESUMES = 2;
    private final WorkflowPlanner planner; private final WorkflowTasks runner; private final WorkflowStore store; private final GithubRepositoryStore repositories;
    private final PrReviewService prs; private final IssueTriageService issues;
    private final Duration timeout;
    private final Semaphore admission = new Semaphore(2);
    private final Map<UUID, Actor> active = new ConcurrentHashMap<>();
    private final ExecutorService workers = new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(2),Thread.ofPlatform().daemon(true).name("development-workflow-",0).factory());
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true).name("workflow-deadline-",0).factory());
    public WorkflowService(WorkflowPlanner planner, WorkflowTasks runner, WorkflowStore store, GithubRepositoryStore repositories,
                           PrReviewService prs, IssueTriageService issues,
                           @Value("${triage.development.workflow-timeout:180s}") Duration timeout) {
        this.planner=planner; this.runner=runner; this.store=store; this.repositories=repositories; this.prs=prs; this.issues=issues; this.timeout=timeout;
        if (timeout.toMillis() < 1 || timeout.compareTo(Duration.ofSeconds(180)) > 0) throw new IllegalArgumentException("Invalid workflow deadline");
    }
    @PostConstruct void recover() { store.recover(); }
    public List<Snapshot> list(UUID repository) { repositories.require(repository); return store.list(repository); }
    public Snapshot get(UUID id) { return store.require(id); }
    public Snapshot create(UUID repository, UUID planId) {
        Plan plan=planner.require(repository, planId);
        if (!admission.tryAcquire()) throw new ResponseStatusException(TOO_MANY_REQUESTS, "已有两个协同工作流在执行，请稍后再试。");
        Instant now=Instant.now(); UUID id=UUID.randomUUID();
        var tasks=plan.tasks().stream().map(spec -> new Task(spec.id(),spec.title(),spec.requires(),spec.after(),"PENDING","等待前置任务。","",null,null,List.of())).toList();
        Actor actor=new Actor(new Snapshot(id,repository,plan.repository(),plan,"PLANNED","已接受工作流，等待执行。",now,now,tasks,Data.empty(),0));
        try {
            store.insert(actor.value); active.put(id,actor);
            actor.pending = new FutureTask<>(() -> { actor.run(); return null; });
            actor.timer=timers.schedule(() -> actor.stop("TIMED_OUT","执行期限已到，保留已完成结果，其余任务已停止。"),timeout.toMillis(),TimeUnit.MILLISECONDS);
            workers.execute(() -> { try { actor.pending.run(); } finally { actor.cleanup(); } });
            return actor.value;
        } catch (RuntimeException error) {
            actor.stop("FAILED","执行线程未接受工作流，需重新创建。"); actor.cleanup();
            if (error instanceof org.springframework.dao.DataIntegrityViolationException) throw new ResponseStatusException(CONFLICT,"仓库登记已被移除，请重新选择仓库。");
            throw error;
        }
    }
    /** Resumes a WAITING workflow after re-checking the PR version; missing material can be supplied here. */
    public Snapshot resume(UUID id, Long issueNumber) {
        Snapshot current = store.require(id);
        if (!current.state().equals("WAITING")) throw new ResponseStatusException(CONFLICT, "工作流不在等待状态，不能恢复。");
        if (current.resumes() >= MAX_RESUMES) throw new ResponseStatusException(CONFLICT, "恢复次数已达上限（" + MAX_RESUMES + " 次），请重建计划。");
        Task blocking = current.tasks().stream().filter(task -> task.state().equals("WAITING")).findFirst().orElse(null);
        boolean materialWait = blocking != null && blocking.errorCode().equals("WAIT_MATERIAL");
        if (materialWait && issueNumber == null) throw new ResponseStatusException(BAD_REQUEST, "该工作流在等待 Issue 材料，请随恢复提交 Issue 编号。");
        if (!materialWait && issueNumber != null) throw new ResponseStatusException(BAD_REQUEST, "该工作流未在等待 Issue 材料，无需提交 Issue 编号。");
        if (issueNumber != null) issues.preview(current.repositoryId(), issueNumber, List.of(), null);
        var nowPull = prs.inspect(current.repositoryId(), current.plan().pull().number());
        if (!nowPull.baseSha().equals(current.plan().pull().baseSha()) || !nowPull.headSha().equals(current.plan().pull().headSha()))
            throw new ResponseStatusException(CONFLICT, "目标已变化：PR base/head 已更新，请重建计划；本条记录保留已完成结果，不混合新旧版本。");
        if (!admission.tryAcquire()) throw new ResponseStatusException(TOO_MANY_REQUESTS, "已有两个协同工作流在执行，请稍后再试。");
        Instant now=Instant.now();
        Plan plan = current.plan();
        if (issueNumber != null) plan = new Plan(plan.id(), plan.repositoryId(), plan.repository(), plan.pull(), issueNumber,
            "SELECTED", plan.issueReportId(), plan.ciReportId(), plan.createdAt(), plan.expiresAt(), plan.tasks(), plan.notice());
        var tasks = current.tasks().stream().map(task -> {
            if (task.state().equals("WAITING"))
                return new Task(task.id(), task.title(), task.requires(), task.after(), "PENDING", "已恢复，等待执行。", "", task.startedAt(), null, task.references());
            if (task.state().equals("SKIPPED") && task.errorCode().equals("DEPENDENCY_FAILED"))
                return new Task(task.id(), task.title(), task.requires(), task.after(), "PENDING", "前置已恢复，重新排队。", "", task.startedAt(), null, task.references());
            return task;
        }).toList();
        var resumed = new Snapshot(current.id(), current.repositoryId(), current.repository(), plan, "PLANNED",
            "已恢复执行（第 " + (current.resumes() + 1) + " 次）：恢复前已重新核对 PR base/head 未变化"
                + (issueNumber != null ? "，并补充 Issue #" + issueNumber : "") + "；已完成任务的结果保留原版本。",
            current.createdAt(), now, tasks, current.data(), current.resumes() + 1);
        store.update(resumed);
        Actor actor = new Actor(resumed);
        active.put(id, actor);
        try {
            actor.pending = new FutureTask<>(() -> { actor.run(); return null; });
            actor.timer = timers.schedule(() -> actor.stop("TIMED_OUT", "恢复段执行期限已到，保留已完成结果，其余任务已停止。"), timeout.toMillis(), TimeUnit.MILLISECONDS);
            workers.execute(() -> { try { actor.pending.run(); } finally { actor.cleanup(); } });
            return resumed;
        } catch (RuntimeException error) {
            actor.stop("FAILED", "执行线程未接受恢复的工作流。"); actor.cleanup();
            throw error;
        }
    }
    public Snapshot cancel(UUID id) {
        Snapshot current = store.require(id); Actor actor = active.get(id);
        if (actor != null) actor.stop("CANCELLED", "已取消，保留已完成结果，未开始的任务已跳过。");
        else if (!terminal(current.state())) {
            Instant now = Instant.now();
            var tasks = current.tasks().stream().map(task -> Set.of("PENDING","RUNNING","WAITING").contains(task.state())
                ? new Task(task.id(), task.title(), task.requires(), task.after(), task.state().equals("RUNNING") ? "CANCELLED" : "SKIPPED",
                    "已取消，保留已完成结果，未开始的任务已跳过。", "CANCELLED", task.startedAt(), now, task.references()) : task).toList();
            store.update(new Snapshot(current.id(), current.repositoryId(), current.repository(), current.plan(), "CANCELLED",
                "已取消，保留已完成结果，未开始的任务已跳过。", current.createdAt(), now, tasks, current.data(), current.resumes()));
        }
        return store.require(id);
    }
    /** True when a task's outcome must never re-run on resume: keep results, deliberate skips and permanent failures. */
    private static boolean settled(Task task) {
        if (Set.of("SUCCEEDED", "PARTIAL", "FAILED", "CANCELLED").contains(task.state())) return true;
        return task.state().equals("SKIPPED") && !task.errorCode().equals("DEPENDENCY_FAILED");
    }
    private final class Actor {
        volatile Snapshot value; volatile FutureTask<Void> pending; volatile ScheduledFuture<?> timer; boolean removed;
        final java.util.concurrent.atomic.AtomicBoolean cleaned = new java.util.concurrent.atomic.AtomicBoolean();
        Actor(Snapshot value) { this.value=value; }
        synchronized boolean publish(Snapshot next) { if (removed) return false; if (!store.update(next)) { removed=true; return false; } value=next; return true; }
        synchronized void task(int index, String state, String message, String code, List<Reference> references, Data data) {
            if (removed || terminal(value.state())) return;
            var tasks=new ArrayList<>(value.tasks()); Task old=tasks.get(index); Instant now=Instant.now();
            tasks.set(index,new Task(old.id(),old.title(),old.requires(),old.after(),state,message,code,state.equals("RUNNING")?now:old.startedAt(),state.equals("RUNNING")?null:now,references));
            publish(new Snapshot(value.id(),value.repositoryId(),value.repository(),value.plan(),"RUNNING","正在执行："+old.title(),value.createdAt(),now,List.copyOf(tasks),data,value.resumes()));
        }
        /** Persists WAITING with the reason; the worker and admission slot are released by the normal cleanup. */
        synchronized void waitSnapshot(String message) {
            if (removed || terminal(value.state())) return;
            publish(new Snapshot(value.id(),value.repositoryId(),value.repository(),value.plan(),"WAITING",message,value.createdAt(),Instant.now(),value.tasks(),value.data(),value.resumes()));
            if (pending!=null) pending.cancel(false);
        }
        synchronized void stop(String state,String message) {
            if (removed || terminal(value.state())) return;
            Instant now=Instant.now(); var tasks=value.tasks().stream().map(task -> Set.of("PENDING","RUNNING","WAITING").contains(task.state()) ? new Task(task.id(),task.title(),task.requires(),task.after(),task.state().equals("RUNNING")?"CANCELLED":"SKIPPED",message,state,task.startedAt(),now,task.references()) : task).toList();
            publish(new Snapshot(value.id(),value.repositoryId(),value.repository(),value.plan(),state,message,value.createdAt(),now,tasks,value.data(),value.resumes()));
            if (pending!=null) pending.cancel(true);
        }
        void run() {
                boolean waiting=false;
                for (int i=0;i<value.tasks().size();i++) {
                    if (removed || terminal(value.state()) || Thread.currentThread().isInterrupted()) break;
                    Task task=value.tasks().get(i);
                    if (settled(task)) continue;
                    List<String> failed=task.requires().stream().filter(required -> value.tasks().stream().filter(candidate -> candidate.id().equals(required)).noneMatch(candidate -> Set.of("SUCCEEDED","PARTIAL").contains(candidate.state()))).toList();
                    if (!failed.isEmpty()) { task(i,"SKIPPED","前置任务未完成："+String.join(", ",failed),"DEPENDENCY_FAILED",List.of(),value.data()); continue; }
                    task(i,"RUNNING","正在读取或处理已选材料。","",List.of(),value.data());
                    try {
                        Result result=runner.execute(task.id(),value.plan(),value.data(),value.tasks());
                        if (result.state().equals("WAIT_MATERIAL") || result.state().equals("WAIT_RETRY")) {
                            task(i,"WAITING",result.message(),result.state(),List.of(),result.data());
                            waitSnapshot("等待人工处理："+result.message()+" 已完成任务的结果与证据已保留；最多可恢复 " + MAX_RESUMES + " 次，恢复前会重新核对 PR 版本。");
                            waiting=true; break;
                        }
                        task(i,result.state(),result.message(),"",result.references(),result.data());
                    }
                    catch (CancellationException error) { stop("CANCELLED","执行已中断，保留已完成结果。"); return; }
                    catch (ResponseStatusException error) {
                        int code=error.getStatusCode().value();
                        String reason=error.getReason()==null?"任务读取失败。":error.getReason();
                        if (RETRYABLE.contains(code) && value.resumes() < MAX_RESUMES) {
                            task(i,"WAITING","可重试的读取失败："+reason+"；请核对网络或稍后恢复重试。","WAIT_RETRY",List.of(),value.data());
                            waitSnapshot("等待人工处理：任务「"+task.title()+"」遇到暂时性读取失败（"+code+"）。已保留已完成结果；最多可恢复 " + MAX_RESUMES + " 次，恢复前会重新核对 PR 版本。");
                            waiting=true; break;
                        }
                        task(i,"FAILED",reason,"UPSTREAM_"+code,List.of(),value.data());
                    }
                    catch (RuntimeException error) { task(i,"FAILED","任务执行失败，请在对应功能中重新核查。","INTERNAL_ERROR",List.of(),value.data()); }
                }
                if (!waiting) finish();
        }
        void cleanup() { if (cleaned.compareAndSet(false,true)) { if (timer!=null) timer.cancel(false); active.remove(value.id(),this); admission.release(); } }
        synchronized void finish() {
            if (removed || terminal(value.state())) return;
            boolean primaryFailure=value.tasks().stream().anyMatch(task -> Set.of("REPOSITORY","PR").contains(task.id()) && task.state().equals("FAILED"));
            boolean gaps=value.tasks().stream().anyMatch(task -> Set.of("FAILED","PARTIAL").contains(task.state()) || task.state().equals("WAITING"));
            String state=primaryFailure?"FAILED":gaps?"COMPLETED_WITH_GAPS":"COMPLETED";
            publish(new Snapshot(value.id(),value.repositoryId(),value.repository(),value.plan(),state,primaryFailure?"核心材料未读取，综合报告保留失败与缺口。":gaps?"工作流已结束，部分材料需要进一步核对。":"所选任务已完成，请核对综合报告的范围。",value.createdAt(),Instant.now(),value.tasks(),value.data(),value.resumes()));
        }
    }
    @PreDestroy public void close() { active.values().forEach(actor -> actor.stop("INTERRUPTED","应用正在关闭，保留已完成结果，没有自动重跑。")); workers.shutdownNow(); timers.shutdownNow(); }
}
