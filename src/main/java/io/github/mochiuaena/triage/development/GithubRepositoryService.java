package io.github.mochiuaena.triage.development;

import io.github.mochiuaena.triage.development.GithubModels.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

@Service
public class GithubRepositoryService {
    private final GithubRepositoryStore store;
    private final GithubClient github;
    private final int refreshSeconds;
    public GithubRepositoryService(GithubRepositoryStore store, GithubClient github,
            @Value("${triage.github.refresh-seconds:30}") int refreshSeconds) {
        this.store = store; this.github = github; this.refreshSeconds = refreshSeconds;
        if (refreshSeconds < 0 || refreshSeconds > 3600) throw new IllegalArgumentException("Invalid GitHub refresh interval");
    }
    public List<Registration> list() { return store.list(); }
    public boolean authenticated() { return github.authenticated(); }
    public synchronized Snapshot create(String repository) {
        Key key = Key.parse(repository);
        if (store.exists(key)) throw new ResponseStatusException(CONFLICT, "这个仓库已经登记，请选择现有仓库。");
        if (store.list().size() >= 10) throw new ResponseStatusException(CONFLICT, "最多登记 10 个仓库，请先移除不再使用的仓库。");
        Health health = github.read(key);
        UUID id = UUID.randomUUID(); store.insert(id, key, health); return snapshot(id);
    }
    public Snapshot snapshot(UUID id) {
        var saved = store.require(id);
        return new Snapshot(saved.registration(), saved.health(), saved.registration().lastError() != null
            || saved.health().checkedAt().isBefore(Instant.now().minusSeconds(120)), saved.registration().lastError());
    }
    public synchronized Snapshot refresh(UUID id) {
        var old = store.require(id);
        if (old.registration().checkedAt().plusSeconds(refreshSeconds).isAfter(Instant.now()))
            throw new ResponseStatusException(TOO_MANY_REQUESTS, "刚刚更新过，请稍后再次读取 GitHub 状态。");
        try { store.update(id, github.read(new Key(old.registration().owner(), old.registration().name()))); }
        catch (ResponseStatusException error) { store.failure(id, error.getReason()); throw error; }
        return snapshot(id);
    }
    public synchronized void delete(UUID id, UUID confirmation) {
        if (!id.equals(confirmation)) throw new ResponseStatusException(BAD_REQUEST, "移除确认与所选仓库不一致。");
        store.require(id); store.delete(id);
    }
}
