package io.github.stellnula.config;

import io.github.stellnula.auth.*;
import io.github.stellnula.client.*;
import io.github.stellnula.grpc.*;
import io.github.stellnula.internal.*;
import io.github.stellnula.management.*;
import io.github.stellnula.store.*;
import io.github.stellnula.telemetry.*;
import io.github.stellnula.transport.*;
import java.util.List;

@FunctionalInterface
public interface StellnulaConfigListener {

    /** 配置快照变更后触发。 */
    void onConfigChanged(StellnulaSnapshot snapshot, List<StellnulaConfigChange> changes);
}
