package io.github.stellnula.management;

public record StellnulaGrayRuleRequest(
        String env,
        String region,
        String zone,
        String cluster,
        String ruleType,
        String grayRules,
        String configValue,
        int priority,
        String status,
        String startTime,
        String endTime,
        String reason) {

    public StellnulaGrayRuleRequest {
        env = requireText(env, "env");
        ruleType = requireText(ruleType, "ruleType");
        grayRules = requireText(grayRules, "grayRules");
        configValue = requireText(configValue, "configValue");
        region = defaultText(region, "default");
        zone = defaultText(zone, "default");
        cluster = defaultText(cluster, "default");
        status = defaultText(status, "ACTIVE");
        reason = defaultText(reason, "gray rule upsert");
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
