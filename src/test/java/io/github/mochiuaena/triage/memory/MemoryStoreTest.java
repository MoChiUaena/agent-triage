package io.github.mochiuaena.triage.memory;

import static io.github.mochiuaena.triage.memory.MemoryModels.*;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.*;
import java.util.concurrent.*;

@SpringBootTest(
        properties = {
            "triage.mode=DEMO",
            "spring.datasource.url=jdbc:h2:mem:memory-store;DB_CLOSE_DELAY=-1",
            "triage.settings.key-file=target/memory-store-key"
        })
class MemoryStoreTest {
    @Autowired MemoryStore store;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    final Scope scope = new Scope("PROJECT", UUID.randomUUID(), "service");

    Body body(String text) {
        return new Body(
                "topic",
                text,
                new Basis("user statement", List.of(), null, List.of(), List.of(), "用户陈述，未经独立验证。"));
    }

    @Test
    void revisionCapStillAllowsBoundedWithdrawalOfConfirmedMemory() {
        var first = store.create(scope, body("withdrawal"));
        for (int r = 1; r < 19; r++) store.edit(first.id(), scope, r, body("v" + r));
        var pending = store.current(first.id(), scope);
        var confirmed =
                store.transition(first.id(), scope, 19, "CONFIRMED", true, pending.bodySha256());
        assertThat(confirmed.revision()).isEqualTo(20);
        var disabled = store.transition(first.id(), scope, 20, "DISABLED", false, null);
        assertThat(disabled.revision()).isEqualTo(21);
        assertThatThrownBy(() -> store.transition(first.id(), scope, 21, "DISABLED", false, null))
                .hasMessageContaining("上限");
        assertThatThrownBy(
                        () ->
                                store.transition(
                                        first.id(),
                                        scope,
                                        21,
                                        "CONFIRMED",
                                        true,
                                        disabled.bodySha256()))
                .hasMessageContaining("上限");
        var deleted = store.transition(first.id(), scope, 21, "DELETED", false, null);
        assertThat(deleted.revision()).isEqualTo(22);
        assertThat(store.list(scope, "CONFIRMED", 20)).isEmpty();
        assertThat(store.history(first.id(), scope)).hasSize(22);
    }

    @Test
    void disabledMemoryRequiresExplicitReviewAndEditingCreatesCandidate() {
        var first = store.create(scope, body("disabled"));
        var disabled = store.transition(first.id(), scope, 1, "DISABLED", false, null);
        assertThatThrownBy(
                        () ->
                                store.transition(
                                        first.id(),
                                        scope,
                                        2,
                                        "CONFIRMED",
                                        false,
                                        disabled.bodySha256()))
                .hasMessageContaining("确认");
        var confirmed =
                store.transition(first.id(), scope, 2, "CONFIRMED", true, disabled.bodySha256());
        var stopped =
                store.transition(first.id(), scope, confirmed.revision(), "DISABLED", false, null);
        var edited = store.edit(first.id(), scope, stopped.revision(), body("revised disabled"));
        assertThat(edited.status()).isEqualTo("CANDIDATE");
        assertThat(edited.confirmedAt()).isNull();
    }

    @Test
    void headCapsIncludeDeletedEntriesAndRejectBeforeInsertion() {
        String tag = UUID.randomUUID().toString();
        var now = java.time.OffsetDateTime.now();
        try {
            for (int n = 0; n < 100; n++)
                jdbc.update(
                        "INSERT INTO"
                            + " memory_heads(id,scope_kind,scope_id,revision,status,topic,body_sha256,updated_at)"
                            + " VALUES(?,?,?,1,'DELETED',?,?,?)",
                        UUID.randomUUID().toString(),
                        scope.kind(),
                        scope.id().toString(),
                        tag,
                        "hash",
                        now);
            assertThatThrownBy(() -> store.create(scope, body("overflow scope")))
                    .hasMessageContaining("100");
            long count = jdbc.queryForObject("SELECT COUNT(*) FROM memory_heads", Long.class);
            for (long n = count; n < 1000; n++)
                jdbc.update(
                        "INSERT INTO"
                            + " memory_heads(id,scope_kind,scope_id,revision,status,topic,body_sha256,updated_at)"
                            + " VALUES(?,?,?,1,'DELETED',?,?,?)",
                        UUID.randomUUID().toString(),
                        "REPOSITORY",
                        UUID.randomUUID().toString(),
                        tag,
                        "hash",
                        now);
            assertThatThrownBy(
                            () ->
                                    store.create(
                                            new Scope("PROJECT", UUID.randomUUID(), null),
                                            body("overflow global")))
                    .hasMessageContaining("1000");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM memory_heads", Long.class))
                    .isEqualTo(1000);
        } finally {
            jdbc.update("DELETE FROM memory_heads WHERE topic=?", tag);
        }
    }

    @Test
    void immutableLifecycleRequiresExactReviewedBodyAndEditsClearConfirmation() {
        var first = store.create(scope, body("first"));
        assertThat(store.list(scope, "CONFIRMED", 20)).isEmpty();
        assertThatThrownBy(
                        () ->
                                store.transition(
                                        first.id(),
                                        scope,
                                        1,
                                        "CONFIRMED",
                                        false,
                                        first.bodySha256()))
                .hasMessageContaining("确认");
        assertThatThrownBy(() -> store.transition(first.id(), scope, 1, "CONFIRMED", true, "wrong"))
                .hasMessageContaining("变化");
        var confirmed =
                store.transition(first.id(), scope, 1, "CONFIRMED", true, first.bodySha256());
        assertThat(confirmed.confirmedAt()).isNotNull();
        assertThat(store.list(scope, "CONFIRMED", 20)).hasSize(1);
        var edited = store.edit(first.id(), scope, 2, body("second"));
        assertThat(edited.status()).isEqualTo("CANDIDATE");
        assertThat(edited.confirmedAt()).isNull();
        assertThat(store.version(first.id(), scope, 1).body().content()).isEqualTo("first");
        assertThatThrownBy(
                        () ->
                                store.transition(
                                        first.id(),
                                        scope,
                                        2,
                                        "CONFIRMED",
                                        true,
                                        confirmed.bodySha256()))
                .hasMessageContaining("变化");
        var disabled = store.transition(first.id(), scope, 3, "DISABLED", false, null);
        assertThat(store.list(scope, "CONFIRMED", 20)).isEmpty();
        var deleted =
                store.transition(first.id(), scope, disabled.revision(), "DELETED", false, null);
        assertThatThrownBy(() -> store.edit(first.id(), scope, deleted.revision(), body("revive")))
                .hasMessageContaining("删除");
        assertThat(store.history(first.id(), scope)).hasSize(5);
        assertThatThrownBy(
                        () ->
                                store.version(
                                        first.id(),
                                        new Scope("PROJECT", UUID.randomUUID(), "service"),
                                        1))
                .hasMessageContaining("范围");
    }

    @Test
    void racingEditAndConfirmHaveOneWinner() throws Exception {
        var first = store.create(scope, body("race"));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var tasks =
                    List.<Callable<Boolean>>of(
                            () -> {
                                gate.await();
                                try {
                                    store.edit(first.id(), scope, 1, body("changed"));
                                    return true;
                                } catch (org.springframework.web.server.ResponseStatusException e) {
                                    return false;
                                }
                            },
                            () -> {
                                gate.await();
                                try {
                                    store.transition(
                                            first.id(),
                                            scope,
                                            1,
                                            "CONFIRMED",
                                            true,
                                            first.bodySha256());
                                    return true;
                                } catch (org.springframework.web.server.ResponseStatusException e) {
                                    return false;
                                }
                            });
            var a = pool.submit(tasks.get(0));
            var b = pool.submit(tasks.get(1));
            gate.countDown();
            assertThat(List.of(a.get(), b.get()).stream().filter(Boolean::booleanValue).count())
                    .isEqualTo(1);
            assertThat(store.history(first.id(), scope)).hasSize(2);
        }
    }

    @Test
    void revisionAndBodyCapsRejectWithoutMutation() {
        assertThatThrownBy(() -> store.create(scope, body("x".repeat(4001))))
                .hasMessageContaining("上限");
        var first = store.create(scope, body("cap"));
        for (int r = 1; r < 20; r++) store.edit(first.id(), scope, r, body("v" + r));
        assertThatThrownBy(() -> store.edit(first.id(), scope, 20, body("overflow")))
                .hasMessageContaining("20");
        assertThat(store.history(first.id(), scope)).hasSize(20);
    }
}
