package io.github.mochiuaena.triage.material;

import com.fasterxml.jackson.databind.*;
import io.github.mochiuaena.triage.development.*;
import io.github.mochiuaena.triage.source.SourceProjectRepository;
import io.github.mochiuaena.triage.knowledge.KnowledgeService;
import io.github.mochiuaena.triage.store.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static io.github.mochiuaena.triage.material.MaterialModels.*;
import static org.springframework.http.HttpStatus.*;

/** Reads stored snapshots only; never fetches GitHub, runtime observations or source files. */
@Service
public class MaterialService {
    private final RunRepository runs;
    private final HistoryRepository history;
    private final PrReportStore prs;
    private final IssueReportStore issues;
    private final CiReportStore cis;
    private final WorkflowStore workflows;
    private final GithubRepositoryStore repositories;
    private final SourceProjectRepository projects;
    private final ObjectMapper json;
    private final KnowledgeService knowledge;
    public MaterialService(RunRepository runs, HistoryRepository history, PrReportStore prs, IssueReportStore issues,
                           CiReportStore cis, WorkflowStore workflows, GithubRepositoryStore repositories,
                           SourceProjectRepository projects, ObjectMapper json, KnowledgeService knowledge) {
        this.runs=runs; this.history=history; this.prs=prs; this.issues=issues; this.cis=cis; this.workflows=workflows;
        this.repositories=repositories; this.projects=projects; this.json=json; this.knowledge=knowledge;
    }
    public List<ScopeOption> scopes() {
        var result = new ArrayList<ScopeOption>();
        projects.list().forEach(p -> result.add(new ScopeOption("PROJECT",p.id().toString(),p.name(),p.service())));
        repositories.list().forEach(r -> result.add(new ScopeOption("REPOSITORY",r.id().toString(),r.owner()+"/"+r.name(),null)));
        history.services().forEach(s -> result.add(new ScopeOption("SERVICE",s.id(),s.name(),s.id())));
        return List.copyOf(result);
    }
    public Material resolve(Selection input) {
        if (input == null || input.type() == null || input.reportId() == null) throw new ResponseStatusException(BAD_REQUEST,"请选择已保存报告。");
        if (input.projectId() != null && input.repositoryId() != null) throw new ResponseStatusException(BAD_REQUEST,"项目和仓库范围不能混用。");
        if (input.type() == Type.KNOWLEDGE) return knowledge(input);
        Object saved = switch (input.type()) {
            case SERVICE -> runs.find(input.reportId()).orElseThrow(() -> new ResponseStatusException(NOT_FOUND,"服务报告不存在。"));
            case PR -> prs.require(input.reportId()); case ISSUE -> issues.require(input.reportId());
            case CI -> cis.require(input.reportId()); case WORKFLOW -> workflows.require(input.reportId());
            case KNOWLEDGE -> throw new IllegalStateException("Knowledge uses its immutable adapter");
        };
        JsonNode snapshot = json.valueToTree(saved);
        Scope scope; String version;
        if (input.type() == Type.SERVICE) {
            String service = snapshot.path("service").asText(null);
            UUID project = uuid(snapshot.path("sourceAnalysis").path("projectId"));
            version = "snapshot";
            if (project != null) {
                var current = projects.require(project);
                if (!Objects.equals(service,current.service())) throw new ResponseStatusException(CONFLICT,"报告项目的服务绑定已变化，请重新选择材料。");
                version += ":project:"+current.revision()+":"+current.index().hash();
            }
            scope = new Scope(project,null,service);
        } else {
            UUID repository = uuid(snapshot.path("repositoryId"));
            if (repository == null || repositories.list().stream().noneMatch(r -> r.id().equals(repository)))
                throw new ResponseStatusException(NOT_FOUND,"材料所属仓库已移除。");
            scope = new Scope(null,repository,null); version = "snapshot";
        }
        if ((input.projectId()!=null && !input.projectId().equals(scope.projectId()))
            || (input.repositoryId()!=null && !input.repositoryId().equals(scope.repositoryId()))
            || (input.service()!=null && !input.service().equals(scope.service())))
            throw new ResponseStatusException(CONFLICT,"报告不属于所选范围；没有保存项目身份的服务报告只能按服务引用。");
        var builder = new TextBuilder();
        List<String> paths = switch(input.type()) {
            case SERVICE -> List.of("question","service","status","scenario","synthetic","createdAt","finishedAt","endpoint","diagnosis","failure","evidence");
            case PR -> List.of("pull","mergeBaseSha","createdAt","source","files","findings","ci","issues","gaps","notice");
            case ISSUE -> List.of("issue","branch","headSha","contextReadAt","createdAt","source","proposal","model","evidence","owners","gaps");
            case CI -> List.of("runId","attempt","headSha","workflowName","job","source","createdAt","notice","analysis");
            case WORKFLOW -> List.of("plan","state","message","createdAt","updatedAt","tasks","data.repository","data.checks","data.summary");
            case KNOWLEDGE -> throw new IllegalStateException("Knowledge uses its immutable adapter");
        };
        for (String path : paths) {
            JsonNode value=snapshot; for (String segment:path.split("\\.")) value=value.path(segment);
            builder.add(path,value,input.type());
        }
        builder.omissions.add("未包含原始源码、完整补丁、完整日志、执行事件和任意证据 data；源码分享需另走现有项目授权机制。");
        if(input.type()==Type.WORKFLOW) builder.omissions.add("data.pr、data.issue、data.ci、data.priorCi 子报告正文未重复纳入；请分别引用保存的子报告。");
        String hash=sha(jsonText(snapshot));
        String link=input.type()==Type.SERVICE ? "/?run="+input.reportId() : "/development.html?repository="+scope.repositoryId()+"&reportType="+input.type()+"&report="+input.reportId();
        var reference=new Reference(input.type(),input.reportId(),scope,version+":"+hash,hash,Instant.now(),"UNCONFIRMED",link);
        return new Material(reference,builder.text.toString(),builder.fields,builder.omissions);
    }
    private Material knowledge(Selection input) {
        if (input.projectId()==null && input.repositoryId()==null) throw new ResponseStatusException(BAD_REQUEST,"知识引用必须携带保存版本的项目或仓库范围。");
        var selected=knowledge.scope(input.projectId()!=null?"PROJECT":"REPOSITORY",input.projectId()!=null?input.projectId():input.repositoryId());
        var version=knowledge.materialVersion(input.reportId(),selected);
        String service=selected.kind().equals("PROJECT")?projects.require(selected.id()).service():null;
        if(input.service()!=null && !input.service().equals(service)) throw new ResponseStatusException(CONFLICT,"知识引用不属于所选服务。");
        Scope scope=new Scope(input.projectId(),input.repositoryId(),service);
        var builder=new TextBuilder();
        builder.add("document.title",json.valueToTree(version.title()),Type.KNOWLEDGE);
        builder.add("document.path",json.valueToTree(version.path()),Type.KNOWLEDGE);
        builder.add("document.origin",json.valueToTree(version.origin()),Type.KNOWLEDGE);
        builder.add("document.commitSha",json.valueToTree(Objects.toString(version.commitSha(),"LOCAL")),Type.KNOWLEDGE);
        builder.add("document.sha256",json.valueToTree(version.sha256()),Type.KNOWLEDGE);
        var chunks=knowledge.chunks(version.id(),selected).stream().map(c -> Map.of("ordinal",c.ordinal(),"startLine",c.startLine(),"endLine",c.endLine(),"text",c.text())).toList();
        builder.add("document.chunks",json.valueToTree(chunks),Type.KNOWLEDGE);
        builder.omissions.add("项目资料仅本机预览；导入或普通报告确认均不授权资料外发。消费预览必须另行显式确认 documentsConfirmed=true。");
        String link="/sources.html?scopeKind="+selected.kind()+"&scopeId="+selected.id()+"&version="+version.id()+"#knowledge";
        return new Material(new Reference(Type.KNOWLEDGE,version.id(),scope,"knowledge:"+version.id()+":"+version.sha256(),version.sha256(),Instant.now(),"UNCONFIRMED",link),builder.text.toString(),builder.fields,builder.omissions);
    }
    private UUID uuid(JsonNode value) { return value.isTextual() ? UUID.fromString(value.asText()) : null; }
    private String jsonText(JsonNode value) { try { return json.writeValueAsString(value); } catch(Exception e) { throw new IllegalStateException("Cannot hash material",e); } }
    public static String sha(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException("Cannot hash material",e); }
    }
    private static final class TextBuilder {
        private static final int MAX_TEXT=16000, MAX_FIELD=2000, MAX_ITEMS=20;
        final StringBuilder text=new StringBuilder(); final List<String> fields=new ArrayList<>(), omissions=new ArrayList<>();
        void add(String path,JsonNode value,Type type) {
            String leaf=path.substring(path.lastIndexOf('.')+1);
            if (Set.of("data","excerpt","content","patch","body","graph","excerpts","sourceAnalysis").contains(leaf)
                || (leaf.equals("text") && type==Type.ISSUE && path.startsWith("evidence["))) { omissions.add(path+"：原始数据或源码片段未包含"); return; }
            if(value.isMissingNode()||value.isNull()) { omissions.add(path+"：保存快照缺失"); return; }
            if(text.length()>=MAX_TEXT) { omissions.add(path+"：达到正文上限，裁剪"); return; }
            if(value.isObject()) { value.fields().forEachRemaining(e -> add(path+"."+e.getKey(),e.getValue(),type)); return; }
            if(value.isArray()) {
                for(int i=0;i<Math.min(value.size(),MAX_ITEMS);i++) add(path+"["+i+"]",value.get(i),type);
                if(value.size()>MAX_ITEMS) omissions.add(path+"：仅前 "+MAX_ITEMS+" 项，裁剪 "+(value.size()-MAX_ITEMS)+" 项");
                if(value.isEmpty()) { text.append(path).append("=[]\n"); fields.add(path); }
                return;
            }
            String raw=value.asText(); String clean=PrEvidenceRedactor.clean(raw);
            if(!raw.equals(clean)) omissions.add(path+"：凭据已脱敏");
            int length=Math.min(MAX_FIELD,Math.max(0,MAX_TEXT-text.length()-path.length()-2));
            if(clean.length()>length) { clean=clean.substring(0,length); omissions.add(path+"：裁剪"); }
            String line=path+"="+clean+"\n";
            if(text.length()+line.length()>MAX_TEXT) { omissions.add(path+"：达到正文上限，裁剪"); return; }
            text.append(line);fields.add(path);
        }
    }
}
