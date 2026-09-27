package io.github.mochiuaena.triage.settings;

import io.github.mochiuaena.triage.execution.RunFailure;
import io.github.mochiuaena.triage.settings.ProviderConfig.TestResult;
import jakarta.annotation.PreDestroy;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import static org.springframework.http.HttpStatus.*;

@Service
public class ProviderProbe {
    private final ProviderRegistry providers;
    private final ExecutorService workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(4), Thread.ofPlatform().daemon(true).name("provider-test-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    public ProviderProbe(ProviderRegistry providers) { this.providers = providers; }

    public TestResult test(UUID id, long version) {
        var selected = providers.resolve(id);
        if (selected.version() != version) throw ProviderRegistry.error(CONFLICT, "配置已被修改，请刷新后重新测试。");
        long started = System.nanoTime();
        Future<String> pending;
        try {
            pending = workers.submit(() -> selected.client().prompt().user("仅回复 OK。")
                .options(OpenAiChatOptions.builder().maxTokens(32).internalToolExecutionEnabled(false).build()).call().content());
        } catch (RejectedExecutionException e) { throw ProviderRegistry.error(TOO_MANY_REQUESTS, "测试请求较多，请稍后再试。"); }
        try {
            long wait = Math.min(Duration.ofSeconds(10).toMillis(), selected.timeout().toMillis());
            String text = pending.get(wait, TimeUnit.MILLISECONDS);
            return result(text != null && !text.isBlank(), text == null || text.isBlank() ? "模型没有返回文本。" : "连接成功，模型已返回文本。", started);
        } catch (TimeoutException e) {
            pending.cancel(true);
            return result(false, "连接超时，请检查服务是否可用。", started);
        } catch (InterruptedException e) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            return result(false, "测试已中断。", started);
        } catch (ExecutionException e) {
            return result(false, e.getCause() instanceof RunFailure failure ? failure.getMessage() : "请求失败，请检查服务地址、模型名和 API Key。", started);
        }
    }

    private TestResult result(boolean success, String message, long start) {
        return new TestResult(success, message, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    }
    @PreDestroy public void close() { workers.shutdownNow(); }
}
