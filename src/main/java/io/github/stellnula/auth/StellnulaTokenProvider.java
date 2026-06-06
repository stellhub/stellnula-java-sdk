package io.github.stellnula.auth;

import io.github.stellnula.client.*;
import io.github.stellnula.config.*;
import io.github.stellnula.grpc.*;
import io.github.stellnula.internal.*;
import io.github.stellnula.management.*;
import io.github.stellnula.store.*;
import io.github.stellnula.telemetry.*;
import io.github.stellnula.transport.*;

@FunctionalInterface
public interface StellnulaTokenProvider {

    /** 获取当前访问令牌。 */
    String token();

    /** 创建固定令牌提供器。 */
    static StellnulaTokenProvider fixed(String token) {
        String resolved = token == null ? "" : token;
        return () -> resolved;
    }
}
