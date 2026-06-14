package io.github.stellnula.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.stellnula.config.StellnulaConfigEntry;
import io.github.stellnula.config.StellnulaConfigScope;
import io.github.stellnula.config.StellnulaSnapshot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public final class StellnulaSnapshotStore {

    private static final String CONFIG_DIRECTORY = "configs";
    private static final String METADATA_FILE = ".stellnula-snapshot.json";
    private static final String LEGACY_SNAPSHOT_FILE = "config-snapshot.json";
    private static final Pattern UNSAFE_FILE_CHARS = Pattern.compile("[\\p{Cntrl}<>:\"|?*]");

    private final Path snapshotDirectory;
    private final Path legacySnapshotFile;
    private final ObjectMapper objectMapper;

    public StellnulaSnapshotStore(Path snapshotDirectory, ObjectMapper objectMapper) {
        this.snapshotDirectory = toSnapshotDirectory(snapshotDirectory);
        this.legacySnapshotFile = toLegacySnapshotFile(snapshotDirectory, this.snapshotDirectory);
        this.objectMapper = objectMapper;
    }

    /** 读取本地快照目录。 */
    public Optional<StellnulaSnapshot> load() throws IOException {
        if (snapshotDirectory == null) {
            return Optional.empty();
        }
        Path metadataFile = metadataFile();
        if (Files.exists(metadataFile)) {
            return loadDirectorySnapshot(metadataFile);
        }
        if (legacySnapshotFile != null && Files.exists(legacySnapshotFile)) {
            return loadLegacySnapshot(legacySnapshotFile);
        }
        return Optional.empty();
    }

    /** 原子保存本地快照目录。 */
    public void save(StellnulaSnapshot snapshot) throws IOException {
        if (snapshotDirectory == null) {
            return;
        }
        Files.createDirectories(configDirectory());
        Set<String> previousFiles = loadManagedConfigFiles();
        Set<String> currentFiles = new LinkedHashSet<>();
        List<EntryMetadata> entries = new ArrayList<>();
        for (StellnulaConfigEntry entry : snapshot.entries()) {
            String relativePath = relativeConfigPath(entry, currentFiles);
            Path target = configFile(relativePath);
            writeStringAtomic(target, entry.configValue());
            entries.add(EntryMetadata.from(entry, relativePath));
        }
        DirectorySnapshotFile snapshotFile =
                new DirectorySnapshotFile(
                        2, OffsetDateTime.now().toString(), snapshot.revision(), snapshot.checksum(), entries);
        writeJsonAtomic(metadataFile(), snapshotFile);
        cleanupStaleConfigFiles(previousFiles, currentFiles);
    }

    private Optional<StellnulaSnapshot> loadDirectorySnapshot(Path metadataFile) throws IOException {
        try {
            DirectorySnapshotFile snapshotFile =
                    objectMapper.readValue(metadataFile.toFile(), DirectorySnapshotFile.class);
            List<StellnulaConfigEntry> entries = new ArrayList<>();
            for (EntryMetadata entry : snapshotFile.entries()) {
                String configContent = Files.readString(configFile(entry.path()), StandardCharsets.UTF_8);
                entries.add(entry.toEntry(configContent));
            }
            StellnulaSnapshot snapshot =
                    new StellnulaSnapshot(snapshotFile.revision(), snapshotFile.checksum(), entries);
            if (!snapshot.checksumMatches()) {
                quarantine(metadataFile, "checksum");
                return Optional.empty();
            }
            return Optional.of(snapshot);
        } catch (IOException | RuntimeException ex) {
            quarantine(metadataFile, "invalid");
            return Optional.empty();
        }
    }

    private Optional<StellnulaSnapshot> loadLegacySnapshot(Path legacyFile) throws IOException {
        try {
            LegacySnapshotFile snapshot =
                    objectMapper.readValue(legacyFile.toFile(), LegacySnapshotFile.class);
            if (snapshot.snapshot() == null || !snapshot.snapshot().checksumMatches()) {
                quarantine(legacyFile, "checksum");
                return Optional.empty();
            }
            return Optional.of(snapshot.snapshot());
        } catch (IOException ex) {
            try {
                StellnulaSnapshot legacy =
                        objectMapper.readValue(legacyFile.toFile(), StellnulaSnapshot.class);
                if (!legacy.checksumMatches()) {
                    quarantine(legacyFile, "checksum");
                    return Optional.empty();
                }
                return Optional.of(legacy);
            } catch (IOException legacyEx) {
                quarantine(legacyFile, "invalid");
                return Optional.empty();
            }
        }
    }

    private void writeStringAtomic(Path target, String configContent) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary =
                parent == null
                        ? Files.createTempFile("stellnula-config-", ".tmp")
                        : Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
        try {
            Files.writeString(
                    temporary, configContent == null ? "" : configContent, StandardCharsets.UTF_8);
            moveReplacing(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void writeJsonAtomic(Path target, DirectorySnapshotFile snapshotFile) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary =
                parent == null
                        ? Files.createTempFile("stellnula-snapshot-", ".tmp")
                        : Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), snapshotFile);
            moveReplacing(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(
                    source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Set<String> loadManagedConfigFiles() {
        Path metadataFile = metadataFile();
        if (!Files.exists(metadataFile)) {
            return Set.of();
        }
        try {
            DirectorySnapshotFile snapshotFile =
                    objectMapper.readValue(metadataFile.toFile(), DirectorySnapshotFile.class);
            Set<String> paths = new LinkedHashSet<>();
            for (EntryMetadata entry : snapshotFile.entries()) {
                paths.add(entry.path());
            }
            return paths;
        } catch (IOException | RuntimeException ex) {
            return Set.of();
        }
    }

    private void cleanupStaleConfigFiles(Set<String> previousFiles, Set<String> currentFiles) {
        for (String previousFile : previousFiles) {
            if (currentFiles.contains(previousFile)) {
                continue;
            }
            try {
                Files.deleteIfExists(configFile(previousFile));
            } catch (IOException ignored) {
                // Cleanup failures must not break a successful snapshot write.
            }
        }
    }

    private String relativeConfigPath(StellnulaConfigEntry entry, Set<String> usedPaths) {
        String readablePath = readablePath(entry.configKey());
        String relativePath =
                readablePath.isBlank() || usedPaths.contains(readablePath)
                        ? "by-id/" + encodeSegment(entry.configId())
                        : readablePath;
        int suffix = 1;
        String candidate = relativePath;
        while (usedPaths.contains(candidate)) {
            candidate = relativePath + "-" + suffix++;
        }
        usedPaths.add(candidate);
        return candidate;
    }

    private String readablePath(String configKey) {
        if (configKey == null || configKey.isBlank()) {
            return "";
        }
        String normalized = configKey.trim().replace('\\', '/');
        if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*")) {
            return "";
        }
        List<String> segments = new ArrayList<>();
        for (String segment : normalized.split("/+")) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                return "";
            }
            String sanitized = sanitizeSegment(segment);
            if (sanitized.isBlank()) {
                return "";
            }
            segments.add(sanitized);
        }
        return String.join("/", segments);
    }

    private String sanitizeSegment(String segment) {
        return UNSAFE_FILE_CHARS.matcher(segment.trim()).replaceAll("_");
    }

    private String encodeSegment(String text) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private Path configFile(String relativePath) throws IOException {
        Path root = configDirectory().toAbsolutePath().normalize();
        Path target = root.resolve(relativePath).normalize();
        if (!target.startsWith(root)) {
            throw new IOException("config snapshot path escapes snapshot directory: " + relativePath);
        }
        return target;
    }

    private Path metadataFile() {
        return snapshotDirectory.resolve(METADATA_FILE);
    }

    private Path configDirectory() {
        return snapshotDirectory.resolve(CONFIG_DIRECTORY);
    }

    private void quarantine(Path target, String reason) throws IOException {
        if (target == null || !Files.exists(target)) {
            return;
        }
        String timestamp =
                DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").format(java.time.LocalDateTime.now());
        Path corruptFile =
                target.resolveSibling(target.getFileName() + "." + reason + "." + timestamp + ".corrupt");
        Files.move(target, corruptFile, StandardCopyOption.REPLACE_EXISTING);
    }

    private static Path toSnapshotDirectory(Path snapshotPath) {
        if (snapshotPath == null) {
            return null;
        }
        if (looksLikeLegacyFile(snapshotPath)) {
            Path parent = snapshotPath.getParent();
            return parent == null ? Path.of(".") : parent;
        }
        return snapshotPath;
    }

    private static Path toLegacySnapshotFile(Path snapshotPath, Path snapshotDirectory) {
        if (snapshotPath == null || snapshotDirectory == null) {
            return null;
        }
        if (looksLikeLegacyFile(snapshotPath)) {
            return snapshotPath;
        }
        return snapshotDirectory.resolve(LEGACY_SNAPSHOT_FILE);
    }

    private static boolean looksLikeLegacyFile(Path snapshotPath) {
        Path fileName = snapshotPath.getFileName();
        return fileName != null && fileName.toString().endsWith(".json");
    }

    private record DirectorySnapshotFile(
            int schemaVersion,
            String savedAt,
            long revision,
            String checksum,
            List<EntryMetadata> entries) {

        private DirectorySnapshotFile {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }
    }

    private record EntryMetadata(
            String path,
            String configId,
            String configKey,
            String contentType,
            long version,
            long revision,
            boolean encrypted,
            boolean deleted,
            String matchedType,
            Long matchedGrayId,
            String matchedGrayName,
            Long grayVersion,
            String valueEncoding,
            String deliveryMode,
            int valueSizeBytes,
            String valueRef,
            StellnulaConfigScope scope) {

        private static EntryMetadata from(StellnulaConfigEntry entry, String path) {
            return new EntryMetadata(
                    path,
                    entry.configId(),
                    entry.configKey(),
                    entry.contentType(),
                    entry.version(),
                    entry.revision(),
                    entry.encrypted(),
                    entry.deleted(),
                    entry.matchedType(),
                    entry.matchedGrayId(),
                    entry.matchedGrayName(),
                    entry.grayVersion(),
                    entry.valueEncoding(),
                    entry.deliveryMode(),
                    entry.valueSizeBytes(),
                    entry.valueRef(),
                    entry.scope());
        }

        private StellnulaConfigEntry toEntry(String configContent) {
            return new StellnulaConfigEntry(
                    configId,
                    configKey,
                    contentType,
                    configContent,
                    version,
                    revision,
                    encrypted,
                    deleted,
                    matchedType,
                    matchedGrayId,
                    matchedGrayName,
                    grayVersion,
                    valueEncoding,
                    deliveryMode,
                    valueSizeBytes,
                    valueRef,
                    scope);
        }
    }

    private record LegacySnapshotFile(
            int schemaVersion, String savedAt, StellnulaSnapshot snapshot) {}
}
