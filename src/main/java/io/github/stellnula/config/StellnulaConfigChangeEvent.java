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
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public record StellnulaConfigChangeEvent(
        StellnulaConfigChangeSource source,
        StellnulaSnapshot previousSnapshot,
        StellnulaSnapshot currentSnapshot,
        List<StellnulaConfigChange> changes) {

    public StellnulaConfigChangeEvent {
        source = Objects.requireNonNull(source, "source must not be null");
        previousSnapshot =
                previousSnapshot == null ? new StellnulaSnapshot(0, "", List.of()) : previousSnapshot;
        currentSnapshot = Objects.requireNonNull(currentSnapshot, "currentSnapshot must not be null");
        changes = changes == null ? List.of() : List.copyOf(changes);
    }

    /** 返回本次事件影响的配置 key 集合。 */
    public Set<String> changedKeys() {
        return changes.stream()
                .map(change -> change.entry().configKey())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** 判断事件是否包含指定配置。 */
    public boolean containsKey(String key) {
        return changes.stream()
                .anyMatch(
                        change ->
                                key.equals(change.entry().configKey()) || key.equals(change.entry().configId()));
    }

    /** 判断事件是否包含指定前缀的配置。 */
    public boolean containsPrefix(String prefix) {
        String normalized = StellnulaConfigPrefixes.normalize(prefix);
        return changes.stream()
                .anyMatch(
                        change ->
                                change.entry().configKey().equals(normalized)
                                        || change.entry().configKey().startsWith(normalized + ".")
                                        || change.entry().configId().equals(normalized)
                                        || change.entry().configId().startsWith(normalized + "."));
    }
}
