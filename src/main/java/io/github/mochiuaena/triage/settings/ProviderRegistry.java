package io.github.mochiuaena.triage.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mochiuaena.triage.domain.TriageModel.ModelSource;
import io.github.mochiuaena.triage.execution.*;
import io.github.mochiuaena.triage.model.*;
import io.github.mochiuaena.triage.settings.ProviderConfig.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;
import static org.springframework.http.HttpStatus.*;

@Service
public class ProviderRegistry {
    private final ProviderRepository repository;
    private final CredentialCipher cipher;
    private final RuntimeSettings runtime;
    private final ModelSettings environment;
    private final ObjectMapper json;
    private final DemoEngine demo;
    private final TransactionTemplate transaction;
    private final Map<UUID, Resolved> cache = new HashMap<>();
    private TriageEngine environmentEngine;

    public ProviderRegistry(ProviderRepository repository, CredentialCipher cipher, RuntimeSettings runtime,
                            ModelSettings environment, ObjectMapper json, DemoEngine demo, PlatformTransactionManager transactions) {
        this.repository = repository; this.cipher = cipher; this.runtime = runtime; this.environment = environment;
        this.json = json; this.demo = demo; this.transaction = new TransactionTemplate(transactions);
    }

    public synchronized Selection selection() {
        return repository.selection().orElse(new Selection(runtime.mode().name(), null, "ENV"));
    }

    public synchronized List<View> list() {
        Selection selection = selection();
        return repository.list().stream().map(p -> p.view("MODEL".equals(selection.mode()) && p.id().equals(selection.providerId()))).toList();
    }

    public synchronized State state() { return new State(list(), selection()); }

    public synchronized View create(Input input) {
        if (repository.list().size() >= 10) throw error(CONFLICT, "最多保存 10 个模型服务。");
        if (input.apiKey() == null || input.apiKey().isBlank()) throw error(BAD_REQUEST, "新增服务需要填写 API Key。");
        UUID id = UUID.randomUUID();
        String url = normalizeUrl(input.baseUrl());
        validate(input, url, input.apiKey().strip());
        Stored value = stored(id, input, url, cipher.encrypt(id, input.apiKey().strip(), repository.list().isEmpty()), 1, Instant.now());
        transaction.executeWithoutResult(status -> repository.insert(value));
        return value.view(false);
    }

    public synchronized View update(UUID id, Input input) {
        Stored old = find(id);
        if (old.version() != input.version()) throw error(CONFLICT, "配置已被修改，请刷新后重新编辑。");
        String url = normalizeUrl(input.baseUrl());
        boolean replace = input.apiKey() != null && !input.apiKey().isBlank();
        if (!url.equals(old.baseUrl()) && !replace) throw error(BAD_REQUEST, "修改服务地址时请重新填写 API Key。");
        String key = replace ? input.apiKey().strip() : cipher.decrypt(id, old.encryptedKey());
        validate(input, url, key);
        Stored value = stored(id, input, url, replace ? cipher.encrypt(id, key, false) : old.encryptedKey(), old.version() + 1, old.createdAt());
        transaction.executeWithoutResult(status -> {
            if (!repository.update(value, input.version())) throw error(CONFLICT, "配置已被修改，请刷新后重新编辑。");
        });
        cache.remove(id);
        return value.view(id.equals(selection().providerId()) && "MODEL".equals(selection().mode()));
    }

    public synchronized void delete(UUID id, long version) {
        Stored old = find(id);
        if (old.version() != version) throw error(CONFLICT, "配置已被修改，请刷新后再删除。");
        if (id.equals(selection().providerId())) throw error(CONFLICT, "请先切换到演示模式或其他模型，再删除当前服务。");
        transaction.executeWithoutResult(status -> repository.delete(id));
        cache.remove(id);
    }

    public synchronized Selection select(String mode, UUID providerId) {
        if (!Set.of("MODEL", "DEMO").contains(mode == null ? "" : mode)) throw error(BAD_REQUEST, "运行模式必须为 MODEL 或 DEMO。");
        if ("MODEL".equals(mode)) {
            if (providerId == null) throw error(BAD_REQUEST, "请选择一个模型服务。");
            resolve(providerId); // Validate and decrypt before changing the active selection. No network request.
        } else if (providerId != null) throw error(BAD_REQUEST, "演示模式不需要模型服务。");
        transaction.executeWithoutResult(status -> repository.select(mode, providerId));
        return selection();
    }

    public synchronized TriageEngine current() {
        Selection selected = selection();
        if ("DEMO".equals(selected.mode())) return demo;
        if (selected.providerId() != null) return resolve(selected.providerId()).engine();
        if (environmentEngine == null) {
            try { environmentEngine = new ModelEngine(ModelConfiguration.createClient(environment), environment, json); }
            catch (IllegalArgumentException e) { throw error(SERVICE_UNAVAILABLE, "环境变量中的模型配置不可用，请进入模型设置添加服务。"); }
        }
        return environmentEngine;
    }

    synchronized Resolved resolve(UUID id) {
        Stored stored = find(id);
        Resolved previous = cache.get(id);
        if (previous != null && previous.version() == stored.version()) return previous;
        ModelSettings settings = stored.settings(cipher.decrypt(id, stored.encryptedKey()));
        ChatClient client;
        boolean disableThinking = stored.protocol() == Protocol.DEEPSEEK ||
            (stored.protocol() == Protocol.KIMI && stored.model().equals("kimi-k2.6"));
        try { client = ModelConfiguration.createClient(settings, disableThinking, stored.temperature()); }
        catch (IllegalArgumentException e) { throw error(BAD_REQUEST, "模型配置无效，请检查服务地址、模型名和 Key。"); }
        var model = new ModelEngine(client, settings, json);
        ModelSource source = new ModelSource(id, stored.displayName(), stored.version());
        TriageEngine selected = new TriageEngine() {
            public String mode() { return "MODEL"; }
            public String modelName() { return model.modelName(); }
            public ModelSource source() { return source; }
            public Decision investigate(ExecutionSession session) { return model.investigate(session); }
        };
        Resolved resolved = new Resolved(stored.version(), selected, client, settings.timeout());
        cache.put(id, resolved);
        return resolved;
    }

    private Stored find(UUID id) { return repository.find(id).orElseThrow(() -> error(NOT_FOUND, "模型服务不存在。")); }

    private String normalizeUrl(String url) {
        String normalized = url.strip().replaceAll("/+$", "");
        if (normalized.endsWith("/chat/completions")) throw error(BAD_REQUEST, "请填写 Base URL，不要包含 /chat/completions。");
        return normalized;
    }

    private void validate(Input input, String url, String key) {
        try {
            if (!Double.isFinite(input.temperature()) || input.temperature() < 0 || input.temperature() > 2
                || key.length() > 4096 || key.chars().anyMatch(c -> c < 33 || c > 126)) throw new IllegalArgumentException();
            if (input.protocol() == Protocol.KIMI && input.model().strip().equals("kimi-k2.6")
                && Double.compare(input.temperature(), 0.6) != 0)
                throw error(BAD_REQUEST, "Kimi K2.6 非思考模式的温度必须为 0.6。");
            new ModelSettings(url, key, input.model().strip(), java.time.Duration.ofSeconds(input.timeoutSeconds()), input.maxRounds(), input.maxTokens())
                .requireCredentials();
        } catch (org.springframework.web.server.ResponseStatusException e) { throw e; }
        catch (IllegalArgumentException e) { throw error(BAD_REQUEST, "模型配置无效。请检查模型名和参数；地址须为 HTTPS 或本机 HTTP，不能包含凭据或查询参数。"); }
    }

    private Stored stored(UUID id, Input p, String url, String encrypted, long version, Instant createdAt) {
        return new Stored(id, p.displayName().strip(), p.protocol(), url, p.model().strip(), encrypted, p.temperature(),
            p.timeoutSeconds(), p.maxRounds(), p.maxTokens(), version, createdAt);
    }

    static ResponseStatusException error(org.springframework.http.HttpStatus status, String text) { return new ResponseStatusException(status, text); }
    record Resolved(long version, TriageEngine engine, ChatClient client, java.time.Duration timeout) {
        @Override public String toString() { return "ResolvedProvider[version=" + version + "]"; }
    }
}
