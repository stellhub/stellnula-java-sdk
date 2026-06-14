package io.github.stellnula.config;

public final class StellnulaConfigPrefixes {

    private StellnulaConfigPrefixes() {}

    public static String normalize(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "";
        }
        String normalized = prefix.trim();
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
