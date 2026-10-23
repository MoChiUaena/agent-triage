package io.github.mochiuaena.triage.knowledge;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static io.github.mochiuaena.triage.knowledge.KnowledgeModels.*;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;

class KnowledgeSearchServiceTest {
    @Test void unavailableStorageDoesNotBecomeAnEmptyMatchOrExposeDatabaseDetails() {
        // An unreachable database is an external failure; mock only that boundary.
        var store = mock(KnowledgeRepository.class);
        var knowledge = mock(KnowledgeService.class);
        var scope = new Scope("PROJECT", UUID.randomUUID());
        when(knowledge.scope(scope.kind(), scope.id())).thenReturn(scope);
        when(store.searchCount(scope, "ALL"))
            .thenThrow(new DataAccessResourceFailureException("private-db-host/password"));
        var search = new KnowledgeSearchService(store, knowledge);
        assertThatThrownBy(() -> search.search(scope, "needle", "ALL", null, 10))
            .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                assertThat(e.getStatusCode().value()).isEqualTo(503);
                assertThat(e.getReason()).contains("存储").doesNotContain("private-db-host", "password");
            });
    }
}
