package io.github.stellnula.management;

public record StellnulaConfigRequest(
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
        String content,
        String reason) {

    public StellnulaConfigRequest {
        ownerId = requireText(ownerId, "ownerId");
        env = requireText(env, "env");
        content = content == null ? "" : content;
        ownerType = defaultText(ownerType, "APPLICATION");
        namespace = defaultText(namespace, "default");
        group = defaultText(group, "default");
        contentType = defaultText(contentType, "KV");
        region = defaultText(region, "default");
        zone = defaultText(zone, "default");
        cluster = defaultText(cluster, "default");
        scopeMode = defaultText(scopeMode, "EXACT");
        reason = defaultText(reason, "config upsert");
    }

    /** 构造应用配置更新请求。 */
    public static StellnulaConfigRequest applicationKv(
            String appId, String env, String configKey, String configContent) {
        return new StellnulaConfigRequest(
                configKey,
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
                configContent,
                "config upsert");
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
