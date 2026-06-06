package io.github.stellnula.transport;

import io.github.stellnula.auth.*;
import io.github.stellnula.client.*;
import io.github.stellnula.config.*;
import io.github.stellnula.grpc.*;
import io.github.stellnula.internal.*;
import io.github.stellnula.management.*;
import io.github.stellnula.store.*;
import io.github.stellnula.telemetry.*;

public record StellnulaServerEndpoint(
        String serverId,
        String httpAddress,
        String grpcAddress,
        int weight,
        String region,
        String zone,
        boolean healthy,
        String status) {}
