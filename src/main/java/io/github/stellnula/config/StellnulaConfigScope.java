package io.github.stellnula.config;

import io.github.stellnula.auth.*;
import io.github.stellnula.client.*;
import io.github.stellnula.grpc.*;
import io.github.stellnula.internal.*;
import io.github.stellnula.management.*;
import io.github.stellnula.store.*;
import io.github.stellnula.telemetry.*;
import io.github.stellnula.transport.*;

public record StellnulaConfigScope(String env, String region, String zone, String cluster) {

    public StellnulaConfigScope {
        env = defaultText(env);
        region = defaultText(region);
        zone = defaultText(zone);
        cluster = defaultText(cluster);
    }

    private static String defaultText(String text) {
        return text == null || text.isBlank() ? "default" : text;
    }
}
