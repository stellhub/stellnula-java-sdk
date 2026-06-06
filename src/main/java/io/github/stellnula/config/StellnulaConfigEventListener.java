package io.github.stellnula.config;

import io.github.stellnula.auth.*;
import io.github.stellnula.client.*;
import io.github.stellnula.grpc.*;
import io.github.stellnula.internal.*;
import io.github.stellnula.management.*;
import io.github.stellnula.store.*;
import io.github.stellnula.telemetry.*;
import io.github.stellnula.transport.*;

@FunctionalInterface
public interface StellnulaConfigEventListener {

    /** 配置变更事件触发。 */
    void onConfigChanged(StellnulaConfigChangeEvent event);
}
