package io.github.mochiuaena.triage.execution;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ResponseQuestionScopeTest {
    @Test void latencyValuesAndRequestCountsDoNotTriggerTheResponseStatusGate() {
        assertThat(QuestionScope.responseStatusQuestion("接口延迟 500ms 是为什么？")).isFalse();
        assertThat(QuestionScope.responseStatusQuestion("每分钟 404 次请求时为什么慢？")).isFalse();
    }
    @Test void explicitResponseCodesAndStatusQuestionsTriggerTheResponseStatusGate() {
        for (String question : new String[]{"接口为什么返回 HTTP 404？", "返回404的原因是什么？", "窗口里为什么有5xx？", "响应状态如何？"})
            assertThat(QuestionScope.responseStatusQuestion(question)).as(question).isTrue();
    }
}
