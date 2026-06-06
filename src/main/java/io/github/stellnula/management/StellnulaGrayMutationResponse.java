package io.github.stellnula.management;

public record StellnulaGrayMutationResponse(
        long grayRuleId,
        String configId,
        long scopeId,
        String grayName,
        long grayVersion,
        long effectiveRevision,
        String status,
        String checksum,
        String updatedAt) {}
