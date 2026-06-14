package io.github.stellnula.config;

public record StellnulaSubscription(
        String group,
        String subscriptionType,
        String subscriptionKey,
        Long currentRevision,
        String currentChecksum,
        String transport,
        String status) {

    public StellnulaSubscription {
        group = defaultText(group, "default");
        subscriptionType = defaultText(subscriptionType, "ALL").toUpperCase();
        subscriptionKey = defaultText(subscriptionKey, "*");
        currentChecksum = currentChecksum == null ? "" : currentChecksum;
        transport = defaultText(transport, "GRPC").toUpperCase();
        status = defaultText(status, "ACTIVE").toUpperCase();
    }

    /** 订阅全部配置。 */
    public static StellnulaSubscription all() {
        return new StellnulaSubscription("default", "ALL", "*", null, "", "GRPC", "ACTIVE");
    }

    /** 订阅指定配置。 */
    public static StellnulaSubscription config(String group, String configId) {
        return new StellnulaSubscription(group, "CONFIG", configId, null, "", "GRPC", "ACTIVE");
    }

    private static String defaultText(String text, String defaultValue) {
        return text == null || text.isBlank() ? defaultValue : text;
    }
}
