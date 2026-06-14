package io.github.stellnula.config;

@FunctionalInterface
public interface StellnulaConfigEventListener {

    /** 配置变更事件触发。 */
    void onConfigChanged(StellnulaConfigChangeEvent event);
}
