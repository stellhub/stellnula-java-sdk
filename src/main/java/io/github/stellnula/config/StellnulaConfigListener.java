package io.github.stellnula.config;

import java.util.List;

@FunctionalInterface
public interface StellnulaConfigListener {

    /** 配置快照变更后触发。 */
    void onConfigChanged(StellnulaSnapshot snapshot, List<StellnulaConfigChange> changes);
}
