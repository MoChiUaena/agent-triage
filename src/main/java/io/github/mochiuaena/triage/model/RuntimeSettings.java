package io.github.mochiuaena.triage.model;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("triage")
public record RuntimeSettings(@DefaultValue("DEMO") Mode mode) {
    public enum Mode { DEMO, MODEL }
}
