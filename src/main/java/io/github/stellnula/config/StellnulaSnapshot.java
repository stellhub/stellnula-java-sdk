package io.github.stellnula.config;

import io.github.stellnula.auth.*;
import io.github.stellnula.client.*;
import io.github.stellnula.grpc.*;
import io.github.stellnula.internal.*;
import io.github.stellnula.management.*;
import io.github.stellnula.store.*;
import io.github.stellnula.telemetry.*;
import io.github.stellnula.transport.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public record StellnulaSnapshot(
        long revision, String checksum, List<StellnulaConfigEntry> entries) {

    public StellnulaSnapshot {
        checksum = checksum == null ? "" : checksum;
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    /** 按配置 key 或 configId 查询配置值。 */
    public Optional<String> findValue(String key) {
        return findEntry(key).map(StellnulaConfigEntry::configValue);
    }

    /** 按配置 key 或 configId 查询必填配置值。 */
    public String requireValue(String key) {
        return findValue(key)
                .orElseThrow(() -> new IllegalArgumentException("Stellnula config is missing: " + key));
    }

    /** 按配置 key 或 configId 查询配置项。 */
    public Optional<StellnulaConfigEntry> findEntry(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        return entries.stream()
                .filter(entry -> key.equals(entry.configKey()) || key.equals(entry.configId()))
                .findFirst();
    }

    /** 转换为按 configId 索引的 Map。 */
    public Map<String, StellnulaConfigEntry> toConfigIdMap() {
        Map<String, StellnulaConfigEntry> result = new LinkedHashMap<>();
        for (StellnulaConfigEntry entry : entries) {
            result.put(entry.configId(), entry);
        }
        return Map.copyOf(result);
    }

    /** 转换为按 configKey 索引的配置值 Map。 */
    public Map<String, String> asMap() {
        Map<String, String> result = new LinkedHashMap<>();
        for (StellnulaConfigEntry entry : entries) {
            result.put(entry.configKey(), entry.configValue());
        }
        return Map.copyOf(result);
    }

    /** 计算当前快照的本地 checksum。 */
    public String calculatedChecksum() {
        return StellnulaChecksum.calculate(entries);
    }

    /** 判断当前快照是否匹配服务端 checksum。 */
    public boolean checksumMatches() {
        return StellnulaChecksum.matches(checksum, entries);
    }
}
