package io.github.stellnula.management;

public record StellnulaGrayRuleEndRequest(
        String env,
        String region,
        String zone,
        String cluster,
        String ruleType,
        String grayRules,
        String configValue,
        int priority,
        String endTime,
        String reason) {

    public StellnulaGrayRuleEndRequest {
        env = requireText(env, "env");
        region = defaultText(region, "default");
        zone = defaultText(zone, "default");
        cluster = defaultText(cluster, "default");
        reason = defaultText(reason, "gray rule end");
    }

    private static String requireText(String text, String fieldName) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return text;
    }

    private static String defaultText(String text, String defaultValue) {
        return text == null || text.isBlank() ? defaultValue : text;
    }
}
