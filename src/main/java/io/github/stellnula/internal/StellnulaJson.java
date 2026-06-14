package io.github.stellnula.internal;

import com.fasterxml.jackson.databind.ObjectMapper;

public final class StellnulaJson {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private StellnulaJson() {}

    /** 返回 SDK 内部共享 JSON mapper。 */
    public static ObjectMapper objectMapper() {
        return OBJECT_MAPPER;
    }
}
