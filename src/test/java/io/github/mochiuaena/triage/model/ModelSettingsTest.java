package io.github.mochiuaena.triage.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;

class ModelSettingsTest {
    @Test void modelClientRequiresAnExplicitKey() {
        var settings = new ModelSettings("https://api.deepseek.com", "", "deepseek-chat", Duration.ofSeconds(20), 4, 1600);
        assertThatThrownBy(() -> ModelConfiguration.createClient(settings))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("TRIAGE_MODEL_API_KEY");
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://model.example", "https://user:secret@model.example", "https://model.example?key=secret",
        "https://model.example#secret", "file:///tmp/model", "not-a-url"})
    void invalidEndpointsAreRejectedWithoutEchoingCredentials(String url) {
        var settings = new ModelSettings(url, "test-secret", "test-model", Duration.ofSeconds(20), 4, 1600);
        assertThatThrownBy(settings::requireCredentials).isInstanceOf(IllegalArgumentException.class)
            .hasMessageNotContaining("test-secret").hasMessageNotContaining("key=secret");
    }

    @Test void configurationStringsDoNotExposeKeys() {
        var settings = new ModelSettings("https://api.deepseek.com", "test-secret", "deepseek-chat", Duration.ofSeconds(20), 4, 1600);
        assertThat(settings.toString()).doesNotContain("test-secret");
        assertThatCode(settings::requireCredentials).doesNotThrowAnyException();
    }

    @Test void roundsTimeoutAndOutputSizeHaveUpperBounds() {
        assertThatThrownBy(() -> new ModelSettings("", "", "", Duration.ofSeconds(61), 4, 1600)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelSettings("", "", "", Duration.ofSeconds(20), 9, 1600)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelSettings("", "", "", Duration.ofSeconds(20), 4, 4097)).isInstanceOf(IllegalArgumentException.class);
    }
}
