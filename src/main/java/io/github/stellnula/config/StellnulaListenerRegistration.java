package io.github.stellnula.config;

@FunctionalInterface
public interface StellnulaListenerRegistration extends AutoCloseable {

    /** 取消监听注册。 */
    @Override
    void close();
}
