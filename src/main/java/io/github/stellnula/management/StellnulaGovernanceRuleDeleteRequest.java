package io.github.stellnula.management;

public record StellnulaGovernanceRuleDeleteRequest(
        String ownerId,
        String ownerType,
        String env,
        String region,
        String zone,
        String cluster,
        String scopeMode,
        String reason) {

    public StellnulaGovernanceRuleDeleteRequest {
        ownerId = requireText(ownerId, "ownerId");
        env = requireText(env, "env");
        ownerType = defaultText(ownerType, "APPLICATION");
        region = defaultText(region, "default");
        zone = defaultText(zone, "default");
        cluster = defaultText(cluster, "default");
        scopeMode = defaultText(scopeMode, "EXACT");
        reason = defaultText(reason, "service governance rule delete");
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
