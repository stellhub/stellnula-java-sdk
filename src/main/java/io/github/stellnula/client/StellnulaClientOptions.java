package io.github.stellnula.client;

import io.github.stellnula.auth.StellnulaTokenProvider;
import io.github.stellnula.config.StellnulaSubscription;
import io.github.stellnula.transport.StellnulaServerSelector;
import io.opentelemetry.api.OpenTelemetry;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record StellnulaClientOptions(
        URI endpoint,
        URI grpcEndpoint,
        boolean grpcPlaintext,
        String apiToken,
        String apiVersion,
        String sdkVersion,
        String appId,
        String clientId,
        String env,
        String region,
        String zone,
        String cluster,
        String namespace,
        String group,
        String clientIp,
        String hostName,
        Map<String, String> labels,
        List<StellnulaSubscription> subscriptions,
        Path snapshotDirectory,
        Duration requestTimeout,
        Duration watchTimeout,
        Duration retryDelay,
        Duration serverRefreshInterval,
        Duration serverFailureCooldown,
        Duration grpcShutdownTimeout,
        boolean watchEnabled,
        boolean failFastOnBootstrap,
        int pageSize,
        int maxPayloadBytes,
        boolean acceptLargeFileReference,
        StellnulaTokenProvider tokenProvider,
        StellnulaServerSelector serverSelector,
        OpenTelemetry openTelemetry) {

    public StellnulaClientOptions {
        Objects.requireNonNull(endpoint, "endpoint must not be null");
        apiToken = apiToken == null ? "" : apiToken;
        apiVersion = defaultText(apiVersion, "v1");
        sdkVersion = defaultText(sdkVersion, "stellnula-java-sdk/0.1.0-SNAPSHOT");
        appId = requireText(appId, "appId");
        clientId = requireText(clientId, "clientId");
        env = requireText(env, "env");
        region = defaultText(region, "default");
        zone = defaultText(zone, "default");
        cluster = defaultText(cluster, "default");
        namespace = defaultText(namespace, "default");
        group = defaultText(group, "default");
        clientIp = clientIp == null ? "" : clientIp;
        hostName = hostName == null ? "" : hostName;
        labels = labels == null ? Map.of() : Map.copyOf(labels);
        subscriptions = subscriptions == null ? List.of() : List.copyOf(subscriptions);
        snapshotDirectory =
                snapshotDirectory == null
                        ? defaultSnapshotDirectory(appId, env, cluster)
                        : snapshotDirectory;
        requestTimeout = positiveDuration(requestTimeout, Duration.ofSeconds(10), "requestTimeout");
        watchTimeout = positiveDuration(watchTimeout, Duration.ofSeconds(30), "watchTimeout");
        retryDelay = positiveDuration(retryDelay, Duration.ofSeconds(3), "retryDelay");
        serverRefreshInterval =
                positiveDuration(serverRefreshInterval, Duration.ofMinutes(1), "serverRefreshInterval");
        serverFailureCooldown =
                positiveDuration(serverFailureCooldown, Duration.ofSeconds(30), "serverFailureCooldown");
        grpcShutdownTimeout =
                positiveDuration(grpcShutdownTimeout, Duration.ofSeconds(3), "grpcShutdownTimeout");
        if (pageSize < 0) {
            throw new IllegalArgumentException("pageSize must not be negative");
        }
        if (maxPayloadBytes < 0) {
            throw new IllegalArgumentException("maxPayloadBytes must not be negative");
        }
        tokenProvider = tokenProvider == null ? StellnulaTokenProvider.fixed(apiToken) : tokenProvider;
        serverSelector =
                serverSelector == null ? StellnulaServerSelector.weightedRendezvous() : serverSelector;
        openTelemetry = openTelemetry == null ? OpenTelemetry.noop() : openTelemetry;
    }

    /** 返回实际请求分页大小。 */
    public int effectivePageSize() {
        return pageSize <= 0 ? 500 : pageSize;
    }

    /** 获取当前访问令牌。 */
    public String currentApiToken() {
        String token = tokenProvider.token();
        return token == null ? "" : token;
    }

    /** 创建配置构造器。 */
    public static Builder builder() {
        return new Builder();
    }

    /** 返回本地快照目录。 */
    public Path snapshotDirectory() {
        return snapshotDirectory;
    }

    /** 返回本地快照 metadata 文件。 */
    @Deprecated(since = "0.0.2", forRemoval = false)
    public Path snapshotFile() {
        return snapshotDirectory.resolve(".stellnula-snapshot.json");
    }

    private static Path defaultSnapshotDirectory(String appId, String env, String cluster) {
        return Path.of(System.getProperty("user.home"), ".stellnula", appId, env, cluster);
    }

    private static Duration positiveDuration(
            Duration duration, Duration defaultValue, String fieldName) {
        Duration resolved = duration == null ? defaultValue : duration;
        if (resolved.isZero() || resolved.isNegative()) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return resolved;
    }

    private static String requireText(String text, String fieldName) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return text;
    }

    private static String defaultText(String text, String defaultValue) {
        return text == null || text.isBlank() ? defaultValue : text;
    }

    public static final class Builder {
        private URI endpoint;
        private URI grpcEndpoint;
        private boolean grpcPlaintext = true;
        private String apiToken = "";
        private String apiVersion = "v1";
        private String sdkVersion = "stellnula-java-sdk/0.1.0-SNAPSHOT";
        private String appId = "default-app";
        private String clientId = "default-client";
        private String env = "dev";
        private String region = "default";
        private String zone = "default";
        private String cluster = "default";
        private String namespace = "default";
        private String group = "default";
        private String clientIp = "";
        private String hostName = "";
        private Map<String, String> labels = Map.of();
        private List<StellnulaSubscription> subscriptions = List.of();
        private Path snapshotDirectory;
        private Duration requestTimeout = Duration.ofSeconds(10);
        private Duration watchTimeout = Duration.ofSeconds(30);
        private Duration retryDelay = Duration.ofSeconds(3);
        private Duration serverRefreshInterval = Duration.ofMinutes(1);
        private Duration serverFailureCooldown = Duration.ofSeconds(30);
        private Duration grpcShutdownTimeout = Duration.ofSeconds(3);
        private boolean watchEnabled = true;
        private boolean failFastOnBootstrap = false;
        private int pageSize = 0;
        private int maxPayloadBytes = 0;
        private boolean acceptLargeFileReference;
        private StellnulaTokenProvider tokenProvider;
        private StellnulaServerSelector serverSelector;
        private OpenTelemetry openTelemetry;

        public Builder endpoint(URI endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        public Builder grpcEndpoint(URI grpcEndpoint) {
            this.grpcEndpoint = grpcEndpoint;
            return this;
        }

        public Builder grpcPlaintext(boolean grpcPlaintext) {
            this.grpcPlaintext = grpcPlaintext;
            return this;
        }

        public Builder apiToken(String apiToken) {
            this.apiToken = apiToken;
            return this;
        }

        public Builder apiVersion(String apiVersion) {
            this.apiVersion = apiVersion;
            return this;
        }

        public Builder sdkVersion(String sdkVersion) {
            this.sdkVersion = sdkVersion;
            return this;
        }

        public Builder appId(String appId) {
            this.appId = appId;
            return this;
        }

        public Builder clientId(String clientId) {
            this.clientId = clientId;
            return this;
        }

        public Builder env(String env) {
            this.env = env;
            return this;
        }

        public Builder region(String region) {
            this.region = region;
            return this;
        }

        public Builder zone(String zone) {
            this.zone = zone;
            return this;
        }

        public Builder cluster(String cluster) {
            this.cluster = cluster;
            return this;
        }

        public Builder namespace(String namespace) {
            this.namespace = namespace;
            return this;
        }

        public Builder group(String group) {
            this.group = group;
            return this;
        }

        public Builder clientIp(String clientIp) {
            this.clientIp = clientIp;
            return this;
        }

        public Builder hostName(String hostName) {
            this.hostName = hostName;
            return this;
        }

        public Builder labels(Map<String, String> labels) {
            this.labels = labels;
            return this;
        }

        public Builder subscriptions(List<StellnulaSubscription> subscriptions) {
            this.subscriptions = subscriptions;
            return this;
        }

        public Builder snapshotDirectory(Path snapshotDirectory) {
            this.snapshotDirectory = snapshotDirectory;
            return this;
        }

        /** 使用 snapshotDirectory(Path) 替代。 */
        @Deprecated(since = "0.0.2", forRemoval = false)
        public Builder snapshotFile(Path snapshotFile) {
            this.snapshotDirectory =
                    snapshotFile == null
                            ? null
                            : snapshotFile.getParent() == null ? Path.of(".") : snapshotFile.getParent();
            return this;
        }

        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
            return this;
        }

        public Builder watchTimeout(Duration watchTimeout) {
            this.watchTimeout = watchTimeout;
            return this;
        }

        public Builder retryDelay(Duration retryDelay) {
            this.retryDelay = retryDelay;
            return this;
        }

        public Builder serverRefreshInterval(Duration serverRefreshInterval) {
            this.serverRefreshInterval = serverRefreshInterval;
            return this;
        }

        public Builder serverFailureCooldown(Duration serverFailureCooldown) {
            this.serverFailureCooldown = serverFailureCooldown;
            return this;
        }

        public Builder grpcShutdownTimeout(Duration grpcShutdownTimeout) {
            this.grpcShutdownTimeout = grpcShutdownTimeout;
            return this;
        }

        public Builder watchEnabled(boolean watchEnabled) {
            this.watchEnabled = watchEnabled;
            return this;
        }

        public Builder failFastOnBootstrap(boolean failFastOnBootstrap) {
            this.failFastOnBootstrap = failFastOnBootstrap;
            return this;
        }

        public Builder pageSize(int pageSize) {
            this.pageSize = pageSize;
            return this;
        }

        public Builder maxPayloadBytes(int maxPayloadBytes) {
            this.maxPayloadBytes = maxPayloadBytes;
            return this;
        }

        public Builder acceptLargeFileReference(boolean acceptLargeFileReference) {
            this.acceptLargeFileReference = acceptLargeFileReference;
            return this;
        }

        public Builder tokenProvider(StellnulaTokenProvider tokenProvider) {
            this.tokenProvider = tokenProvider;
            return this;
        }

        public Builder serverSelector(StellnulaServerSelector serverSelector) {
            this.serverSelector = serverSelector;
            return this;
        }

        /** 设置框架统一管理的 OpenTelemetry 实例。 */
        public Builder openTelemetry(OpenTelemetry openTelemetry) {
            this.openTelemetry = openTelemetry;
            return this;
        }

        public StellnulaClientOptions build() {
            return new StellnulaClientOptions(
                    endpoint,
                    grpcEndpoint,
                    grpcPlaintext,
                    apiToken,
                    apiVersion,
                    sdkVersion,
                    appId,
                    clientId,
                    env,
                    region,
                    zone,
                    cluster,
                    namespace,
                    group,
                    clientIp,
                    hostName,
                    labels,
                    subscriptions,
                    snapshotDirectory,
                    requestTimeout,
                    watchTimeout,
                    retryDelay,
                    serverRefreshInterval,
                    serverFailureCooldown,
                    grpcShutdownTimeout,
                    watchEnabled,
                    failFastOnBootstrap,
                    pageSize,
                    maxPayloadBytes,
                    acceptLargeFileReference,
                    tokenProvider,
                    serverSelector,
                    openTelemetry);
        }
    }
}
