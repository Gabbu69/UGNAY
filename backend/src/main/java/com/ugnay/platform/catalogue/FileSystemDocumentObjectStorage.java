package com.ugnay.platform.catalogue;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/** Private, immutable-by-convention object storage for the single-machine Windows profile. */
@Component
@Profile("lite")
final class FileSystemDocumentObjectStorage implements DocumentObjectStorage {
    private final Path root;

    FileSystemDocumentObjectStorage(@Value("${ugnay.storage.filesystem-root}") String configuredRoot) throws IOException {
        root = Path.of(configuredRoot).toAbsolutePath().normalize();
        Files.createDirectories(root);
    }

    @Override
    public StoredObject store(String objectKey, Path source, String contentType, Map<String, String> metadata) throws IOException {
        Path target = resolve(objectKey);
        Files.createDirectories(target.getParent());
        if (Files.exists(target)) {
            throw new IOException("The randomized document object key already exists; the existing version was not overwritten.");
        }
        Path staged = Files.createTempFile(target.getParent(), ".ugnay-stage-", ".tmp");
        try {
            Files.copy(source, staged, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(staged, target);
            }
            return new StoredObject(objectKey, sha256(target));
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    @Override
    public InputStream open(String objectKey) throws IOException {
        Path target = resolve(objectKey);
        if (!Files.isRegularFile(target)) throw new IOException("The stored document version is unavailable.");
        return Files.newInputStream(target);
    }

    private Path resolve(String objectKey) throws IOException {
        if (objectKey == null || !objectKey.matches("[A-Za-z0-9/_-]{8,240}")) {
            throw new IOException("Document object key is invalid.");
        }
        Path target = root.resolve(objectKey).normalize();
        if (!target.startsWith(root)) throw new IOException("Document object key escapes the private storage root.");
        return target;
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new DigestInputStream(Files.newInputStream(path), digest)) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
