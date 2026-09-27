package io.github.mochiuaena.triage;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:bootstrap;DB_CLOSE_DELAY=-1")
class TriageApplicationTest {
    @Test
    void contextLoadsWithoutModelCredentials() {
    }
}
