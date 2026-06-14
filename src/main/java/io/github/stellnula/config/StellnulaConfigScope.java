package io.github.stellnula.config;

public record StellnulaConfigScope(String env, String region, String zone, String cluster) {

    public StellnulaConfigScope {
        env = defaultText(env);
        region = defaultText(region);
        zone = defaultText(zone);
        cluster = defaultText(cluster);
    }

    private static String defaultText(String text) {
        return text == null || text.isBlank() ? "default" : text;
    }
}
