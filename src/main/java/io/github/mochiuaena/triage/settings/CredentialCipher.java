package io.github.mochiuaena.triage.settings;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.*;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

@Component
public class CredentialCipher {
    private final Path keyFile;
    private final SecureRandom random = new SecureRandom();

    public CredentialCipher(@Value("${triage.settings.key-file:./data/model-config.key}") String path) {
        keyFile = Path.of(path).toAbsolutePath().normalize();
    }

    synchronized String encrypt(UUID providerId, String value) {
        return encrypt(providerId, value, true);
    }

    synchronized String encrypt(UUID providerId, String value, boolean allowCreate) {
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(allowCreate), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(providerId.toString().getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, payload, 0, nonce.length);
            System.arraycopy(encrypted, 0, payload, nonce.length, encrypted.length);
            return "v1:" + Base64.getEncoder().encodeToString(payload);
        } catch (Exception e) { throw unavailable(); }
    }

    synchronized String decrypt(UUID providerId, String encoded) {
        try {
            if (!encoded.startsWith("v1:")) throw new IllegalArgumentException();
            byte[] payload = Base64.getDecoder().decode(encoded.substring(3));
            if (payload.length < 29) throw new IllegalArgumentException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, Arrays.copyOf(payload, 12)));
            cipher.updateAAD(providerId.toString().getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(payload, 12, payload.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) { throw unavailable(); }
    }

    private SecretKeySpec key(boolean create) throws Exception {
        if (create && !Files.exists(keyFile)) {
            Files.createDirectories(keyFile.getParent());
            byte[] key = new byte[32];
            random.nextBytes(key);
            try {
                try { Files.createFile(keyFile, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))); }
                catch (UnsupportedOperationException e) { Files.createFile(keyFile); }
                Files.writeString(keyFile, Base64.getEncoder().encodeToString(key), StandardOpenOption.WRITE);
            } catch (FileAlreadyExistsException ignored) { /* Another local instance created it. */ }
        }
        if (Files.size(keyFile) > 256) throw new IllegalArgumentException();
        byte[] bytes = Base64.getDecoder().decode(Files.readString(keyFile).strip());
        if (bytes.length != 32) throw new IllegalArgumentException();
        return new SecretKeySpec(bytes, "AES");
    }

    private ResponseStatusException unavailable() {
        return new ResponseStatusException(SERVICE_UNAVAILABLE, "无法读取本地配置密钥，请恢复密钥文件备份或重新创建受影响的模型配置。");
    }
}
