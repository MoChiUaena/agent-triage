package io.github.mochiuaena.triage.knowledge;

import static io.github.mochiuaena.triage.knowledge.KnowledgeModels.*;

import static org.assertj.core.api.Assertions.*;

import io.github.mochiuaena.triage.development.*;
import io.github.mochiuaena.triage.source.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@SpringBootTest(
        properties = {
            "triage.mode=DEMO",
            "spring.datasource.url=jdbc:h2:mem:knowledge-lifecycle;DB_CLOSE_DELAY=-1",
            "triage.settings.key-file=target/knowledge-lifecycle-key"
        })
class KnowledgeLifecycleTest {
    @Autowired KnowledgeRepository store;
    @Autowired SourceProjectRepository projects;
    @Autowired GithubRepositoryStore repos;
    @Autowired GithubKnowledgeReader github;
    @TempDir Path directory;
    KnowledgeService service;
    BlockingFiles files;
    UUID project;

    static class BlockingFiles extends KnowledgeFiles {
        volatile boolean block;
        volatile boolean blockAfterFirst;
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);

        @Override
        public Read read(
                String root,
                java.util.function.BooleanSupplier cancelled,
                java.util.function.IntConsumer progress) {
            if (block) {
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS))
                        throw new IllegalStateException("fixture timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return super.read(
                    root,
                    cancelled,
                    count -> {
                        progress.accept(count);
                        if (blockAfterFirst && count == 1) {
                            entered.countDown();
                            try {
                                if (!release.await(10, TimeUnit.SECONDS))
                                    throw new IllegalStateException("fixture timed out");
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    });
        }
    }

    @BeforeEach
    void setup() throws Exception {
        Files.writeString(directory.resolve("doc.md"), "# stable\noriginal");
        project =
                projects.create(
                                "fixture",
                                null,
                                directory.toString(),
                                new SourceModels.Index(
                                        "fixture", Instant.now(), 0, 0, 0, List.of()))
                        .id();
        files = new BlockingFiles();
        service = new KnowledgeService(store, projects, repos, files, github);
    }

    @AfterEach
    void close() {
        files.release.countDown();
        service.close();
    }

    Preview preview() {
        return service.preview(
                new Input("PROJECT", project, directory.toString(), null, null, null));
    }

    Job await(UUID id) throws Exception {
        Job result = store.job(id);
        for (int i = 0; i < 200 && Set.of("QUEUED", "RUNNING").contains(result.state()); i++) {
            Thread.sleep(20);
            result = store.job(id);
        }
        return result;
    }

    @Test
    void runningAndQueuedCancellationNeverActivateAndQueueIsBounded() throws Exception {
        var manifests = new ArrayList<Preview>();
        for (int i = 0; i < 7; i++) manifests.add(preview());
        files.block = true;
        Job running = service.submit(manifests.get(0).id());
        assertThat(files.entered.await(3, TimeUnit.SECONDS)).isTrue();
        List<Job> queued = new ArrayList<>();
        for (int i = 1; i < 5; i++) queued.add(service.submit(manifests.get(i).id()));
        assertThatThrownBy(() -> service.submit(manifests.get(5).id())).hasMessageContaining("容量");
        assertThat(service.cancel(queued.getFirst().id()).state()).isEqualTo("CANCELLED");
        service.cancel(running.id());
        files.release.countDown();
        assertThat(await(running.id()).state()).isEqualTo("CANCELLED");
        for (Job q : queued.subList(1, queued.size()))
            assertThat(await(q.id()).state()).isIn("SUCCEEDED", "FAILED");
        assertThat(store.job(queued.getFirst().id()).state()).isEqualTo("CANCELLED");
    }

    @Test
    void restartInterruptsPendingJobsAndDoesNotReplayOrActivate() throws Exception {
        Preview p = preview(), q = preview();
        files.block = true;
        Job running = service.submit(p.id());
        assertThat(files.entered.await(3, TimeUnit.SECONDS)).isTrue();
        Job queued = service.submit(q.id());
        var restarted = new KnowledgeService(store, projects, repos, files, github);
        restarted.close();
        assertThat(store.job(running.id()).state()).isEqualTo("INTERRUPTED");
        assertThat(store.job(queued.id()).state()).isEqualTo("INTERRUPTED");
        files.release.countDown();
        Thread.sleep(150);
        assertThat(store.sources(new Scope("PROJECT", project))).isEmpty();
    }

    @Test
    void optimisticUpdatesDoNotReplacePriorActiveMappingsAndUnbindKeepsChunks() throws Exception {
        var initial = preview();
        assertThat(await(service.submit(initial.id()).id()).state()).isEqualTo("SUCCEEDED");
        Source source = store.sources(initial.scope()).getFirst();
        Preview stale = preview();
        store.action(source.id(), source.scope(), source.revision(), "UNBIND");
        assertThat(await(service.submit(stale.id()).id()).state()).isEqualTo("FAILED");
        assertThat(store.require(source.id()).state()).isEqualTo("UNBOUND");
        var version = store.versions(source.id()).getFirst();
        assertThat(store.chunks(version.id())).isNotEmpty();
        assertThat(service.version(version.id(), source.scope()).text()).contains("original");
        assertThat(await(service.submit(preview().id()).id()).state()).isEqualTo("SUCCEEDED");
    }

    @Test
    void previewCacheHasTenEntriesAndStoresOnlyBoundedExcerpts() {
        for (int i = 0; i < 10; i++) {
            Preview p = preview();
            assertThat(p.files().getFirst().excerpt().length()).isLessThanOrEqualTo(240);
        }
        assertThatThrownBy(this::preview).hasMessageContaining("容量");
    }

    @Test
    void progressUpdatesBeforeCompletionAndCancellationKeepsThePriorActiveVersion()
            throws Exception {
        assertThat(await(service.submit(preview().id()).id()).state()).isEqualTo("SUCCEEDED");
        var prior = store.sources(new Scope("PROJECT", project)).getFirst();
        Files.writeString(directory.resolve("doc.md"), "changed");
        Files.writeString(directory.resolve("two.md"), "second");
        Preview p = preview();
        files.blockAfterFirst = true;
        Job job = service.submit(p.id());
        assertThat(files.entered.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(store.job(job.id()).progress()).isEqualTo(1);
        assertThat(store.job(job.id()).total()).isEqualTo(2);
        service.cancel(job.id());
        files.release.countDown();
        assertThat(await(job.id()).state()).isEqualTo("CANCELLED");
        assertThat(store.require(prior.id()).revision()).isEqualTo(prior.revision());
        assertThat(store.versions(prior.id())).hasSize(1);
        assertThat(store.require(prior.id()).documents()).isEqualTo(1);
    }

    @Test
    void expiresAtTenMinutesAndRegistrationChangeCannotBeImported() {
        class MutableClock extends Clock {
            Instant at = Instant.parse("2026-10-15T00:00:00Z");

            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            public Clock withZone(ZoneId zone) {
                return this;
            }

            public Instant instant() {
                return at;
            }
        }
        service.close();
        var clock = new MutableClock();
        service = new KnowledgeService(store, projects, repos, files, github, clock);
        Preview p = preview();
        clock.at = clock.at.plusSeconds(600);
        assertThatThrownBy(() -> service.submit(p.id())).hasMessageContaining("过期");
        Preview valid = preview();
        projects.delete(project, projects.require(project).revision());
        assertThatThrownBy(() -> service.submit(valid.id())).hasMessageContaining("不存在");
    }
}
