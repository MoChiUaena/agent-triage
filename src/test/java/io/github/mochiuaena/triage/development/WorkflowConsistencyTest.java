package io.github.mochiuaena.triage.development;

import io.github.mochiuaena.triage.development.WorkflowModels.*;
import io.github.mochiuaena.triage.development.GithubModels.Key;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.http.HttpStatus.FORBIDDEN;

class WorkflowConsistencyTest {
    final UUID repository=UUID.randomUUID(); final String BASE="a".repeat(40), HEAD="b".repeat(40);
    final PrReviewService prs=mock(PrReviewService.class); final CiTriageService ci=mock(CiTriageService.class);
    final WorkflowCiReader reader=mock(WorkflowCiReader.class); final WorkflowPolicy policy=new WorkflowPolicy();
    final WorkflowTasks tasks=new WorkflowTasks(mock(GithubClient.class),prs,mock(IssueTriageService.class),mock(GithubIssueClient.class),ci,reader,policy);
    PrModels.Context context(String head) { return new PrModels.Context(7,"Change","https://github.com/acme/service/pull/7","open",false,BASE,head,0,0,0,List.of(),false); }
    Plan plan() { Instant time=Instant.now(); return new Plan(UUID.randomUUID(),repository,"acme/service",context(HEAD),null,"NONE",null,null,time,time.plusSeconds(300),List.of(),""); }
    PrModels.Report pr(String state) { return new PrModels.Report(UUID.randomUUID(),repository,"acme/service",context(HEAD),BASE,Instant.now(),"GITHUB_RULES",List.of(),List.of(),new PrModels.CiSnapshot(state,true,true,false,List.of(new PrModels.CiCheck("Build","completed",state.equals("OBSERVED_SUCCESS")?"success":"failure","https://github.com/acme/service/pull/7/checks")),List.of()),List.of(),List.of(),""); }
    CiModels.Job job(int attempt) { return new CiModels.Job(91,9,attempt,HEAD,"Build","completed","failure",List.of(),"https://github.com/acme/service/actions/runs/9/job/91"); }
    CiModels.Report report() { return new CiModels.Report(UUID.randomUUID(),repository,"acme/service",9,1,HEAD,"Verify",job(1),"GITHUB",Instant.now(),"",new CiModels.Analysis("COMPILATION_FAILURE","OBSERVED","Compilation failed","",List.of("OLD_ATTEMPT_FIX"),List.of(),List.of(),"Build",false)); }
    Data reused(String checks) { return Data.empty().pr(pr(checks)).ci(new CiObservation("REUSED",report(),"SAVED_REPORT",0,false,List.of("已有 CI 报告尚需核对提交、来源和尝试次数。"))); }
    void stable() { when(prs.inspect(repository,7)).thenReturn(context(HEAD)); }
    @Test void aSuccessfulCurrentAttemptRemovesTheOldFailureConflict() {
        stable(); Data old=reused("OBSERVED_SUCCESS").checks(List.of(new Check("CI_ATTEMPT_CHANGED","CONFLICT","old attempt",List.of("CI")),new Check("CI_STATUS_DISAGREEMENT","CONFLICT","old failure",List.of("CI","PR"))));
        when(prs.create(repository,7,BASE,HEAD)).thenReturn(pr("OBSERVED_SUCCESS"));
        when(reader.read(repository,new Key("acme","service"),HEAD)).thenReturn(new CiObservation("NO_FAILED_RUNS",null,"GITHUB",1,false,List.of()));
        var result=tasks.execute("RECHECK",plan(),old,List.of());
        assertThat(result.data().checks().stream().filter(check->check.code().equals("CI_STATUS_DISAGREEMENT")).findFirst().orElseThrow().state()).isEqualTo("RESOLVED");
        assertThat(policy.summarize(result.data(),List.of()).verdict()).isEqualTo("REVIEW_REMAINING");
    }
    @Test void matchingSavedGithubEvidenceCanBecomeApplicableAfterValidation() {
        stable(); when(ci.inspect(repository,9)).thenReturn(new CiModels.Context(9,1,HEAD,"Verify","main","completed","failure","",List.of(job(1)),false));
        var result=tasks.execute("CHECK",plan(),reused("OBSERVED_FAILURE"),List.of());
        assertThat(result.data().ci().state()).isEqualTo("ANALYZED");
        var summary=policy.summarize(result.data(),List.of()); assertThat(summary.verdict()).isEqualTo("ATTENTION_REQUIRED");
        assertThat(summary.sources()).extracting(Reference::kind).contains("CI");
        assertThat(summary.gaps()).noneMatch(text->text.contains("尚需核对提交"));
    }
    @Test void failedRecheckDoesNotPromoteKnownOldAttemptEvidenceIntoApplicableSources() {
        stable(); when(ci.inspect(repository,9)).thenReturn(new CiModels.Context(9,2,HEAD,"Verify","main","completed","failure","",List.of(job(2)),false));
        Data checked=tasks.execute("CHECK",plan(),reused("OBSERVED_FAILURE"),List.of()).data();
        when(prs.create(repository,7,BASE,HEAD)).thenReturn(pr("OBSERVED_FAILURE"));
        when(reader.read(repository,new Key("acme","service"),HEAD)).thenThrow(new ResponseStatusException(FORBIDDEN,"Logs unavailable"));
        assertThatThrownBy(()->tasks.execute("RECHECK",plan(),checked,List.of())).isInstanceOf(ResponseStatusException.class);
        var summary=policy.summarize(checked,List.of());
        assertThat(summary.sources()).extracting(Reference::kind).doesNotContain("CI");
        assertThat(summary.actions()).doesNotContain("OLD_ATTEMPT_FIX");
        assertThat(summary.verdict()).isEqualTo("INCOMPLETE");
    }
    @Test void aPrUpdateDiscoveredByRecheckRequiresANewPlan() {
        when(prs.inspect(repository,7)).thenReturn(context("d".repeat(40)));
        Data old=reused("OBSERVED_FAILURE").checks(List.of(new Check("CI_HEAD_MISMATCH","CONFLICT","old commit",List.of("PR","CI"))));
        var result=tasks.execute("RECHECK",plan(),old,List.of());
        assertThat(policy.summarize(result.data(),List.of()).verdict()).isEqualTo("REPLAN_REQUIRED");
        assertThat(policy.summarize(result.data(),List.of()).sources()).isEmpty();
    }
    @Test void unavailableChecksCannotBeUsedToResolveAStatusConflict() {
        stable(); Data old=reused("OBSERVED_SUCCESS").checks(List.of(new Check("CI_STATUS_DISAGREEMENT","CONFLICT","old failure",List.of("CI","PR"))));
        when(prs.create(repository,7,BASE,HEAD)).thenReturn(pr("INCOMPLETE"));
        when(reader.read(repository,new Key("acme","service"),HEAD)).thenReturn(new CiObservation("ANALYZED",report(),"GITHUB",1,false,List.of()));
        var result=tasks.execute("RECHECK",plan(),old,List.of());
        assertThat(result.data().checks().getFirst().state()).isNotEqualTo("RESOLVED");
        assertThat(policy.summarize(result.data(),List.of()).verdict()).isEqualTo("INCOMPLETE");
    }
    @Test void replacingOldEvidenceAlsoResolvesValidationGapsThatBelongedToIt() {
        stable(); when(prs.create(repository,7,BASE,HEAD)).thenReturn(pr("OBSERVED_SUCCESS"));
        when(reader.read(repository,new Key("acme","service"),HEAD)).thenReturn(new CiObservation("NO_FAILED_RUNS",null,"GITHUB",1,false,List.of()));
        for (String code : List.of("CI_ATTEMPT_UNVERIFIED","CI_JOB_UNVERIFIED")) {
            Data old=reused("OBSERVED_SUCCESS").checks(List.of(new Check("CI_STATUS_DISAGREEMENT","CONFLICT","old failure",List.of("CI","PR")),new Check(code,"GAP","old validation unavailable",List.of("CI"))));
            var result=tasks.execute("RECHECK",plan(),old,List.of());
            assertThat(policy.summarize(result.data(),List.of()).verdict()).isEqualTo("REVIEW_REMAINING");
            assertThat(result.data().checks().stream().filter(check->check.code().equals(code)).findFirst().orElseThrow().state()).isEqualTo("RESOLVED");
        }
    }
}
