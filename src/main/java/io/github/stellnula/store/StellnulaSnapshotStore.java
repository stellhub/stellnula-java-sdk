package io.github.stellnula.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.stellnula.config.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

public final class StellnulaSnapshotStore {

    private final Path snapshotFile;
    private final ObjectMapper objectMapper;

    public StellnulaSnapshotStore(Path snapshotFile, ObjectMapper objectMapper) {
        this.snapshotFile = snapshotFile;
        this.objectMapper = objectMapper;
    }

    /** 读取本地快照文件。 */
    public Optional<StellnulaSnapshot> load() throws IOException {
        if (snapshotFile == null || !Files.exists(snapshotFile)) {
            return Optional.empty();
        }
        try {
            SnapshotFile snapshot = objectMapper.readValue(snapshotFile.toFile(), SnapshotFile.class);
            if (snapshot.snapshot() == null || !snapshot.snapshot().checksumMatches()) {
                quarantine("checksum");
                return Optional.empty();
            }
            return Optional.of(snapshot.snapshot());
        } catch (IOException ex) {
            try {
                StellnulaSnapshot legacy =
                        objectMapper.readValue(snapshotFile.toFile(), StellnulaSnapshot.class);
                if (!legacy.checksumMatches()) {
                    quarantine("checksum");
                    return Optional.empty();
                }
                return Optional.of(legacy);
            } catch (IOException legacyEx) {
                quarantine("invalid");
                return Optional.empty();
            }
        }
    }

    /** 原子保存本地快照文件。 */
    public void save(StellnulaSnapshot snapshot) throws IOException {
        if (snapshotFile == null) {
            return;
        }
        Path parent = snapshotFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary =
                parent == null
                        ? Files.createTempFile("config-snapshot-", ".tmp")
                        : Files.createTempFile(parent, "config-snapshot-", ".tmp");
        try {
            objectMapper
                    .writerWithDefaultPrettyPrinter()
                    .writeValue(
                            temporary.toFile(), new SnapshotFile(1, OffsetDateTime.now().toString(), snapshot));
            Files.move(
                    temporary,
                    snapshotFile,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            Files.deleteIfExists(temporary);
            throw ex;
        }
    }

    private void quarantine(String reason) throws IOException {
        if (snapshotFile == null || !Files.exists(snapshotFile)) {
            return;
        }
        String timestamp =
                DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").format(java.time.LocalDateTime.now());
        Path target =
                snapshotFile.resolveSibling(
                        snapshotFile.getFileName() + "." + reason + "." + timestamp + ".corrupt");
        Files.move(snapshotFile, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private record SnapshotFile(int schemaVersion, String savedAt, StellnulaSnapshot snapshot) {}
}
