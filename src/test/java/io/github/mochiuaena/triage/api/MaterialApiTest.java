package io.github.mochiuaena.triage.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mochiuaena.triage.domain.TriageModel.*;
import io.github.mochiuaena.triage.store.RunRepository;
import io.github.mochiuaena.triage.source.*;
import io.github.mochiuaena.triage.settings.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "triage.mode=DEMO", "spring.datasource.url=jdbc:h2:mem:materials;DB_CLOSE_DELAY=-1",
    "triage.settings.key-file=target/materials-test-key"
})
class MaterialApiTest {
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired RunRepository runs;
    @Autowired SourceProjectRepository projects;
    @Autowired ProviderRegistry providers;
    UUID repository;
    @BeforeEach void prepare() {
        providers.select("DEMO",null);
        repository = UUID.randomUUID();
        jdbc.update("INSERT INTO github_repositories(id,owner_name,repository_name,registered_at,checked_at,snapshot) VALUES(?,?,?,?,?,?)",
            repository.toString(), "fixture", "repo-" + repository, OffsetDateTime.now(), OffsetDateTime.now(), "{}");
    }
    HttpHeaders headers() { var h = new HttpHeaders(); h.setContentType(MediaType.APPLICATION_JSON); h.set("X-Triage-Material", "1"); return h; }
    ResponseEntity<Map> post(String path, Object body) { return http.postForEntity("/api/materials" + path, new HttpEntity<>(body, headers()), Map.class); }
    Map<String,Object> selection(String type, UUID id) { var value = new HashMap<String,Object>(); value.put("type",type); value.put("reportId",id); if (!type.equals("SERVICE")) value.put("repositoryId",repository); return value; }
    UUID service(String question) {
        var run = new Run(UUID.randomUUID(), question, "order-service", 5, Scenario.NORMAL, "DEMO", true, Status.SUCCEEDED,
            Instant.now(), Instant.now(), 0, List.of(), List.of(new Evidence("e1","fixture","runtime","safe",Map.of("password","raw-must-not-leak"))), null, null);
        runs.insert(run); return run.id();
    }
    UUID saved(String type) throws Exception {
        UUID id = UUID.randomUUID(); String table = Map.of("PR","pr_reports","ISSUE","issue_reports","CI","ci_reports","WORKFLOW","development_workflows").get(type);
        var payload = new HashMap<String,Object>(); payload.put("id",id); payload.put("repositoryId",repository); payload.put("repository","fixture/repo"); payload.put("createdAt",Instant.now());
        if (type.equals("PR")) { payload.put("notice","Authorization: Bearer fixture-secret"); payload.put("files",List.of()); payload.put("findings",List.of()); payload.put("gaps",List.of("patch absent")); }
        if (type.equals("CI")) payload.put("notice","log omitted");
        if (type.equals("WORKFLOW")) { payload.put("state","COMPLETED"); payload.put("updatedAt",Instant.now()); payload.put("tasks",List.of()); }
        String text = json.writeValueAsString(payload);
        if (type.equals("WORKFLOW")) jdbc.update("INSERT INTO development_workflows(id,repository_id,created_at,state,payload) VALUES(?,?,?,?,?)",id.toString(),repository.toString(),OffsetDateTime.now(),"COMPLETED",text);
        else jdbc.update("INSERT INTO " + table + "(id,repository_id,created_at,payload) VALUES(?,?,?,?)",id.toString(),repository.toString(),OffsetDateTime.now(),text);
        return id;
    }
    @Test void resolvesFiveSavedFamiliesAndKeepsReportsUntouched() throws Exception {
        for (String type : List.of("SERVICE","PR","ISSUE","CI","WORKFLOW")) {
            UUID id = type.equals("SERVICE") ? service("Check order") : saved(type);
            var response = post("/resolve",selection(type,id));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            var reference = (Map<?,?>) response.getBody().get("reference");
            assertThat(reference.get("type")).isEqualTo(type); assertThat(reference.get("reportId")).isEqualTo(id.toString());
            assertThat(reference.get("contentSha256").toString()).matches("[a-f0-9]{64}");
            assertThat(reference.get("originalLink").toString()).startsWith(type.equals("SERVICE") ? "/?run=" : "/development.html?");
            assertThat(response.getBody().get("omissions").toString()).isNotBlank();
            assertThat(response.getBody().toString()).doesNotContain("fixture-secret","raw-must-not-leak");
            assertThat(reference.get("confirmation")).isEqualTo("UNCONFIRMED");
        }
        UUID id = service("token=fixture-secret " + "x".repeat(10000));
        String before = jdbc.queryForObject("SELECT payload FROM triage_runs WHERE id=?",String.class,id.toString());
        var result = post("/resolve",selection("SERVICE",id));
        assertThat(result.getBody().get("text").toString()).hasSizeLessThan(17000).doesNotContain("fixture-secret");
        assertThat(result.getBody().get("omissions").toString()).contains("question","裁剪");
        assertThat(jdbc.queryForObject("SELECT payload FROM triage_runs WHERE id=?",String.class,id.toString())).isEqualTo(before);
    }
    @Test void rejectsUnknownWrongAndMixedScopeAndCallerRawContent() throws Exception {
        assertThat(post("/resolve",selection("PR",UUID.randomUUID())).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        var wrong = selection("PR",saved("PR")); wrong.put("repositoryId",UUID.randomUUID());
        assertThat(post("/resolve",wrong).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        wrong.put("projectId",UUID.randomUUID());
        assertThat(post("/resolve",wrong).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post("/previews",Map.of("references",List.of(selection("PR",saved("PR")),selection("SERVICE",service("q"))))).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        var raw = selection("SERVICE",service("saved")); raw.put("content","INJECTED RAW");
        var result = post("/resolve",raw);
        assertThat(result.getBody().toString()).doesNotContain("INJECTED RAW");
    }
    @Test void unavailableModelStillPreviewsSavedMaterialsButCannotConsume() {
        UUID id = service("question");
        var response = post("/previews",Map.of("references",List.of(selection("SERVICE",id))));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(((Map<?,?>) response.getBody().get("recipient")).get("available")).isEqualTo(false);
        assertThat(response.getBody().get("text").toString()).contains("question","不可信");
        assertThat(post("/previews/" + response.getBody().get("id") + "/consume",Map.of("confirmed",true)).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }
    @Test void localOriginAndExplicitMarkerProtectMaterialsAndResponsesAreNotCached() {
        var input=selection("SERVICE",service("q"));
        assertThat(http.postForEntity("/api/materials/resolve",input,String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        var h=headers();h.set("Origin","https://evil.example");
        assertThat(http.postForEntity("/api/materials/resolve",new HttpEntity<>(input,h),String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(http.getForEntity("/api/materials/scopes",String.class).getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(post("/resolve",input).getHeaders().getCacheControl()).isEqualTo("no-store");
    }
    @Test void savedProjectIdentityWinsOverCurrentBindingsAndSourceIsNeverImplicitlyShared() {
        String service="material-"+UUID.randomUUID();
        var index=new SourceModels.Index("fixed-index-hash",Instant.now(),0,0,0,List.of());
        var project=projects.create("Material project",service,"fixture-root",index);
        var analysis=new SourceModels.Analysis(project.id(),project.name(),1,index.hash(),"LOCAL",false,"local only",
            List.of(new SourceModels.Excerpt("symbol","A.java","hash","A","method","",List.of(),List.of(),1,1,"SOURCE-DO-NOT-SEND")));
        var run=new Run(UUID.randomUUID(),"saved scoped report",service,5,Scenario.NORMAL,"DEMO",true,Status.SUCCEEDED,
            Instant.now(),Instant.now(),0,List.of(),List.of(),null,null,null,null,analysis);
        runs.insert(run);
        var input=selection("SERVICE",run.id());input.put("projectId",project.id());
        var first=post("/resolve",input);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody().toString()).doesNotContain("SOURCE-DO-NOT-SEND","fixture-root");
        var reference=(Map<?,?>)first.getBody().get("reference");
        assertThat(((Map<?,?>)reference.get("scope")).get("projectId")).isEqualTo(project.id().toString());
        projects.reindex(project,new SourceModels.Index("changed-index-hash",Instant.now(),0,0,0,List.of()));
        assertThat(((Map<?,?>)post("/resolve",input).getBody().get("reference")).get("version")).isNotEqualTo(reference.get("version"));
        var legacy=selection("SERVICE",service("no saved project identity"));legacy.put("projectId",project.id());
        assertThat(post("/resolve",legacy).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        input.put("projectId",UUID.randomUUID());assertThat(post("/resolve",input).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        projects.delete(project.id(),2);
        assertThat(post("/resolve",selection("SERVICE",run.id())).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
    @Test void registeredProviderNeverCallsNetworkAndConsumeRechecksRealSavedSnapshots() throws Exception {
        var provider=providers.create(new ProviderConfig.Input("Material fixture",ProviderConfig.Protocol.OPENAI_COMPATIBLE,
            "http://127.0.0.1:18199/v1","fixture-no-network","private-provider-key",0.2,1,1,256,0));
        providers.select("MODEL",provider.id());
        UUID id=service("frozen question");var input=Map.of("references",List.of(selection("SERVICE",id)));
        var preview=post("/previews",input);
        assertThat(preview.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(preview.getBody().toString()).doesNotContain("private-provider-key","encryptedKey","apiKey");
        assertThat(((Map<?,?>)preview.getBody().get("recipient")).get("providerId")).isEqualTo(provider.id().toString());
        var sent=post("/previews/"+preview.getBody().get("id")+"/consume",Map.of("confirmed",true));
        assertThat(sent.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sent.getBody().get("text")).isEqualTo(preview.getBody().get("text"));
        assertThat(post("/previews/"+preview.getBody().get("id")+"/consume",Map.of("confirmed",true)).getStatusCode()).isEqualTo(HttpStatus.GONE);
        preview=post("/previews",input);
        var payload=json.readTree(jdbc.queryForObject("SELECT payload FROM triage_runs WHERE id=?",String.class,id.toString()));
        ((com.fasterxml.jackson.databind.node.ObjectNode)payload).put("question","changed saved question");
        jdbc.update("UPDATE triage_runs SET payload=? WHERE id=?",json.writeValueAsString(payload),id.toString());
        assertThat(post("/previews/"+preview.getBody().get("id")+"/consume",Map.of("confirmed",true)).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        preview=post("/previews",input);providers.select("DEMO",null);
        assertThat(post("/previews/"+preview.getBody().get("id")+"/consume",Map.of("confirmed",true)).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
