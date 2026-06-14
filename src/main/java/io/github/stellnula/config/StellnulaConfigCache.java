package io.github.stellnula.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public final class StellnulaConfigCache {

    private final AtomicReference<StellnulaSnapshot> snapshot =
            new AtomicReference<>(new StellnulaSnapshot(0, "", List.of()));

    /** 获取当前快照。 */
    public StellnulaSnapshot snapshot() {
        return snapshot.get();
    }

    /** 判断是否已经有本地配置。 */
    public boolean hasSnapshot() {
        return !snapshot.get().entries().isEmpty() || snapshot.get().revision() > 0;
    }

    /** 替换完整快照。 */
    public StellnulaSnapshot replace(StellnulaSnapshot next) {
        snapshot.set(next);
        return next;
    }

    /** 仅更新快照 revision 和 checksum。 */
    public StellnulaSnapshot updateMetadata(long revision, String checksum) {
        StellnulaSnapshot current = snapshot.get();
        if (revision <= current.revision()
                && (checksum == null || checksum.isBlank() || checksum.equals(current.checksum()))) {
            return current;
        }
        StellnulaSnapshot next =
                new StellnulaSnapshot(
                        Math.max(current.revision(), revision),
                        checksum == null || checksum.isBlank() ? current.checksum() : checksum,
                        current.entries());
        snapshot.set(next);
        return next;
    }

    /** 应用服务端增量并返回新快照。 */
    public StellnulaSnapshot apply(
            long revision, String checksum, List<StellnulaConfigChange> changes) {
        LinkedHashMap<String, StellnulaConfigEntry> entries =
                new LinkedHashMap<>(snapshot.get().toConfigIdMap());
        for (StellnulaConfigChange change : changes) {
            if (change.type() == StellnulaChangeType.DELETE || change.entry().deleted()) {
                entries.remove(change.entry().configId());
            } else {
                entries.put(change.entry().configId(), change.entry());
            }
        }
        StellnulaSnapshot next =
                new StellnulaSnapshot(revision, checksum, new ArrayList<>(entries.values()));
        snapshot.set(next);
        return next;
    }

    /** 按配置 key 或 configId 查询配置值。 */
    public Optional<String> getValue(String key) {
        return snapshot.get().findValue(key);
    }

    /** 按配置 key 或 configId 查询配置项。 */
    public Optional<StellnulaConfigEntry> getEntry(String key) {
        return snapshot.get().findEntry(key);
    }
}
