package io.github.stellnula.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

public final class StellnulaChecksum {

    private StellnulaChecksum() {}

    /** 按服务端快照算法计算配置集合 checksum。 */
    public static String calculate(List<StellnulaConfigEntry> entries) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            entries.stream()
                    .sorted(Comparator.comparing(StellnulaConfigEntry::configKey))
                    .forEach(entry -> update(digest, entry));
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    public static boolean matches(String expected, List<StellnulaConfigEntry> entries) {
        return expected == null || expected.isBlank() || expected.equals(calculate(entries));
    }

    private static void update(MessageDigest digest, StellnulaConfigEntry entry) {
        update(digest, entry.configId());
        update(digest, entry.configKey());
        update(digest, entry.configValue());
        update(digest, Long.toString(entry.version()));
        update(digest, Long.toString(entry.revision()));
        update(digest, entry.matchedType());
        update(digest, entry.matchedGrayName());
    }

    private static void update(MessageDigest digest, String text) {
        digest.update((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }
}
