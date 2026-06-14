package io.github.stellnula.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.stellnula.client.StellnulaClientOptions;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StellnulaTelemetryTest {

    @Test
    void resolvesBaseAttributesWithStellfluxResourceKeys() {
        Attributes attributes =
                StellnulaTelemetry.resolveBaseAttributes(
                        options(),
                        Map.of(
                                "STELLAR_APP_NAME",
                                "order-service",
                                "STELLAR_APP_NAMESPACE",
                                "stellar.trade",
                                "STELLAR_APP_VERSION",
                                "1.2.3",
                                "STELLAR_APP_INSTANCE_ID",
                                "order-001",
                                "STELLAR_ENV",
                                "prod",
                                "STELLAR_CLUSTER",
                                "prod-cluster",
                                "STELLAR_REGION",
                                "cn-hangzhou",
                                "STELLAR_ZONE",
                                "az-1",
                                "STELLAR_IDC",
                                "idc-a",
                                "STELLAR_HOST_NAME",
                                "host-a"));

        assertEquals("order-service", stringAttribute(attributes, "service.name"));
        assertEquals("stellar.trade", stringAttribute(attributes, "service.namespace"));
        assertEquals("1.2.3", stringAttribute(attributes, "service.version"));
        assertEquals("order-001", stringAttribute(attributes, "service.instance.id"));
        assertEquals("prod", stringAttribute(attributes, "deployment.environment.name"));
        assertEquals("prod-cluster", stringAttribute(attributes, "k8s.cluster.name"));
        assertEquals("cn-hangzhou", stringAttribute(attributes, "cloud.region"));
        assertEquals("az-1", stringAttribute(attributes, "cloud.availability_zone"));
        assertEquals("idc-a", stringAttribute(attributes, "stellar.idc"));
        assertEquals("host-a", stringAttribute(attributes, "host.name"));
        assertEquals("application", stringAttribute(attributes, "stellnula.config.namespace"));
        assertEquals("default", stringAttribute(attributes, "stellnula.config.group"));
        assertNull(stringAttribute(attributes, "stellnula.app_id"));
        assertNull(stringAttribute(attributes, "stellnula.env"));
    }

    @Test
    void letsOtelResourceAttributesOverrideStellfluxResolvedValues() {
        Attributes attributes =
                StellnulaTelemetry.resolveBaseAttributes(
                        options(),
                        Map.of(
                                "stellflux_OTEL_SERVICE_NAME",
                                "stellflux-service",
                                "stellflux_OTEL_ENVIRONMENT",
                                "qa",
                                "stellflux_OTEL_RESOURCE_ATTRIBUTES",
                                "service.name=explicit-service,k8s.cluster.name=explicit-cluster",
                                "OTEL_RESOURCE_ATTRIBUTES",
                                "service.name=resource-service,deployment.environment.name=resource-env,k8s.cluster.name=resource-cluster"));

        assertEquals("resource-service", stringAttribute(attributes, "service.name"));
        assertEquals("resource-env", stringAttribute(attributes, "deployment.environment.name"));
        assertEquals("resource-cluster", stringAttribute(attributes, "k8s.cluster.name"));
    }

    @Test
    void keepsExternallyProvidedOpenTelemetryInstance() {
        OpenTelemetry openTelemetry = OpenTelemetry.noop();

        StellnulaClientOptions options =
                StellnulaClientOptions.builder()
                        .endpoint(URI.create("http://127.0.0.1:8080"))
                        .openTelemetry(openTelemetry)
                        .build();

        assertSame(openTelemetry, options.openTelemetry());
    }

    private static StellnulaClientOptions options() {
        return StellnulaClientOptions.builder()
                .endpoint(URI.create("http://127.0.0.1:8080"))
                .appId("fallback-service")
                .clientId("fallback-instance")
                .env("dev")
                .region("fallback-region")
                .zone("fallback-zone")
                .cluster("fallback-cluster")
                .namespace("application")
                .group("default")
                .hostName("fallback-host")
                .build();
    }

    private static String stringAttribute(Attributes attributes, String key) {
        return attributes.get(AttributeKey.stringKey(key));
    }
}
