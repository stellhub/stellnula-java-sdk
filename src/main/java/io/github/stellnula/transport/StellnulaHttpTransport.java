package io.github.stellnula.transport;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.stellnula.client.StellnulaClientException;
import io.github.stellnula.client.StellnulaClientOptions;
import io.github.stellnula.client.StellnulaRetryBackoffHint;
import io.github.stellnula.config.StellnulaChangeType;
import io.github.stellnula.config.StellnulaChecksum;
import io.github.stellnula.config.StellnulaConfigChange;
import io.github.stellnula.config.StellnulaConfigEntry;
import io.github.stellnula.config.StellnulaConfigScope;
import io.github.stellnula.config.StellnulaSnapshot;
import io.github.stellnula.config.StellnulaSubscription;
import io.github.stellnula.management.StellnulaConfigDeleteRequest;
import io.github.stellnula.management.StellnulaConfigMutationResponse;
import io.github.stellnula.management.StellnulaConfigRecord;
import io.github.stellnula.management.StellnulaConfigRequest;
import io.github.stellnula.management.StellnulaGovernanceRuleDeleteRequest;
import io.github.stellnula.management.StellnulaGovernanceRuleRecord;
import io.github.stellnula.management.StellnulaGovernanceRuleRequest;
import io.github.stellnula.management.StellnulaGrayImpactResponse;
import io.github.stellnula.management.StellnulaGrayMutationResponse;
import io.github.stellnula.management.StellnulaGrayRuleEndRequest;
import io.github.stellnula.management.StellnulaGrayRuleRecord;
import io.github.stellnula.management.StellnulaGrayRuleRequest;
import io.github.stellnula.management.StellnulaPublicConfigReplicationRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class StellnulaHttpTransport {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final StellnulaClientOptions options;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final StellnulaServerFailureTracker failureTracker;

    public StellnulaHttpTransport(
            StellnulaClientOptions options, OkHttpClient httpClient, ObjectMapper objectMapper) {
        this(
                options,
                httpClient,
                objectMapper,
                new StellnulaServerFailureTracker(options.serverFailureCooldown()));
    }

    StellnulaHttpTransport(
            StellnulaClientOptions options,
            OkHttpClient httpClient,
            ObjectMapper objectMapper,
            StellnulaServerFailureTracker failureTracker) {
        this.options = options;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.failureTracker = failureTracker;
    }

    /** 执行客户端 bootstrap。 */
    public BootstrapResult bootstrap(long currentRevision) throws IOException, InterruptedException {
        List<ConfigEntryResponse> entries = new ArrayList<>();
        BootstrapResponse response;
        String pageToken = "";
        do {
            BootstrapRequest body = bootstrapRequest(currentRevision, pageToken);
            response = sendJson("POST", "/api/v1/client/bootstrap", body, BootstrapResponse.class);
            if (response.configs() != null) {
                entries.addAll(response.configs());
            }
            pageToken = nextPageToken(response.protocol());
        } while (!pageToken.isBlank());
        return new BootstrapResult(
                toSnapshot(response.revision(), response.snapshotChecksum(), entries),
                selectGrpcEndpoint(response.servers()));
    }

    /** 拉取客户端全量配置。 */
    public StellnulaSnapshot fetchFull() throws IOException, InterruptedException {
        List<ConfigEntryResponse> entries = new ArrayList<>();
        SnapshotResponse response;
        String pageToken = "";
        do {
            response =
                    sendGet(
                            "/api/v1/client/configs/full",
                            clientQuery(Map.of("pageToken", pageToken)),
                            SnapshotResponse.class);
            if (response.entries() != null) {
                entries.addAll(response.entries());
            }
            pageToken = nextPageToken(response.protocol());
        } while (!pageToken.isBlank());
        return toSnapshot(response.revision(), response.checksum(), entries);
    }

    /** 拉取客户端增量配置。 */
    public DeltaResult fetchDelta(long fromRevision) throws IOException, InterruptedException {
        List<StellnulaConfigChange> changes = new ArrayList<>();
        DeltaResponse response;
        String pageToken = "";
        boolean fullSyncRequired = false;
        String fullSyncReason = "";
        do {
            response =
                    sendGet(
                            "/api/v1/client/configs/delta",
                            clientQuery(
                                    Map.of("fromRevision", Long.toString(fromRevision), "pageToken", pageToken)),
                            DeltaResponse.class);
            if (response.changes() != null) {
                for (ConfigChangeResponse change : response.changes()) {
                    changes.add(toChange(change));
                }
            }
            fullSyncRequired =
                    fullSyncRequired
                            || (response.protocol() != null
                                    && Boolean.TRUE.equals(response.protocol().fullSyncRequired()));
            fullSyncReason =
                    response.protocol() == null
                            ? fullSyncReason
                            : defaultText(response.protocol().fullSyncReason(), fullSyncReason);
            pageToken = nextPageToken(response.protocol());
        } while (!pageToken.isBlank());
        return new DeltaResult(
                response.fromRevision(),
                response.toRevision(),
                response.checksum(),
                changes,
                fullSyncRequired,
                fullSyncReason);
    }

    /** 按需读取引用型大文件配置内容。 */
    public StellnulaConfigEntry fetchContent(String configId)
            throws IOException, InterruptedException {
        ConfigContentResponse response =
                sendGet(
                        "/api/v1/client/configs/content",
                        clientQuery(Map.of("configId", configId)),
                        ConfigContentResponse.class);
        return toEntry(response.content(), false);
    }

    /** 上报客户端状态心跳。 */
    public void heartbeat(StellnulaSnapshot snapshot, boolean localFileLoaded) throws IOException {
        ClientStateRequest body =
                new ClientStateRequest(
                        options.appId(),
                        options.clientId(),
                        options.env(),
                        options.region(),
                        options.zone(),
                        options.cluster(),
                        options.namespace(),
                        options.group(),
                        options.clientIp(),
                        options.hostName(),
                        options.sdkVersion(),
                        options.labels(),
                        snapshot.revision(),
                        snapshot.checksum(),
                        localFileLoaded,
                        OffsetDateTime.now().toString(),
                        options.subscriptions(),
                        options.apiVersion());
        sendJson("POST", "/api/v1/client/heartbeat", body, ClientStateResponse.class);
    }

    /** 标记 gRPC 节点失败，短时间内从自动选点候选中隔离。 */
    public void markGrpcEndpointFailure(URI endpoint) {
        failureTracker.markFailure(endpoint);
    }

    /** 标记 gRPC 节点恢复，允许自动选点重新选择。 */
    public void markGrpcEndpointSuccess(URI endpoint) {
        failureTracker.markSuccess(endpoint);
    }

    /** 查询管理面配置。 */
    public Optional<StellnulaConfigRecord> getConfig(
            String configId, String env, String region, String zone, String cluster) throws IOException {
        try {
            return Optional.of(
                    sendGet(
                            "/api/v1/configs/" + encode(configId),
                            query(Map.of("env", env, "region", region, "zone", zone, "cluster", cluster)),
                            StellnulaConfigRecord.class));
        } catch (StellnulaClientException ex) {
            if (ex.statusCode() == 404) {
                return Optional.empty();
            }
            throw ex;
        }
    }

    /** 新增或更新管理面配置。 */
    public StellnulaConfigMutationResponse upsertConfig(
            String configId, StellnulaConfigRequest request) throws IOException {
        return sendJson(
                "PUT",
                "/api/v1/configs/" + encode(configId),
                request,
                StellnulaConfigMutationResponse.class);
    }

    /** 删除管理面配置。 */
    public StellnulaConfigMutationResponse deleteConfig(
            String configId, StellnulaConfigDeleteRequest request) throws IOException {
        return sendJson(
                "DELETE",
                "/api/v1/configs/" + encode(configId),
                request,
                StellnulaConfigMutationResponse.class);
    }

    /** 复制公共配置到目标环境。 */
    public StellnulaConfigMutationResponse replicatePublicConfig(
            String configId, StellnulaPublicConfigReplicationRequest request) throws IOException {
        return sendJson(
                "POST",
                "/api/v1/configs/" + encode(configId) + "/replications",
                request,
                StellnulaConfigMutationResponse.class);
    }

    /** 查询服务治理规则列表。 */
    public List<StellnulaGovernanceRuleRecord> listGovernanceRules(
            String env, String ownerId, String ruleType, String targetService, String status)
            throws IOException {
        return sendGetList(
                "/api/v1/governance/rules",
                query(
                        Map.of(
                                "env",
                                env,
                                "ownerId",
                                ownerId == null ? "" : ownerId,
                                "ruleType",
                                ruleType == null ? "" : ruleType,
                                "targetService",
                                targetService == null ? "" : targetService,
                                "status",
                                status == null ? "" : status)),
                StellnulaGovernanceRuleRecord[].class);
    }

    /** 查询服务治理规则。 */
    public Optional<StellnulaGovernanceRuleRecord> getGovernanceRule(
            String ruleId, String env, String region, String zone, String cluster) throws IOException {
        try {
            return Optional.of(
                    sendGet(
                            "/api/v1/governance/rules/" + encode(ruleId),
                            query(Map.of("env", env, "region", region, "zone", zone, "cluster", cluster)),
                            StellnulaGovernanceRuleRecord.class));
        } catch (StellnulaClientException ex) {
            if (ex.statusCode() == 404) {
                return Optional.empty();
            }
            throw ex;
        }
    }

    /** 新增或更新服务治理规则。 */
    public StellnulaConfigMutationResponse upsertGovernanceRule(
            String ruleId, StellnulaGovernanceRuleRequest request) throws IOException {
        return sendJson(
                "PUT",
                "/api/v1/governance/rules/" + encode(ruleId),
                request,
                StellnulaConfigMutationResponse.class);
    }

    /** 删除服务治理规则。 */
    public StellnulaConfigMutationResponse deleteGovernanceRule(
            String ruleId, StellnulaGovernanceRuleDeleteRequest request) throws IOException {
        return sendJson(
                "DELETE",
                "/api/v1/governance/rules/" + encode(ruleId),
                request,
                StellnulaConfigMutationResponse.class);
    }

    /** 查询配置灰度规则。 */
    public Optional<StellnulaGrayRuleRecord> getGrayRule(
            String configId, String grayName, String env, String region, String zone, String cluster)
            throws IOException {
        try {
            return Optional.of(
                    sendGet(
                            "/api/v1/configs/" + encode(configId) + "/gray-rules/" + encode(grayName),
                            query(Map.of("env", env, "region", region, "zone", zone, "cluster", cluster)),
                            StellnulaGrayRuleRecord.class));
        } catch (StellnulaClientException ex) {
            if (ex.statusCode() == 404) {
                return Optional.empty();
            }
            throw ex;
        }
    }

    /** 查询配置灰度影响面。 */
    public StellnulaGrayImpactResponse getGrayImpact(
            String configId,
            String grayName,
            String env,
            String region,
            String zone,
            String cluster,
            int limit)
            throws IOException {
        return sendGet(
                "/api/v1/configs/" + encode(configId) + "/gray-rules/" + encode(grayName) + "/impact",
                query(
                        Map.of(
                                "env",
                                env,
                                "region",
                                region,
                                "zone",
                                zone,
                                "cluster",
                                cluster,
                                "limit",
                                Integer.toString(limit))),
                StellnulaGrayImpactResponse.class);
    }

    /** 创建、更新或发布配置灰度规则。 */
    public StellnulaGrayMutationResponse upsertGrayRule(
            String configId, String grayName, StellnulaGrayRuleRequest request) throws IOException {
        return sendJson(
                "PUT",
                "/api/v1/configs/" + encode(configId) + "/gray-rules/" + encode(grayName),
                request,
                StellnulaGrayMutationResponse.class);
    }

    /** 结束配置灰度规则。 */
    public StellnulaGrayMutationResponse endGrayRule(
            String configId, String grayName, StellnulaGrayRuleEndRequest request) throws IOException {
        return sendJson(
                "DELETE",
                "/api/v1/configs/" + encode(configId) + "/gray-rules/" + encode(grayName),
                request,
                StellnulaGrayMutationResponse.class);
    }

    private <T> T sendGet(String path, String query, Class<T> responseType) throws IOException {
        URI uri = resolve(path + (query.isBlank() ? "" : "?" + query));
        Request request = requestBuilder(uri).get().build();
        return execute(request, responseType);
    }

    private <T> List<T> sendGetList(String path, String query, Class<T[]> responseType)
            throws IOException {
        T[] values = sendGet(path, query, responseType);
        return List.copyOf(Arrays.asList(values));
    }

    private <T> T sendJson(String method, String path, Object body, Class<T> responseType)
            throws IOException {
        byte[] payload = objectMapper.writeValueAsBytes(body);
        Request request =
                requestBuilder(resolve(path)).method(method, RequestBody.create(payload, JSON)).build();
        return execute(request, responseType);
    }

    private <T> T execute(Request request, Class<T> responseType) throws IOException {
        okhttp3.Call call = httpClient.newCall(request);
        call.timeout().timeout(options.requestTimeout().toMillis(), TimeUnit.MILLISECONDS);
        try (Response response = call.execute()) {
            ResponseBody body = response.body();
            String responseBody = body.string();
            if (response.isSuccessful()) {
                return objectMapper.readValue(responseBody, responseType);
            }
            throw toClientException(response.code(), responseBody);
        }
    }

    private Request.Builder requestBuilder(URI uri) {
        Request.Builder builder =
                new Request.Builder()
                        .url(uri.toString())
                        .header("Accept", "application/json")
                        .header("X-API-Version", options.apiVersion())
                        .header("X-SDK-Version", options.sdkVersion())
                        .header("Accept-Encoding", "gzip");
        String token = options.currentApiToken();
        if (!token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder;
    }

    private URI resolve(String path) {
        String endpoint = options.endpoint().toString();
        String normalizedEndpoint =
                endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        return URI.create(normalizedEndpoint + path);
    }

    private String clientQuery(Map<String, String> extras) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("appId", options.appId());
        query.put("clientId", options.clientId());
        query.put("env", options.env());
        query.put("region", options.region());
        query.put("zone", options.zone());
        query.put("cluster", options.cluster());
        query.put("namespace", options.namespace());
        query.put("group", options.group());
        query.put("apiVersion", options.apiVersion());
        query.put("sdkVersion", options.sdkVersion());
        query.put("acceptedCompressions", "gzip");
        query.put("pageSize", Integer.toString(options.effectivePageSize()));
        query.put("maxPayloadBytes", Integer.toString(options.maxPayloadBytes()));
        query.put("acceptLargeFileReference", Boolean.toString(options.acceptLargeFileReference()));
        query.putAll(extras);
        return query(query);
    }

    private static String query(Map<String, String> queryValues) {
        StringJoiner joiner = new StringJoiner("&");
        queryValues.forEach((key, queryValue) -> joiner.add(encode(key) + "=" + encode(queryValue)));
        return joiner.toString();
    }

    private static String encode(String text) {
        return URLEncoder.encode(text == null ? "" : text, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private Optional<URI> selectGrpcEndpoint(List<ServerEndpointResponse> servers) {
        if (options.grpcEndpoint() != null) {
            return failureTracker.isAvailable(options.grpcEndpoint())
                    ? Optional.of(options.grpcEndpoint())
                    : Optional.empty();
        }
        if (servers == null) {
            return Optional.empty();
        }
        List<StellnulaServerEndpoint> candidates =
                servers.stream()
                        .filter(server -> server.grpcAddress() != null && !server.grpcAddress().isBlank())
                        .filter(
                                server -> server.healthy() || server.status() == null || server.status().isBlank())
                        .map(ServerEndpointResponse::toEndpoint)
                        .filter(server -> failureTracker.isAvailable(toGrpcUri(server.grpcAddress())))
                        .toList();
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        String hashKey =
                options.appId()
                        + ':'
                        + options.clientId()
                        + ':'
                        + options.env()
                        + ':'
                        + options.namespace();
        return options
                .serverSelector()
                .select(candidates, hashKey)
                .map(server -> toGrpcUri(server.grpcAddress()));
    }

    private StellnulaSnapshot toSnapshot(
            long revision, String checksum, List<ConfigEntryResponse> entries)
            throws IOException, InterruptedException {
        List<StellnulaConfigEntry> resolvedEntries = new ArrayList<>();
        if (entries != null) {
            for (ConfigEntryResponse entry : entries) {
                resolvedEntries.add(toEntry(entry, true));
            }
        }
        if (!StellnulaChecksum.matches(checksum, resolvedEntries)) {
            throw new StellnulaClientException(
                    0,
                    "",
                    "CHECKSUM_MISMATCH",
                    true,
                    0,
                    null,
                    true,
                    "LOCAL_CHECKSUM_MISMATCH",
                    "Stellnula snapshot checksum mismatch");
        }
        return new StellnulaSnapshot(revision, checksum, resolvedEntries);
    }

    private StellnulaConfigChange toChange(ConfigChangeResponse response)
            throws IOException, InterruptedException {
        return new StellnulaConfigChange(
                StellnulaChangeType.valueOf(defaultText(response.type(), "UPSERT")),
                toEntry(response.entry(), true));
    }

    private StellnulaConfigEntry toEntry(ConfigEntryResponse response, boolean resolveReference)
            throws IOException, InterruptedException {
        if (response == null) {
            throw new IllegalArgumentException("config entry response must not be null");
        }
        if (resolveReference
                && "REFERENCE".equalsIgnoreCase(response.deliveryMode())
                && response.valueRef() != null
                && !response.valueRef().isBlank()) {
            return fetchContent(response.configId());
        }
        String configContent = decodeValue(response.configValue(), response.valueEncoding());
        ScopeResponse scope = response.scope();
        return new StellnulaConfigEntry(
                response.configId(),
                response.configKey(),
                response.contentType(),
                configContent,
                response.version(),
                response.revision(),
                response.encrypted(),
                response.deleted(),
                response.matchedType(),
                response.matchedGrayId(),
                response.matchedGrayName(),
                response.grayVersion(),
                response.valueEncoding(),
                response.deliveryMode(),
                response.valueSizeBytes(),
                response.valueRef(),
                scope == null
                        ? new StellnulaConfigScope("", "", "", "")
                        : new StellnulaConfigScope(scope.env(), scope.region(), scope.zone(), scope.cluster()));
    }

    private String decodeValue(String configContent, String valueEncoding) {
        if (!"gzip+base64".equalsIgnoreCase(valueEncoding)) {
            return configContent == null ? "" : configContent;
        }
        try {
            byte[] compressed = Base64.getDecoder().decode(configContent);
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
                return new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("failed to decode compressed Stellnula config content", ex);
        }
    }

    private static String defaultText(String text, String defaultValue) {
        return text == null || text.isBlank() ? defaultValue : text;
    }

    private BootstrapRequest bootstrapRequest(long currentRevision, String pageToken) {
        return new BootstrapRequest(
                options.appId(),
                options.clientId(),
                options.sdkVersion(),
                options.env(),
                options.region(),
                options.zone(),
                options.cluster(),
                options.namespace(),
                options.group(),
                options.clientIp(),
                options.labels(),
                currentRevision,
                options.subscriptions(),
                List.of("grpc", "http"),
                options.apiVersion(),
                "gzip",
                options.effectivePageSize(),
                pageToken,
                options.maxPayloadBytes(),
                options.acceptLargeFileReference());
    }

    private String nextPageToken(ProtocolMetaResponse protocol) {
        if (protocol == null || !Boolean.TRUE.equals(protocol.hasMore())) {
            return "";
        }
        return protocol.nextPageToken() == null ? "" : protocol.nextPageToken();
    }

    private StellnulaClientException toClientException(int statusCode, String responseBody) {
        ErrorResponse error = null;
        try {
            if (responseBody != null && !responseBody.isBlank()) {
                error = objectMapper.readValue(responseBody, ErrorResponse.class);
            }
        } catch (IOException ignored) {
            // Keep the raw body when the server returns a non-standard error.
        }
        if (error == null) {
            return new StellnulaClientException(statusCode, responseBody);
        }
        return new StellnulaClientException(
                statusCode,
                responseBody,
                error.code(),
                error.retryable(),
                error.retryAfterMillis(),
                error.retryBackoff(),
                error.fullSyncRequired(),
                error.fullSyncReason(),
                error.message());
    }

    private URI toGrpcUri(String address) {
        return URI.create(address.contains("://") ? address : "dns:///" + address);
    }

    public record BootstrapResult(StellnulaSnapshot snapshot, Optional<URI> grpcEndpoint) {}

    public record DeltaResult(
            long fromRevision,
            long toRevision,
            String checksum,
            List<StellnulaConfigChange> changes,
            boolean fullSyncRequired,
            String fullSyncReason) {}

    private record BootstrapRequest(
            String appId,
            String clientId,
            String sdkVersion,
            String env,
            String region,
            String zone,
            String cluster,
            String namespace,
            String group,
            String clientIp,
            Map<String, String> labels,
            long currentRevision,
            List<StellnulaSubscription> subscriptions,
            List<String> supportedTransports,
            String apiVersion,
            String acceptedCompressions,
            int pageSize,
            String pageToken,
            int maxPayloadBytes,
            boolean acceptLargeFileReference) {}

    private record ClientStateRequest(
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
            String sdkVersion,
            Map<String, String> labels,
            long localRevision,
            String localChecksum,
            boolean localFileLoaded,
            String lastSuccessSyncTime,
            List<StellnulaSubscription> subscriptions,
            String apiVersion) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BootstrapResponse(
            ProtocolMetaResponse protocol,
            long revision,
            String snapshotChecksum,
            List<ConfigEntryResponse> configs,
            List<ServerEndpointResponse> servers) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SnapshotResponse(
            ProtocolMetaResponse protocol,
            long revision,
            String checksum,
            List<ConfigEntryResponse> entries) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DeltaResponse(
            ProtocolMetaResponse protocol,
            long fromRevision,
            long toRevision,
            String checksum,
            List<ConfigChangeResponse> changes) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ProtocolMetaResponse(
            Boolean hasMore,
            String nextPageToken,
            Long retryAfterMillis,
            StellnulaRetryBackoffHint retryBackoff,
            Boolean fullSyncRequired,
            String fullSyncReason) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ClientStateResponse(Boolean accepted, long serverRevision) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ConfigContentResponse(
            ProtocolMetaResponse protocol, ConfigEntryResponse content) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ConfigChangeResponse(String type, ConfigEntryResponse entry) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ConfigEntryResponse(
            String configId,
            String configKey,
            String contentType,
            @JsonProperty("value") String configValue,
            long version,
            long revision,
            boolean encrypted,
            boolean deleted,
            String matchedType,
            Long matchedGrayId,
            String matchedGrayName,
            Long grayVersion,
            String valueEncoding,
            String deliveryMode,
            int valueSizeBytes,
            String valueRef,
            ScopeResponse scope) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ScopeResponse(String env, String region, String zone, String cluster) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ServerEndpointResponse(
            String serverId,
            String httpAddress,
            String grpcAddress,
            int weight,
            String region,
            String zone,
            boolean healthy,
            String status) {

        StellnulaServerEndpoint toEndpoint() {
            return new StellnulaServerEndpoint(
                    serverId, httpAddress, grpcAddress, weight, region, zone, healthy, status);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ErrorResponse(
            String code,
            String message,
            boolean retryable,
            long retryAfterMillis,
            StellnulaRetryBackoffHint retryBackoff,
            boolean fullSyncRequired,
            String fullSyncReason) {}
}
