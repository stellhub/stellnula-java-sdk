package io.github.stellnula.management;

import java.util.List;
import java.util.Map;

public record StellnulaGrayImpactResponse(
        String configId,
        String grayName,
        String env,
        String region,
        String zone,
        String cluster,
        int matchedCount,
        List<Client> clients) {

    public record Client(
            String appId,
            String clientId,
            String env,
            String region,
            String zone,
            String cluster,
            String namespaceCode,
            String groupCode,
            String clientIp,
            String hostName,
            String sdkVersion,
            Map<String, String> labels,
            String lastSeenAt) {}
}
