package io.github.stellnula.management;

import io.github.stellnula.auth.*;
import io.github.stellnula.client.*;
import io.github.stellnula.config.*;
import io.github.stellnula.grpc.*;
import io.github.stellnula.internal.*;
import io.github.stellnula.store.*;
import io.github.stellnula.telemetry.*;
import io.github.stellnula.transport.*;

public record StellnulaGovernanceRuleDeleteRequest(
        String ownerId,
        String ownerType,
        String env,
        String region,
        String zone,
        String cluster,
        String scopeMode,
        String reason) {

    public StellnulaGovernanceRuleDeleteRequest {
        ownerId = requireText(ownerId, "ownerId");
        env = requireText(env, "env");
        ownerType = defaultText(ownerType, "APPLICATION");
        region = defaultText(region, "default");
        zone = defaultText(zone, "default");
        cluster = defaultText(cluster, "default");
        scopeMode = defaultText(scopeMode, "EXACT");
        reason = defaultText(reason, "service governance rule delete");
    }

    private static String requireText(String text, String fieldName) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return text;
    }

    private static String defaultText(String text, String defaultValue) {
        return text == null || text.isBlank() ? defaultValue : text;
    }
}
