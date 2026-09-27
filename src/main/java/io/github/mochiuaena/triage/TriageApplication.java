package io.github.mochiuaena.triage;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TriageApplication {
    public static void main(String[] args) {
        SpringApplication.run(TriageApplication.class, args);
    }
}
