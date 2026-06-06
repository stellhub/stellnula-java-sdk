package io.github.stellnula.management;

public record StellnulaConfigMutationResponse(
        String configId,
        long scopeId,
        String releaseNo,
        long version,
        long revision,
        String releaseStatus,
        String checksum) {}
