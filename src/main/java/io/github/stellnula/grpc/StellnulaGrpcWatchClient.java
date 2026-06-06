package io.github.stellnula.grpc;

import io.github.stellnula.client.*;
import io.github.stellnula.config.*;
import io.github.stellnula.internal.*;
import io.github.stellnula.protocol.grpc.v1.ClientContext;
import io.github.stellnula.protocol.grpc.v1.ClientStateRequest;
import io.github.stellnula.protocol.grpc.v1.ConfigDelta;
import io.github.stellnula.protocol.grpc.v1.ConfigSnapshot;
import io.github.stellnula.protocol.grpc.v1.FetchDeltaRequest;
import io.github.stellnula.protocol.grpc.v1.FetchFullRequest;
import io.github.stellnula.protocol.grpc.v1.ProtocolOptions;
import io.github.stellnula.protocol.grpc.v1.StellnulaConfigServiceGrpc;
import io.github.stellnula.protocol.grpc.v1.SubscriptionFilter;
import io.github.stellnula.protocol.grpc.v1.WatchRequest;
import io.github.stellnula.protocol.grpc.v1.WatchResponse;
import io.github.stellnula.protocol.grpc.v1.WatchStatus;
import io.github.stellnula.transport.*;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import okhttp3.OkHttpClient;

public final class StellnulaGrpcWatchClient implements AutoCloseable {

    private final StellnulaClientOptions options;
    private final URI endpoint;
    private final StellnulaHttpTransport httpTransport;
    private final ManagedChannel channel;
    private final StellnulaConfigServiceGrpc.StellnulaConfigServiceBlockingStub stub;

    public StellnulaGrpcWatchClient(StellnulaClientOptions options, URI endpoint) {
        this(
                options,
                endpoint,
                new StellnulaHttpTransport(options, new OkHttpClient(), StellnulaJson.objectMapper()));
    }

    public StellnulaGrpcWatchClient(
            StellnulaClientOptions options, URI endpoint, StellnulaHttpTransport httpTransport) {
        this.options = options;
        this.endpoint = endpoint;
        this.httpTransport = httpTransport;
        NettyChannelBuilder builder = NettyChannelBuilder.forTarget(toTarget(endpoint));
        if (options.grpcPlaintext()) {
            builder.usePlaintext();
        }
        this.channel = builder.build();
        StellnulaConfigServiceGrpc.StellnulaConfigServiceBlockingStub resolvedStub =
                StellnulaConfigServiceGrpc.newBlockingStub(channel)
                        .withInterceptors(new DynamicAuthorizationInterceptor(options));
        this.stub = resolvedStub;
    }

    /** 通过 gRPC 拉取全量配置。 */
    public StellnulaSnapshot fetchFull() throws IOException, InterruptedException {
        List<StellnulaConfigEntry> entries = new ArrayList<>();
        ConfigSnapshot response;
        String pageToken = "";
        do {
            try {
                response =
                        stub.withDeadlineAfter(options.requestTimeout().toMillis(), TimeUnit.MILLISECONDS)
                                .fetchFull(
                                        FetchFullRequest.newBuilder()
                                                .setContext(toGrpcContext())
                                                .setOptions(toProtocolOptions(pageToken))
                                                .build());
            } catch (StatusRuntimeException ex) {
                throw toClientException(ex);
            }
            for (io.github.stellnula.protocol.grpc.v1.ConfigEntry entry : response.getEntriesList()) {
                entries.add(toEntry(entry));
            }
            pageToken = nextPageToken(response.getMeta());
        } while (!pageToken.isBlank());
        if (!StellnulaChecksum.matches(response.getChecksum(), entries)) {
            throw checksumMismatch();
        }
        return new StellnulaSnapshot(response.getRevision(), response.getChecksum(), entries);
    }

    /** 通过 gRPC 拉取增量配置。 */
    public StellnulaHttpTransport.DeltaResult fetchDelta(long fromRevision)
            throws IOException, InterruptedException {
        List<StellnulaConfigChange> changes = new ArrayList<>();
        ConfigDelta response;
        String pageToken = "";
        boolean fullSyncRequired = false;
        String fullSyncReason = "";
        do {
            try {
                response =
                        stub.withDeadlineAfter(options.requestTimeout().toMillis(), TimeUnit.MILLISECONDS)
                                .fetchDelta(
                                        FetchDeltaRequest.newBuilder()
                                                .setContext(toGrpcContext())
                                                .setFromRevision(fromRevision)
                                                .setOptions(toProtocolOptions(pageToken))
                                                .build());
            } catch (StatusRuntimeException ex) {
                throw toClientException(ex);
            }
            for (io.github.stellnula.protocol.grpc.v1.ConfigChange change : response.getChangesList()) {
                changes.add(toChange(change));
            }
            fullSyncRequired = fullSyncRequired || response.getFullSyncRequired();
            fullSyncReason =
                    response.getFullSyncReason().isBlank() ? fullSyncReason : response.getFullSyncReason();
            pageToken = nextPageToken(response.getMeta());
        } while (!pageToken.isBlank());
        return new StellnulaHttpTransport.DeltaResult(
                response.getFromRevision(),
                response.getToRevision(),
                response.getChecksum(),
                changes,
                fullSyncRequired,
                fullSyncReason);
    }

    /** 通过 gRPC 上报客户端状态。 */
    public void reportClientState(StellnulaSnapshot snapshot, boolean localFileLoaded) {
        try {
            stub.withDeadlineAfter(options.requestTimeout().toMillis(), TimeUnit.MILLISECONDS)
                    .reportClientState(
                            ClientStateRequest.newBuilder()
                                    .setContext(toGrpcContext())
                                    .setLocalRevision(snapshot.revision())
                                    .setLocalChecksum(snapshot.checksum())
                                    .setLocalFileLoaded(localFileLoaded)
                                    .setLastSuccessSyncTime(OffsetDateTime.now().toString())
                                    .setOptions(toProtocolOptions(""))
                                    .setSdkVersion(options.sdkVersion())
                                    .setHostName(options.hostName())
                                    .build());
        } catch (StatusRuntimeException ex) {
            throw toClientException(ex);
        }
    }

    public URI endpoint() {
        return endpoint;
    }

    /** 执行一次 gRPC 长轮询 watch。 */
    public WatchResult watch(StellnulaSnapshot snapshot) throws IOException, InterruptedException {
        List<StellnulaConfigChange> changes = new ArrayList<>();
        WatchResponse response;
        String pageToken = "";
        do {
            try {
                response =
                        stub.withDeadlineAfter(options.watchTimeout().toMillis() + 1000, TimeUnit.MILLISECONDS)
                                .watch(
                                        WatchRequest.newBuilder()
                                                .setContext(toGrpcContext())
                                                .setCurrentRevision(snapshot.revision())
                                                .setSnapshotChecksum(snapshot.checksum())
                                                .setTimeoutMillis(Math.toIntExact(options.watchTimeout().toMillis()))
                                                .setOptions(toProtocolOptions(pageToken))
                                                .build());
            } catch (StatusRuntimeException ex) {
                throw toClientException(ex);
            }
            for (io.github.stellnula.protocol.grpc.v1.ConfigChange change : response.getChangesList()) {
                changes.add(toChange(change));
            }
            pageToken = nextPageToken(response);
            if (response.getStatus() != WatchStatus.CHANGED
                    || response.getFullSyncRequired()
                    || response.getMeta().getFullSyncRequired()) {
                pageToken = "";
            }
        } while (!pageToken.isBlank());
        return new WatchResult(
                response.getStatus(),
                response.getLatestRevision(),
                response.getLatestChecksum(),
                response.getFullSyncRequired(),
                response.getFullSyncReason(),
                changes);
    }

    private ClientContext toGrpcContext() {
        ClientContext.Builder builder =
                ClientContext.newBuilder()
                        .setAppId(options.appId())
                        .setClientId(options.clientId())
                        .setEnv(options.env())
                        .setRegion(options.region())
                        .setZone(options.zone())
                        .setCluster(options.cluster())
                        .setNamespace(options.namespace())
                        .setGroup(options.group())
                        .setClientIp(options.clientIp())
                        .putAllLabels(options.labels());
        for (StellnulaSubscription subscription : options.subscriptions()) {
            builder.addSubscriptions(
                    SubscriptionFilter.newBuilder()
                            .setGroup(subscription.group())
                            .setSubscriptionType(subscription.subscriptionType())
                            .setSubscriptionKey(subscription.subscriptionKey())
                            .build());
        }
        return builder.build();
    }

    private ProtocolOptions toProtocolOptions(String pageToken) {
        return ProtocolOptions.newBuilder()
                .setApiVersion(options.apiVersion())
                .setSdkVersion(options.sdkVersion())
                .addAcceptedCompressions("gzip")
                .setPageSize(options.effectivePageSize())
                .setPageToken(pageToken == null ? "" : pageToken)
                .setMaxPayloadBytes(options.maxPayloadBytes())
                .setAcceptLargeFileReference(options.acceptLargeFileReference())
                .build();
    }

    private String nextPageToken(WatchResponse response) {
        if (!response.hasMeta() || !response.getMeta().getHasMore()) {
            return "";
        }
        return response.getMeta().getNextPageToken();
    }

    private String nextPageToken(io.github.stellnula.protocol.grpc.v1.ProtocolMeta meta) {
        if (meta == null || !meta.getHasMore()) {
            return "";
        }
        return meta.getNextPageToken();
    }

    private StellnulaConfigChange toChange(io.github.stellnula.protocol.grpc.v1.ConfigChange change)
            throws IOException, InterruptedException {
        return new StellnulaConfigChange(toChangeType(change.getType()), toEntry(change.getEntry()));
    }

    private StellnulaChangeType toChangeType(io.github.stellnula.protocol.grpc.v1.ChangeType type) {
        return switch (type) {
            case DELETE -> StellnulaChangeType.DELETE;
            case GRAY_CHANGED -> StellnulaChangeType.GRAY_CHANGED;
            case UPSERT, CHANGE_TYPE_UNSPECIFIED, UNRECOGNIZED -> StellnulaChangeType.UPSERT;
        };
    }

    private StellnulaConfigEntry toEntry(io.github.stellnula.protocol.grpc.v1.ConfigEntry entry)
            throws IOException, InterruptedException {
        if ("REFERENCE".equalsIgnoreCase(entry.getDeliveryMode()) && !entry.getValueRef().isBlank()) {
            return httpTransport.fetchContent(entry.getConfigId());
        }
        return new StellnulaConfigEntry(
                entry.getConfigId(),
                entry.getConfigKey(),
                entry.getContentType(),
                decodeValue(entry.getValue(), entry.getValueEncoding()),
                entry.getVersion(),
                entry.getRevision(),
                entry.getEncrypted(),
                entry.getDeleted(),
                entry.getMatchedType(),
                entry.getMatchedGrayId() == 0 ? null : entry.getMatchedGrayId(),
                entry.getMatchedGrayName().isBlank() ? null : entry.getMatchedGrayName(),
                entry.getGrayVersion() == 0 ? null : entry.getGrayVersion(),
                entry.getValueEncoding(),
                entry.getDeliveryMode(),
                entry.getValueSizeBytes(),
                entry.getValueRef(),
                new StellnulaConfigScope(
                        options.env(), options.region(), options.zone(), options.cluster()));
    }

    private StellnulaClientException checksumMismatch() {
        return new StellnulaClientException(
                0,
                "",
                "CHECKSUM_MISMATCH",
                true,
                0,
                null,
                true,
                "LOCAL_CHECKSUM_MISMATCH",
                "Stellnula gRPC snapshot checksum mismatch");
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

    private static String toTarget(URI uri) {
        if (uri.getScheme() == null || "dns".equalsIgnoreCase(uri.getScheme())) {
            return uri.toString();
        }
        if ("grpc".equalsIgnoreCase(uri.getScheme()) || "grpcs".equalsIgnoreCase(uri.getScheme())) {
            return uri.getAuthority();
        }
        return uri.toString();
    }

    private StellnulaClientException toClientException(StatusRuntimeException ex) {
        Metadata trailers = Status.trailersFromThrowable(ex);
        String errorCode = metadata(trailers, "stellnula-error-code");
        boolean retryable = Boolean.parseBoolean(metadata(trailers, "stellnula-retryable"));
        long retryAfterMillis = parseLong(metadata(trailers, "stellnula-retry-after-millis"));
        boolean fullSyncRequired =
                Boolean.parseBoolean(metadata(trailers, "stellnula-full-sync-required"));
        String fullSyncReason = metadata(trailers, "stellnula-full-sync-reason");
        Status status = ex.getStatus();
        return new StellnulaClientException(
                0,
                "",
                errorCode,
                retryable || status.getCode() == Status.Code.UNAVAILABLE,
                retryAfterMillis,
                null,
                fullSyncRequired,
                fullSyncReason,
                status.getDescription() == null ? status.toString() : status.getDescription());
    }

    private String metadata(Metadata metadata, String name) {
        if (metadata == null) {
            return "";
        }
        String metadataValue = metadata.get(Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER));
        return metadataValue == null ? "" : metadataValue;
    }

    private long parseLong(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    @Override
    public void close() {
        channel.shutdown();
        try {
            if (!channel.awaitTermination(
                    options.grpcShutdownTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                channel.shutdownNow();
                channel.awaitTermination(options.grpcShutdownTimeout().toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException ex) {
            channel.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static final class DynamicAuthorizationInterceptor implements ClientInterceptor {

        private final StellnulaClientOptions options;

        private DynamicAuthorizationInterceptor(StellnulaClientOptions options) {
            this.options = options;
        }

        @Override
        public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
            return new ForwardingClientCall.SimpleForwardingClientCall<>(
                    next.newCall(method, callOptions)) {
                @Override
                public void start(Listener<RespT> responseListener, Metadata headers) {
                    String token = options.currentApiToken();
                    if (!token.isBlank()) {
                        headers.put(
                                Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                                "Bearer " + token);
                    }
                    super.start(responseListener, headers);
                }
            };
        }
    }

    public record WatchResult(
            WatchStatus status,
            long latestRevision,
            String latestChecksum,
            boolean fullSyncRequired,
            String fullSyncReason,
            List<StellnulaConfigChange> changes) {}
}
