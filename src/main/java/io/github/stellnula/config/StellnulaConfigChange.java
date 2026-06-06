package io.github.stellnula.config;

import io.github.stellnula.auth.*;
import io.github.stellnula.client.*;
import io.github.stellnula.grpc.*;
import io.github.stellnula.internal.*;
import io.github.stellnula.management.*;
import io.github.stellnula.store.*;
import io.github.stellnula.telemetry.*;
import io.github.stellnula.transport.*;
import java.util.Objects;

public record StellnulaConfigChange(
        StellnulaChangeType type, StellnulaConfigEntry entry, StellnulaConfigEntry previousEntry) {

    public StellnulaConfigChange(StellnulaChangeType type, StellnulaConfigEntry entry) {
        this(type, entry, null);
    }

    public StellnulaConfigChange {
        type = Objects.requireNonNull(type, "type must not be null");
        entry = Objects.requireNonNull(entry, "entry must not be null");
    }

    /** 返回变更后的配置值。 */
    public String currentValue() {
        return type == StellnulaChangeType.DELETE ? null : entry.configValue();
    }

    /** 返回变更前的配置值。 */
    public String previousValue() {
        return previousEntry == null ? null : previousEntry.configValue();
    }
}
