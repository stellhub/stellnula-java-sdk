package io.github.stellnula.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.stellnula.auth.*;
import io.github.stellnula.client.*;
import io.github.stellnula.grpc.*;
import io.github.stellnula.internal.*;
import io.github.stellnula.management.*;
import io.github.stellnula.store.*;
import io.github.stellnula.telemetry.*;
import io.github.stellnula.transport.*;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

public final class StellnulaConfigConverter {

    private StellnulaConfigConverter() {}

    public static <T> T convertValue(String key, String rawValue, Class<T> type) {
        if (rawValue == null) {
            return null;
        }
        try {
            if (type == String.class) {
                return type.cast(rawValue);
            }
            if (type == Integer.class || type == int.class) {
                return cast(type, Integer.parseInt(rawValue.trim()));
            }
            if (type == Long.class || type == long.class) {
                return cast(type, Long.parseLong(rawValue.trim()));
            }
            if (type == Boolean.class || type == boolean.class) {
                return cast(type, parseBoolean(rawValue));
            }
            if (type == Double.class || type == double.class) {
                return cast(type, Double.parseDouble(rawValue.trim()));
            }
            if (type == Float.class || type == float.class) {
                return cast(type, Float.parseFloat(rawValue.trim()));
            }
            if (type == Duration.class) {
                return type.cast(parseDuration(rawValue));
            }
            if (type.isEnum()) {
                return parseEnum(rawValue, type);
            }
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException(
                    "failed to convert Stellnula config " + key + " to " + type, ex);
        }
        throw new IllegalArgumentException("unsupported Stellnula scalar config type: " + type);
    }

    public static <T> T bind(ObjectMapper objectMapper, Map<String, Object> values, Class<T> type) {
        return objectMapper.convertValue(values, type);
    }

    private static boolean parseBoolean(String rawValue) {
        String normalized = rawValue.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "true", "1", "yes", "y", "on" -> true;
            case "false", "0", "no", "n", "off" -> false;
            default -> throw new IllegalArgumentException("invalid boolean content: " + rawValue);
        };
    }

    private static Duration parseDuration(String rawValue) {
        String normalized = rawValue.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("p")) {
            return Duration.parse(rawValue.trim());
        }
        if (normalized.endsWith("ms")) {
            return Duration.ofMillis(Long.parseLong(normalized.substring(0, normalized.length() - 2)));
        }
        if (normalized.endsWith("s")) {
            return Duration.ofSeconds(Long.parseLong(normalized.substring(0, normalized.length() - 1)));
        }
        if (normalized.endsWith("m")) {
            return Duration.ofMinutes(Long.parseLong(normalized.substring(0, normalized.length() - 1)));
        }
        if (normalized.endsWith("h")) {
            return Duration.ofHours(Long.parseLong(normalized.substring(0, normalized.length() - 1)));
        }
        if (normalized.endsWith("d")) {
            return Duration.ofDays(Long.parseLong(normalized.substring(0, normalized.length() - 1)));
        }
        return Duration.ofMillis(Long.parseLong(normalized));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> T parseEnum(String rawValue, Class<T> type) {
        return (T) Enum.valueOf((Class<? extends Enum>) type.asSubclass(Enum.class), rawValue.trim());
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Class<T> type, Object convertedValue) {
        if (type.isPrimitive()) {
            return (T) convertedValue;
        }
        return type.cast(convertedValue);
    }
}
