package io.github.stellnula.config;

@FunctionalInterface
public interface StellnulaConfigChangePredicate {

    /** 判断配置变更是否需要通知。 */
    boolean test(StellnulaConfigChange change);

    /** 匹配全部配置变更。 */
    static StellnulaConfigChangePredicate all() {
        return change -> true;
    }

    /** 匹配指定配置 key 或 configId。 */
    static StellnulaConfigChangePredicate key(String key) {
        return change ->
                key.equals(change.entry().configKey()) || key.equals(change.entry().configId());
    }

    /** 匹配指定配置前缀。 */
    static StellnulaConfigChangePredicate prefix(String prefix) {
        String normalized = StellnulaConfigPrefixes.normalize(prefix);
        return change ->
                change.entry().configKey().equals(normalized)
                        || change.entry().configKey().startsWith(normalized + ".")
                        || change.entry().configId().equals(normalized)
                        || change.entry().configId().startsWith(normalized + ".");
    }
}
