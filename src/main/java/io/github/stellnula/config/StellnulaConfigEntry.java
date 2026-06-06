package io.github.stellnula.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.stellnula.auth.*;
import io.github.stellnula.client.*;
import io.github.stellnula.grpc.*;
import io.github.stellnula.internal.*;
import io.github.stellnula.management.*;
import io.github.stellnula.store.*;
import io.github.stellnula.telemetry.*;
import io.github.stellnula.transport.*;

public record StellnulaConfigEntry(
        String configId,
        String configKey,
        String contentType,
        @JsonProperty("value") String configValue,
        long version,
        long revision,
        boolean encrypted,
        boolean deleted,
        String matchedType,
        Long matchedGrayId,
        String matchedGrayName,
        Long grayVersion,
        String valueEncoding,
        String deliveryMode,
        int valueSizeBytes,
        String valueRef,
        StellnulaConfigScope scope) {

    public StellnulaConfigEntry {
        configId = normalize(configId);
        configKey = normalize(configKey);
        contentType = defaultText(contentType, "KV");
        configValue = configValue == null ? "" : configValue;
        matchedType = defaultText(matchedType, "BASE");
        valueEncoding = defaultText(valueEncoding, "identity");
        deliveryMode = defaultText(deliveryMode, "INLINE");
        valueRef = valueRef == null ? "" : valueRef;
        scope = scope == null ? new StellnulaConfigScope("", "", "", "") : scope;
    }

    private static String normalize(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("config id and key must not be blank");
        }
        return text;
    }

    private static String defaultText(String text, String defaultValue) {
        return text == null || text.isBlank() ? defaultValue : text;
    }
}
