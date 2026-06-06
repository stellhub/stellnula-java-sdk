package io.github.stellnula.management;

public record StellnulaGovernanceRuleRecord(
        String ruleId,
        String ruleName,
        String ownerType,
        String ownerId,
        String env,
        String region,
        String zone,
        String cluster,
        String contentType,
        long version,
        long revision,
        String releaseStatus,
        String checksum,
        String ruleType,
        String targetService,
        String status,
        Integer priority,
        String content) {}
