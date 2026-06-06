package io.github.stellnula.management;

public record StellnulaGrayRuleRecord(
        long grayRuleId,
        String configId,
        long scopeId,
        String grayName,
        String ruleType,
        String grayRules,
        String configValue,
        long grayVersion,
        long effectiveRevision,
        String checksum,
        int priority,
        String status,
        String startTime,
        String endTime,
        String updatedAt) {}
