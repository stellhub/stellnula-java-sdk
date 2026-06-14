package io.github.stellnula.transport;

public record StellnulaServerEndpoint(
        String serverId,
        String httpAddress,
        String grpcAddress,
        int weight,
        String region,
        String zone,
        boolean healthy,
        String status) {}
