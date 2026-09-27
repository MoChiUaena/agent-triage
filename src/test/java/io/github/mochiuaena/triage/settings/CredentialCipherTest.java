package io.github.mochiuaena.triage.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class CredentialCipherTest {
    @TempDir Path folder;
    @Test void encryptedKeysSurviveRecreationAndUseDifferentNonces() throws Exception {
        Path path = folder.resolve("key");
        var cipher = new CredentialCipher(path.toString());
        UUID id = UUID.randomUUID();
        String first = cipher.encrypt(id, "test-provider-secret");
        String second = cipher.encrypt(id, "test-provider-secret");
        assertThat(first).isNotEqualTo(second).doesNotContain("test-provider-secret");
        assertThat(Files.readString(path)).doesNotContain("test-provider-secret");
        assertThat(new CredentialCipher(path.toString()).decrypt(id, first)).isEqualTo("test-provider-secret");
    }

    @Test void ciphertextCannotBeMovedToAnotherProvider() {
        var cipher = new CredentialCipher(folder.resolve("key").toString());
        String encrypted = cipher.encrypt(UUID.randomUUID(), "test-provider-secret");
        assertThatThrownBy(() -> cipher.decrypt(UUID.randomUUID(), encrypted)).isInstanceOf(ResponseStatusException.class);
    }

    @Test void missingKeyIsNotRegeneratedWhileDecryptingOrReplacingExistingCredentials() throws Exception {
        Path path = folder.resolve("key");
        var cipher = new CredentialCipher(path.toString());
        UUID id = UUID.randomUUID();
        String encrypted = cipher.encrypt(id, "test-provider-secret");
        Files.delete(path);
        assertThatThrownBy(() -> cipher.decrypt(id, encrypted)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> cipher.encrypt(id, "replacement", false)).isInstanceOf(ResponseStatusException.class);
        assertThat(path).doesNotExist();
    }
}
