package io.github.stellnula.auth;

@FunctionalInterface
public interface StellnulaTokenProvider {

    /** 获取当前访问令牌。 */
    String token();

    /** 创建固定令牌提供器。 */
    static StellnulaTokenProvider fixed(String token) {
        String resolved = token == null ? "" : token;
        return () -> resolved;
    }
}
