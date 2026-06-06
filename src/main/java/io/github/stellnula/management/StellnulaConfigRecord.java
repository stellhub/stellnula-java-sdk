package io.github.stellnula.management;

public record StellnulaConfigRecord(
        String configId,
        String configName,
        String ownerType,
        String ownerId,
        String namespace,
        String group,
        String contentType,
        boolean sensitive,
        String env,
        String region,
        String zone,
        String cluster,
        String scopeMode,
        String releaseNo,
        long version,
        long revision,
        String releaseStatus,
        String checksum,
        String content) {}
