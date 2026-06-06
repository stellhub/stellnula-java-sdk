package io.github.stellnula.management;

public record StellnulaConfigDeleteRequest(
        String configName,
        String ownerType,
        String ownerId,
        String namespace,
        String group,
        String contentType,
        boolean sensitive,
        String description,
        String env,
        String region,
        String zone,
        String cluster,
        String scopeMode,
        String reason) {

    public StellnulaConfigDeleteRequest {
        ownerId = requireText(ownerId, "ownerId");
        env = requireText(env, "env");
        ownerType = defaultText(ownerType, "APPLICATION");
        namespace = defaultText(namespace, "default");
        group = defaultText(group, "default");
        contentType = defaultText(contentType, "KV");
        region = defaultText(region, "default");
        zone = defaultText(zone, "default");
        cluster = defaultText(cluster, "default");
        scopeMode = defaultText(scopeMode, "EXACT");
        reason = defaultText(reason, "config delete");
    }

    /** 构造应用配置删除请求。 */
    public static StellnulaConfigDeleteRequest applicationKv(String appId, String env) {
        return new StellnulaConfigDeleteRequest(
                "",
                "APPLICATION",
                appId,
                "default",
                "default",
                "KV",
                false,
                "",
                env,
                "default",
                "default",
                "default",
                "EXACT",
                "config delete");
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
