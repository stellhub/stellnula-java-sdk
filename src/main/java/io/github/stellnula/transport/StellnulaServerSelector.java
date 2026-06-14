package io.github.stellnula.transport;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@FunctionalInterface
public interface StellnulaServerSelector {

    /** 从健康服务端列表中选择一个目标。 */
    Optional<StellnulaServerEndpoint> select(List<StellnulaServerEndpoint> servers, String hashKey);

    /** 默认加权 rendezvous hash 选择器。 */
    static StellnulaServerSelector weightedRendezvous() {
        return (servers, hashKey) ->
                servers.stream()
                        .max(Comparator.comparingDouble(server -> rendezvousScore(server, hashKey)));
    }

    private static double rendezvousScore(StellnulaServerEndpoint server, String hashKey) {
        int weight = Math.max(1, server.weight());
        long hash = fnv1a64(hashKey + '|' + server.serverId() + '|' + server.grpcAddress());
        double normalized = ((hash >>> 1) + 1.0) / (Long.MAX_VALUE + 1.0);
        return -Math.log(normalized) / weight;
    }

    private static long fnv1a64(String text) {
        long hash = 0xcbf29ce484222325L;
        for (byte current : text.getBytes(StandardCharsets.UTF_8)) {
            hash ^= current;
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}
