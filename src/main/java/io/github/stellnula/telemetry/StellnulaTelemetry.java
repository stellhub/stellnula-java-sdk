package io.github.stellnula.telemetry;

import io.github.stellnula.client.*;
import io.github.stellnula.config.*;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.ObservableLongGauge;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class StellnulaTelemetry implements AutoCloseable {

    private static final AttributeKey<String> SERVICE_NAME = AttributeKey.stringKey("service.name");
    private static final AttributeKey<String> SERVICE_NAMESPACE =
            AttributeKey.stringKey("service.namespace");
    private static final AttributeKey<String> SERVICE_VERSION =
            AttributeKey.stringKey("service.version");
    private static final AttributeKey<String> SERVICE_INSTANCE_ID =
            AttributeKey.stringKey("service.instance.id");
    private static final AttributeKey<String> DEPLOYMENT_ENVIRONMENT_NAME =
            AttributeKey.stringKey("deployment.environment.name");
    private static final AttributeKey<String> K8S_CLUSTER_NAME =
            AttributeKey.stringKey("k8s.cluster.name");
    private static final AttributeKey<String> CLOUD_REGION = AttributeKey.stringKey("cloud.region");
    private static final AttributeKey<String> CLOUD_AVAILABILITY_ZONE =
            AttributeKey.stringKey("cloud.availability_zone");
    private static final AttributeKey<String> STELLAR_IDC = AttributeKey.stringKey("stellar.idc");
    private static final AttributeKey<String> HOST_NAME = AttributeKey.stringKey("host.name");
    private static final AttributeKey<String> CONFIG_NAMESPACE =
            AttributeKey.stringKey("stellnula.config.namespace");
    private static final AttributeKey<String> CONFIG_GROUP =
            AttributeKey.stringKey("stellnula.config.group");
    private static final AttributeKey<String> OPERATION = AttributeKey.stringKey("operation");
    private static final AttributeKey<String> TRANSPORT = AttributeKey.stringKey("transport");
    private static final AttributeKey<String> RESULT = AttributeKey.stringKey("result");
    private static final AttributeKey<String> ERROR_CODE = AttributeKey.stringKey("error_code");
    private static final AttributeKey<String> SOURCE = AttributeKey.stringKey("source");
    private static final AttributeKey<String> CHANGE_TYPE = AttributeKey.stringKey("change_type");
    private static final AttributeKey<String> LISTENER_TYPE = AttributeKey.stringKey("listener_type");

    private final Attributes baseAttributes;
    private final LongCounter operationCounter;
    private final DoubleHistogram operationDuration;
    private final LongCounter errorCounter;
    private final LongCounter configChangeCounter;
    private final LongCounter snapshotCounter;
    private final DoubleHistogram snapshotDuration;
    private final LongCounter listenerCounter;
    private final ObservableLongGauge revisionGauge;
    private final ObservableLongGauge entriesGauge;
    private final AtomicLong currentRevision = new AtomicLong();
    private final AtomicLong currentEntries = new AtomicLong();

    public StellnulaTelemetry(StellnulaClientOptions options) {
        this(options, options.openTelemetry());
    }

    public StellnulaTelemetry(StellnulaClientOptions options, OpenTelemetry openTelemetry) {
        this(options, openTelemetry, System.getenv());
    }

    StellnulaTelemetry(
            StellnulaClientOptions options, OpenTelemetry openTelemetry, Map<String, String> env) {
        Objects.requireNonNull(options, "options must not be null");
        Objects.requireNonNull(openTelemetry, "openTelemetry must not be null");
        Meter meter = openTelemetry.getMeter("io.github.stellnula.sdk");
        this.baseAttributes = resolveBaseAttributes(options, env);
        this.operationCounter =
                meter
                        .counterBuilder("stellnula.client.operations")
                        .setDescription("Stellnula client operation count")
                        .setUnit("1")
                        .build();
        this.operationDuration =
                meter
                        .histogramBuilder("stellnula.client.operation.duration")
                        .setDescription("Stellnula client operation duration")
                        .setUnit("ms")
                        .build();
        this.errorCounter =
                meter
                        .counterBuilder("stellnula.client.errors")
                        .setDescription("Stellnula client error count")
                        .setUnit("1")
                        .build();
        this.configChangeCounter =
                meter
                        .counterBuilder("stellnula.client.config.changes")
                        .setDescription("Stellnula config change count")
                        .setUnit("1")
                        .build();
        this.snapshotCounter =
                meter
                        .counterBuilder("stellnula.client.snapshot.operations")
                        .setDescription("Stellnula local snapshot operation count")
                        .setUnit("1")
                        .build();
        this.snapshotDuration =
                meter
                        .histogramBuilder("stellnula.client.snapshot.operation.duration")
                        .setDescription("Stellnula local snapshot operation duration")
                        .setUnit("ms")
                        .build();
        this.listenerCounter =
                meter
                        .counterBuilder("stellnula.client.listener.notifications")
                        .setDescription("Stellnula listener notification count")
                        .setUnit("1")
                        .build();
        this.revisionGauge =
                meter
                        .gaugeBuilder("stellnula.client.revision")
                        .ofLongs()
                        .setDescription("Current Stellnula local config revision")
                        .setUnit("1")
                        .buildWithCallback(
                                measurement -> measurement.record(currentRevision.get(), baseAttributes));
        this.entriesGauge =
                meter
                        .gaugeBuilder("stellnula.client.config.entries")
                        .ofLongs()
                        .setDescription("Current Stellnula local config entry count")
                        .setUnit("1")
                        .buildWithCallback(
                                measurement -> measurement.record(currentEntries.get(), baseAttributes));
    }

    /** 记录客户端操作。 */
    public void recordOperation(
            String operation, String transport, String result, long startedNanos) {
        Attributes attributes = withBase(OPERATION, operation, TRANSPORT, transport, RESULT, result);
        operationCounter.add(1, attributes);
        operationDuration.record(elapsedMillis(startedNanos), attributes);
    }

    /** 记录客户端错误。 */
    public void recordError(String operation, String transport, Throwable error) {
        String errorCode = errorCode(error);
        errorCounter.add(
                1, withBase(OPERATION, operation, TRANSPORT, transport, ERROR_CODE, errorCode));
    }

    /** 记录本地快照操作。 */
    public void recordSnapshotOperation(String operation, String result, long startedNanos) {
        Attributes attributes = withBase(OPERATION, operation, RESULT, result);
        snapshotCounter.add(1, attributes);
        snapshotDuration.record(elapsedMillis(startedNanos), attributes);
    }

    /** 更新当前快照指标。 */
    public void recordSnapshot(StellnulaSnapshot snapshot) {
        currentRevision.set(snapshot.revision());
        currentEntries.set(snapshot.entries().size());
    }

    /** 记录配置变更。 */
    public void recordConfigChanges(
            StellnulaConfigChangeSource source, Iterable<StellnulaConfigChange> changes) {
        for (StellnulaConfigChange change : changes) {
            configChangeCounter.add(
                    1,
                    withBase(
                            SOURCE,
                            source.name().toLowerCase(Locale.ROOT),
                            CHANGE_TYPE,
                            change.type().name().toLowerCase(Locale.ROOT)));
        }
    }

    /** 记录监听器通知。 */
    public void recordListenerNotification(String listenerType, String result) {
        listenerCounter.add(1, withBase(LISTENER_TYPE, listenerType, RESULT, result));
    }

    /** 按 stellflux OpenTelemetry 资源规范解析基础指标维度。 */
    static Attributes resolveBaseAttributes(StellnulaClientOptions options, Map<String, String> env) {
        Map<String, String> resolvedEnv = env == null ? Map.of() : env;
        Map<String, String> otelResourceAttributes =
                parseKeyValuePairs(resolvedEnv.get("OTEL_RESOURCE_ATTRIBUTES"));
        Map<String, String> resourceAttributes =
                mergeResourceAttributes(
                        parseKeyValuePairs(resolvedEnv.get("stellflux_OTEL_RESOURCE_ATTRIBUTES")),
                        otelResourceAttributes);
        AttributesBuilder builder = Attributes.builder();
        put(
                builder,
                SERVICE_NAME,
                firstNonBlank(
                        resolvedEnv.get("stellflux_OTEL_SERVICE_NAME"),
                        resolvedEnv.get("OTEL_SERVICE_NAME"),
                        resolvedEnv.get("STELLAR_APP_NAME"),
                        options.appId(),
                        "unknown-service"));
        put(
                builder,
                SERVICE_NAMESPACE,
                firstNonBlank(
                        resolvedEnv.get("stellflux_OTEL_SERVICE_NAMESPACE"),
                        resolvedEnv.get("STELLAR_APP_NAMESPACE"),
                        "default"));
        put(
                builder,
                SERVICE_VERSION,
                firstNonBlank(
                        resolvedEnv.get("stellflux_OTEL_SERVICE_VERSION"),
                        resolvedEnv.get("STELLAR_APP_VERSION"),
                        "unknown"));
        put(
                builder,
                SERVICE_INSTANCE_ID,
                firstNonBlank(
                        resolvedEnv.get("stellflux_OTEL_SERVICE_INSTANCE_ID"),
                        resolvedEnv.get("STELLAR_APP_INSTANCE_ID"),
                        options.clientId()));
        put(
                builder,
                DEPLOYMENT_ENVIRONMENT_NAME,
                firstNonBlank(
                        resolvedEnv.get("stellflux_OTEL_ENVIRONMENT"),
                        otelResourceAttributes.get("deployment.environment.name"),
                        resolvedEnv.get("STELLAR_ENV"),
                        options.env(),
                        "dev"));
        put(
                builder,
                K8S_CLUSTER_NAME,
                firstNonBlank(
                        resolvedEnv.get("stellflux_OTEL_CLUSTER"),
                        resolvedEnv.get("STELLAR_CLUSTER"),
                        options.cluster()));
        put(
                builder,
                CLOUD_REGION,
                firstNonBlank(
                        resolvedEnv.get("stellflux_OTEL_REGION"),
                        resolvedEnv.get("STELLAR_REGION"),
                        options.region()));
        put(
                builder,
                CLOUD_AVAILABILITY_ZONE,
                firstNonBlank(
                        resolvedEnv.get("stellflux_OTEL_ZONE"),
                        resolvedEnv.get("STELLAR_ZONE"),
                        options.zone()));
        put(
                builder,
                STELLAR_IDC,
                firstNonBlank(resolvedEnv.get("stellflux_OTEL_IDC"), resolvedEnv.get("STELLAR_IDC")));
        put(
                builder,
                HOST_NAME,
                firstNonBlank(
                        resolvedEnv.get("stellflux_OTEL_HOST_NAME"),
                        resolvedEnv.get("STELLAR_HOST_NAME"),
                        options.hostName()));
        put(builder, CONFIG_NAMESPACE, options.namespace());
        put(builder, CONFIG_GROUP, options.group());
        resourceAttributes.forEach((key, attributeValue) -> put(builder, key, attributeValue));
        return builder.build();
    }

    private Attributes withBase(
            AttributeKey<String> key1,
            String value1,
            AttributeKey<String> key2,
            String value2,
            AttributeKey<String> key3,
            String value3) {
        return Attributes.builder()
                .putAll(baseAttributes)
                .put(key1, value1)
                .put(key2, value2)
                .put(key3, value3)
                .build();
    }

    private Attributes withBase(
            AttributeKey<String> key1, String value1, AttributeKey<String> key2, String value2) {
        return Attributes.builder().putAll(baseAttributes).put(key1, value1).put(key2, value2).build();
    }

    private double elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - startedNanos) / 1000.0d;
    }

    private String errorCode(Throwable error) {
        if (error instanceof StellnulaClientException ex && !ex.errorCode().isBlank()) {
            return ex.errorCode();
        }
        return error.getClass().getSimpleName();
    }

    /** 合并 stellflux 与标准 OTel Resource Attributes。 */
    private static Map<String, String> mergeResourceAttributes(
            Map<String, String> stellfluxResourceAttributes, Map<String, String> otelResourceAttributes) {
        Map<String, String> merged = new LinkedHashMap<>(stellfluxResourceAttributes);
        merged.putAll(otelResourceAttributes);
        return merged;
    }

    /** 解析 key=value,key2=value2 形式的环境变量。 */
    private static Map<String, String> parseKeyValuePairs(String raw) {
        Map<String, String> result = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        for (String item : raw.split(",")) {
            int separator = item.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String key = item.substring(0, separator).trim();
            String attributeValue = item.substring(separator + 1).trim();
            if (!key.isBlank()) {
                result.put(key, attributeValue);
            }
        }
        return result;
    }

    /** 写入非空字符串属性。 */
    private static void put(
            AttributesBuilder builder, AttributeKey<String> key, String attributeValue) {
        if (attributeValue != null && !attributeValue.isBlank()) {
            builder.put(key, attributeValue.trim());
        }
    }

    /** 写入非空动态字符串属性。 */
    private static void put(AttributesBuilder builder, String key, String attributeValue) {
        if (key != null && !key.isBlank() && attributeValue != null && !attributeValue.isBlank()) {
            builder.put(key.trim(), attributeValue.trim());
        }
    }

    /** 返回第一个非空字符串。 */
    private static String firstNonBlank(String... values) {
        for (String candidate : values) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return null;
    }

    @Override
    public void close() {
        revisionGauge.close();
        entriesGauge.close();
    }
}
