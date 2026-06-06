package io.github.stellnula.transport;

import io.github.stellnula.auth.*;
import io.github.stellnula.client.*;
import io.github.stellnula.config.*;
import io.github.stellnula.grpc.*;
import io.github.stellnula.internal.*;
import io.github.stellnula.management.*;
import io.github.stellnula.store.*;
import io.github.stellnula.telemetry.*;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class StellnulaServerFailureTracker {

    private final Duration cooldown;
    private final Map<String, Long> failedUntilNanos = new ConcurrentHashMap<>();

    StellnulaServerFailureTracker(Duration cooldown) {
        this.cooldown = cooldown;
    }

    /** 标记 gRPC 节点进入失败冷却窗口。 */
    void markFailure(URI endpoint) {
        if (endpoint == null) {
            return;
        }
        failedUntilNanos.put(endpointKey(endpoint), System.nanoTime() + cooldown.toNanos());
    }

    /** 清除 gRPC 节点失败状态。 */
    void markSuccess(URI endpoint) {
        if (endpoint != null) {
            failedUntilNanos.remove(endpointKey(endpoint));
        }
    }

    /** 判断 gRPC 节点当前是否可被选择。 */
    boolean isAvailable(URI endpoint) {
        if (endpoint == null) {
            return false;
        }
        String key = endpointKey(endpoint);
        Long failedUntil = failedUntilNanos.get(key);
        if (failedUntil == null) {
            return true;
        }
        if (System.nanoTime() >= failedUntil) {
            failedUntilNanos.remove(key, failedUntil);
            return true;
        }
        return false;
    }

    private static String endpointKey(URI endpoint) {
        return endpoint.normalize().toString();
    }
}
