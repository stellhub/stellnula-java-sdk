package io.github.stellnula.config;

import io.github.stellnula.auth.*;
import io.github.stellnula.client.*;
import io.github.stellnula.grpc.*;
import io.github.stellnula.internal.*;
import io.github.stellnula.management.*;
import io.github.stellnula.store.*;
import io.github.stellnula.telemetry.*;
import io.github.stellnula.transport.*;

public final class StellnulaConfigPrefixes {

    private StellnulaConfigPrefixes() {}

    public static String normalize(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "";
        }
        String normalized = prefix.trim();
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
