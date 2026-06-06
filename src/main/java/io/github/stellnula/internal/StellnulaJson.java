package io.github.stellnula.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.stellnula.auth.*;
import io.github.stellnula.client.*;
import io.github.stellnula.config.*;
import io.github.stellnula.grpc.*;
import io.github.stellnula.management.*;
import io.github.stellnula.store.*;
import io.github.stellnula.telemetry.*;
import io.github.stellnula.transport.*;

public final class StellnulaJson {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private StellnulaJson() {}

    /** 返回 SDK 内部共享 JSON mapper。 */
    public static ObjectMapper objectMapper() {
        return OBJECT_MAPPER;
    }
}
