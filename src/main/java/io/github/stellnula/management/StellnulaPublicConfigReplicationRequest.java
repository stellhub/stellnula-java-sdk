package io.github.stellnula.management;

public record StellnulaPublicConfigReplicationRequest(
        String sourceEnv,
        String sourceRegion,
        String sourceZone,
        String sourceCluster,
        String targetEnv,
        String targetRegion,
        String targetZone,
        String targetCluster,
        String reason) {

    public StellnulaPublicConfigReplicationRequest {
        sourceEnv = requireText(sourceEnv, "sourceEnv");
        targetEnv = requireText(targetEnv, "targetEnv");
        sourceRegion = defaultText(sourceRegion, "default");
        sourceZone = defaultText(sourceZone, "default");
        sourceCluster = defaultText(sourceCluster, "default");
        targetRegion = defaultText(targetRegion, "default");
        targetZone = defaultText(targetZone, "default");
        targetCluster = defaultText(targetCluster, "default");
        reason = defaultText(reason, "public config replicated");
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
