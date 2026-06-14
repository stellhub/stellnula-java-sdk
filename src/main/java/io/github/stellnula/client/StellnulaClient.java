package io.github.stellnula.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.stellnula.config.StellnulaChangeType;
import io.github.stellnula.config.StellnulaConfigCache;
import io.github.stellnula.config.StellnulaConfigChange;
import io.github.stellnula.config.StellnulaConfigChangeEvent;
import io.github.stellnula.config.StellnulaConfigChangePredicate;
import io.github.stellnula.config.StellnulaConfigChangeSource;
import io.github.stellnula.config.StellnulaConfigConverter;
import io.github.stellnula.config.StellnulaConfigEntry;
import io.github.stellnula.config.StellnulaConfigEventListener;
import io.github.stellnula.config.StellnulaConfigListener;
import io.github.stellnula.config.StellnulaConfigPrefixes;
import io.github.stellnula.config.StellnulaListenerRegistration;
import io.github.stellnula.config.StellnulaSnapshot;
import io.github.stellnula.grpc.StellnulaGrpcWatchClient;
import io.github.stellnula.internal.StellnulaJson;
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
import io.github.stellnula.protocol.grpc.v1.WatchStatus;
import io.github.stellnula.store.StellnulaSnapshotStore;
import io.github.stellnula.telemetry.StellnulaTelemetry;
import io.github.stellnula.transport.StellnulaHttpTransport;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;

public final class StellnulaClient implements AutoCloseable {

    private final StellnulaClientOptions options;
    private final ObjectMapper objectMapper;
    private final StellnulaHttpTransport httpTransport;
    private final StellnulaTelemetry telemetry;
    private final StellnulaConfigCache cache = new StellnulaConfigCache();
    private final StellnulaSnapshotStore snapshotStore;
    private final List<StellnulaConfigListener> listeners = new CopyOnWriteArrayList<>();
    private final List<FilteredListenerRegistration> eventListeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicReference<StellnulaClientState> state =
            new AtomicReference<>(StellnulaClientState.NEW);
    private final ExecutorService watchExecutor;
    private final ExecutorService listenerExecutor;
    private final boolean ownsWatchExecutor;
    private final boolean ownsListenerExecutor;
    private volatile StellnulaGrpcWatchClient watchClient;
    private volatile boolean localFileLoaded;
    private volatile long lastServerRefreshNanos;

    public StellnulaClient(StellnulaClientOptions options) {
        this(options, new OkHttpClient());
    }

    public StellnulaClient(StellnulaClientOptions options, OkHttpClient httpClient) {
        this(options, httpClient, newWatchExecutor(), newListenerExecutor(), true, true);
    }

    public StellnulaClient(
            StellnulaClientOptions options,
            OkHttpClient httpClient,
            ExecutorService watchExecutor,
            ExecutorService listenerExecutor) {
        this(options, httpClient, watchExecutor, listenerExecutor, false, false);
    }

    StellnulaClient(
            StellnulaClientOptions options, OkHttpClient httpClient, ObjectMapper objectMapper) {
        this(options, httpClient, objectMapper, newWatchExecutor(), newListenerExecutor(), true, true);
    }

    private StellnulaClient(
            StellnulaClientOptions options,
            OkHttpClient httpClient,
            ExecutorService watchExecutor,
            ExecutorService listenerExecutor,
            boolean ownsWatchExecutor,
            boolean ownsListenerExecutor) {
        this(
                options,
                httpClient,
                StellnulaJson.objectMapper(),
                watchExecutor,
                listenerExecutor,
                ownsWatchExecutor,
                ownsListenerExecutor);
    }

    private StellnulaClient(
            StellnulaClientOptions options,
            OkHttpClient httpClient,
            ObjectMapper objectMapper,
            ExecutorService watchExecutor,
            ExecutorService listenerExecutor,
            boolean ownsWatchExecutor,
            boolean ownsListenerExecutor) {
        this.options = Objects.requireNonNull(options, "options must not be null");
        ObjectMapper mapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.objectMapper = mapper;
        this.httpTransport =
                new StellnulaHttpTransport(
                        options, Objects.requireNonNull(httpClient, "httpClient must not be null"), mapper);
        this.telemetry = new StellnulaTelemetry(options, options.openTelemetry());
        this.snapshotStore = new StellnulaSnapshotStore(options.snapshotFile(), mapper);
        this.watchExecutor = Objects.requireNonNull(watchExecutor, "watchExecutor must not be null");
        this.listenerExecutor =
                Objects.requireNonNull(listenerExecutor, "listenerExecutor must not be null");
        this.ownsWatchExecutor = ownsWatchExecutor;
        this.ownsListenerExecutor = ownsListenerExecutor;
    }

    /** 创建使用默认选项的客户端。 */
    public static StellnulaClient create(URI endpoint) {
        return new StellnulaClient(StellnulaClientOptions.builder().endpoint(endpoint).build());
    }

    /** 启动客户端，加载本地快照、同步远端配置并启动 watch。 */
    public void start() throws IOException, InterruptedException {
        state.set(StellnulaClientState.STARTING);
        loadLocalSnapshot();
        try {
            syncNow();
        } catch (IOException | InterruptedException | RuntimeException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (options.failFastOnBootstrap() || !cache.hasSnapshot()) {
                state.set(StellnulaClientState.CLOSED);
                throw ex;
            }
            state.set(StellnulaClientState.DEGRADED);
        }
        if (options.watchEnabled() && running.compareAndSet(false, true)) {
            watchExecutor.submit(this::watchLoop);
        }
        if (state.get() == StellnulaClientState.STARTING) {
            state.set(StellnulaClientState.RUNNING);
        }
    }

    /** 立即执行一次远端全量同步。 */
    public StellnulaSnapshot syncNow() throws IOException, InterruptedException {
        StellnulaHttpTransport.BootstrapResult result = bootstrapFromHttp();
        StellnulaSnapshot previous = cache.snapshot();
        StellnulaSnapshot snapshot = cache.replace(result.snapshot());
        persist(snapshot);
        telemetry.recordSnapshot(snapshot);
        result.grpcEndpoint().ifPresent(this::replaceWatchClient);
        lastServerRefreshNanos = System.nanoTime();
        heartbeatQuietly(snapshot);
        notifyListeners(
                StellnulaConfigChangeSource.BOOTSTRAP, previous, snapshot, diff(previous, snapshot));
        state.set(StellnulaClientState.RUNNING);
        return snapshot;
    }

    /** 通过 HTTP 补偿路径拉取全量配置。 */
    public StellnulaSnapshot fetchFull() throws IOException, InterruptedException {
        StellnulaSnapshot previous = cache.snapshot();
        StellnulaSnapshot snapshot = cache.replace(fetchFullFromDataPlane());
        persist(snapshot);
        telemetry.recordSnapshot(snapshot);
        heartbeatQuietly(snapshot);
        notifyListeners(
                StellnulaConfigChangeSource.FULL_SYNC, previous, snapshot, diff(previous, snapshot));
        state.set(StellnulaClientState.RUNNING);
        return snapshot;
    }

    /** 获取客户端生命周期状态。 */
    public StellnulaClientState state() {
        return state.get();
    }

    /** 获取当前内存快照。 */
    public StellnulaSnapshot snapshot() {
        return cache.snapshot();
    }

    /** 获取当前配置值 Map。 */
    public java.util.Map<String, String> asMap() {
        return cache.snapshot().asMap();
    }

    /** 按配置 key 或 configId 查询配置值。 */
    public Optional<String> getValue(String key) {
        return cache.getValue(key);
    }

    /** 按配置 key 或 configId 查询并转换配置值。 */
    public <T> Optional<T> getValue(String key, Class<T> type) {
        Objects.requireNonNull(type, "type must not be null");
        return getValue(key)
                .map(configContent -> StellnulaConfigConverter.convertValue(key, configContent, type));
    }

    /** 按配置 key 或 configId 查询必填配置值。 */
    public String getRequiredValue(String key) {
        return cache.snapshot().requireValue(key);
    }

    /** 按配置 key 或 configId 查询并转换必填配置值。 */
    public <T> T getRequiredValue(String key, Class<T> type) {
        return getValue(key, type)
                .orElseThrow(() -> new IllegalArgumentException("Stellnula config is missing: " + key));
    }

    /** 查询整数配置值。 */
    public Optional<Integer> getInt(String key) {
        return getValue(key, Integer.class);
    }

    /** 查询长整数配置值。 */
    public Optional<Long> getLong(String key) {
        return getValue(key, Long.class);
    }

    /** 查询布尔配置值。 */
    public Optional<Boolean> getBoolean(String key) {
        return getValue(key, Boolean.class);
    }

    /** 查询 Duration 配置值。 */
    public Optional<Duration> getDuration(String key) {
        return getValue(key, Duration.class);
    }

    /** 获取指定前缀下的配置值 Map，返回 key 会移除前缀。 */
    public Map<String, String> getByPrefix(String prefix) {
        String normalized = StellnulaConfigPrefixes.normalize(prefix);
        Map<String, String> values = cache.snapshot().asMap();
        if (normalized.isBlank()) {
            return values;
        }
        Map<String, String> result = new LinkedHashMap<>();
        String prefixWithDot = normalized + ".";
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getKey().startsWith(prefixWithDot)) {
                result.put(entry.getKey().substring(prefixWithDot.length()), entry.getValue());
            }
        }
        return Map.copyOf(result);
    }

    /** 将指定前缀下的配置绑定为目标类型。 */
    public <T> T bindPrefix(String prefix, Class<T> type) {
        Objects.requireNonNull(type, "type must not be null");
        return StellnulaConfigConverter.bind(objectMapper, toNestedMap(getByPrefix(prefix)), type);
    }

    /** 按配置 key 或 configId 查询配置项。 */
    public Optional<StellnulaConfigEntry> getEntry(String key) {
        return cache.getEntry(key);
    }

    /** 注册配置变更监听器。 */
    public void addListener(StellnulaConfigListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener must not be null"));
    }

    /** 移除配置变更监听器。 */
    public void removeListener(StellnulaConfigListener listener) {
        listeners.remove(listener);
    }

    /** 注册配置变更事件监听器。 */
    public StellnulaListenerRegistration listen(StellnulaConfigEventListener listener) {
        return listen(StellnulaConfigChangePredicate.all(), listener, false);
    }

    /** 注册配置变更事件监听器，并可选择立即发送当前快照。 */
    public StellnulaListenerRegistration listen(
            StellnulaConfigEventListener listener, boolean notifyCurrent) {
        return listen(StellnulaConfigChangePredicate.all(), listener, notifyCurrent);
    }

    /** 注册指定配置 key 或 configId 的变更监听器。 */
    public StellnulaListenerRegistration listenKey(
            String key, StellnulaConfigEventListener listener) {
        return listen(StellnulaConfigChangePredicate.key(key), listener, false);
    }

    /** 注册指定配置前缀的变更监听器。 */
    public StellnulaListenerRegistration listenPrefix(
            String prefix, StellnulaConfigEventListener listener) {
        return listen(StellnulaConfigChangePredicate.prefix(prefix), listener, false);
    }

    /** 注册自定义变更过滤器监听器。 */
    public StellnulaListenerRegistration listen(
            StellnulaConfigChangePredicate predicate, StellnulaConfigEventListener listener) {
        return listen(predicate, listener, false);
    }

    /** 注册自定义变更过滤器监听器，并可选择立即发送当前快照。 */
    public StellnulaListenerRegistration listen(
            StellnulaConfigChangePredicate predicate,
            StellnulaConfigEventListener listener,
            boolean notifyCurrent) {
        FilteredListenerRegistration registration =
                new FilteredListenerRegistration(
                        Objects.requireNonNull(predicate, "predicate must not be null"),
                        Objects.requireNonNull(listener, "listener must not be null"));
        eventListeners.add(registration);
        if (notifyCurrent) {
            notifyEventListener(registration, initialEvent(cache.snapshot()));
        }
        return registration;
    }

    /** 查询管理面配置。 */
    public Optional<StellnulaConfigRecord> getConfig(String configId)
            throws IOException, InterruptedException {
        return httpTransport.getConfig(
                configId, options.env(), options.region(), options.zone(), options.cluster());
    }

    /** 查询指定作用域下的管理面配置。 */
    public Optional<StellnulaConfigRecord> getConfig(
            String configId, String env, String region, String zone, String cluster)
            throws IOException, InterruptedException {
        return httpTransport.getConfig(configId, env, region, zone, cluster);
    }

    /** 新增或更新管理面配置。 */
    public StellnulaConfigMutationResponse upsertConfig(
            String configId, StellnulaConfigRequest request) throws IOException, InterruptedException {
        return httpTransport.upsertConfig(configId, request);
    }

    /** 删除管理面配置。 */
    public StellnulaConfigMutationResponse deleteConfig(
            String configId, StellnulaConfigDeleteRequest request)
            throws IOException, InterruptedException {
        return httpTransport.deleteConfig(configId, request);
    }

    /** 复制公共配置到目标环境。 */
    public StellnulaConfigMutationResponse replicatePublicConfig(
            String configId, StellnulaPublicConfigReplicationRequest request)
            throws IOException, InterruptedException {
        return httpTransport.replicatePublicConfig(configId, request);
    }

    /** 查询服务治理规则列表。 */
    public List<StellnulaGovernanceRuleRecord> listGovernanceRules(
            String env, String ownerId, String ruleType, String targetService, String status)
            throws IOException, InterruptedException {
        return httpTransport.listGovernanceRules(env, ownerId, ruleType, targetService, status);
    }

    /** 查询服务治理规则。 */
    public Optional<StellnulaGovernanceRuleRecord> getGovernanceRule(String ruleId)
            throws IOException, InterruptedException {
        return getGovernanceRule(
                ruleId, options.env(), options.region(), options.zone(), options.cluster());
    }

    /** 查询指定作用域下的服务治理规则。 */
    public Optional<StellnulaGovernanceRuleRecord> getGovernanceRule(
            String ruleId, String env, String region, String zone, String cluster)
            throws IOException, InterruptedException {
        return httpTransport.getGovernanceRule(ruleId, env, region, zone, cluster);
    }

    /** 新增或更新服务治理规则。 */
    public StellnulaConfigMutationResponse upsertGovernanceRule(
            String ruleId, StellnulaGovernanceRuleRequest request)
            throws IOException, InterruptedException {
        return httpTransport.upsertGovernanceRule(ruleId, request);
    }

    /** 删除服务治理规则。 */
    public StellnulaConfigMutationResponse deleteGovernanceRule(
            String ruleId, StellnulaGovernanceRuleDeleteRequest request)
            throws IOException, InterruptedException {
        return httpTransport.deleteGovernanceRule(ruleId, request);
    }

    /** 查询配置灰度规则。 */
    public Optional<StellnulaGrayRuleRecord> getGrayRule(String configId, String grayName)
            throws IOException, InterruptedException {
        return getGrayRule(
                configId, grayName, options.env(), options.region(), options.zone(), options.cluster());
    }

    /** 查询指定作用域下的配置灰度规则。 */
    public Optional<StellnulaGrayRuleRecord> getGrayRule(
            String configId, String grayName, String env, String region, String zone, String cluster)
            throws IOException, InterruptedException {
        return httpTransport.getGrayRule(configId, grayName, env, region, zone, cluster);
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
            throws IOException, InterruptedException {
        return httpTransport.getGrayImpact(configId, grayName, env, region, zone, cluster, limit);
    }

    /** 创建、更新或发布配置灰度规则。 */
    public StellnulaGrayMutationResponse upsertGrayRule(
            String configId, String grayName, StellnulaGrayRuleRequest request)
            throws IOException, InterruptedException {
        return httpTransport.upsertGrayRule(configId, grayName, request);
    }

    /** 结束配置灰度规则。 */
    public StellnulaGrayMutationResponse endGrayRule(
            String configId, String grayName, StellnulaGrayRuleEndRequest request)
            throws IOException, InterruptedException {
        return httpTransport.endGrayRule(configId, grayName, request);
    }

    URI buildConfigUri(String namespace, String key) {
        requireText(namespace, "namespace");
        requireText(key, "key");
        return resolve(
                "/api/v1/client/configs/full?namespace="
                        + encode(namespace)
                        + "&subscriptions=CONFIG:"
                        + encode(key));
    }

    private void loadLocalSnapshot() throws IOException {
        long startedNanos = System.nanoTime();
        Optional<StellnulaSnapshot> localSnapshot;
        try {
            localSnapshot = snapshotStore.load();
            telemetry.recordSnapshotOperation(
                    "load", localSnapshot.isPresent() ? "success" : "empty", startedNanos);
        } catch (IOException | RuntimeException ex) {
            telemetry.recordSnapshotOperation("load", "failure", startedNanos);
            telemetry.recordError("snapshot_load", "local", ex);
            throw ex;
        }
        if (localSnapshot.isPresent()) {
            cache.replace(localSnapshot.get());
            telemetry.recordSnapshot(localSnapshot.get());
            localFileLoaded = true;
        }
    }

    private void watchLoop() {
        int failureAttempt = 0;
        while (running.get()) {
            try {
                StellnulaGrpcWatchClient client = watchClient;
                if (client == null) {
                    syncNow();
                    sleep(options.retryDelay());
                    continue;
                }
                StellnulaGrpcWatchClient.WatchResult result = watchFromGrpc(client, cache.snapshot());
                handleWatchResult(result);
                httpTransport.markGrpcEndpointSuccess(client.endpoint());
                refreshServerEndpointIfDue();
                failureAttempt = 0;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                running.set(false);
            } catch (StellnulaClientException ex) {
                markCurrentWatchClientFailure();
                telemetry.recordError("watch", "grpc", ex);
                handleWatchFailure(ex);
                sleep(retryDelay(ex, failureAttempt++));
            } catch (Exception ex) {
                state.set(StellnulaClientState.DEGRADED);
                markCurrentWatchClientFailure();
                telemetry.recordError("watch", "grpc", ex);
                invalidateWatchClient();
                tryCompensateAfterWatchFailure();
                sleep(StellnulaRetryBackoffHint.defaults(options.retryDelay()).delay(failureAttempt++));
            }
        }
    }

    private void handleWatchResult(StellnulaGrpcWatchClient.WatchResult result)
            throws IOException, InterruptedException {
        if (result.fullSyncRequired() || result.status() == WatchStatus.CLIENT_TOO_OLD) {
            fetchFull();
            return;
        }
        if (result.status() == WatchStatus.NO_CHANGE) {
            StellnulaSnapshot snapshot =
                    cache.updateMetadata(result.latestRevision(), result.latestChecksum());
            persist(snapshot);
            heartbeatQuietly(snapshot);
            return;
        }
        if (result.status() == WatchStatus.CHANGED) {
            StellnulaSnapshot previous = cache.snapshot();
            List<StellnulaConfigChange> changes = withPreviousEntries(result.changes(), previous);
            StellnulaSnapshot snapshot =
                    cache.apply(result.latestRevision(), result.latestChecksum(), changes);
            if (!snapshot.checksumMatches()) {
                fetchFull();
                return;
            }
            persist(snapshot);
            heartbeatQuietly(snapshot);
            notifyListeners(StellnulaConfigChangeSource.WATCH, previous, snapshot, changes);
            state.set(StellnulaClientState.RUNNING);
        }
    }

    private void handleWatchFailure(StellnulaClientException ex) {
        state.set(StellnulaClientState.DEGRADED);
        invalidateWatchClient();
        if (ex.fullSyncRequired()) {
            try {
                fetchFull();
                return;
            } catch (Exception ignored) {
                // Retry loop will continue with the server-provided backoff.
            }
        }
        if (ex.retryable()) {
            tryCompensateAfterWatchFailure();
        }
    }

    private void tryCompensateAfterWatchFailure() {
        try {
            StellnulaHttpTransport.DeltaResult delta =
                    fetchDeltaFromDataPlane(cache.snapshot().revision());
            if (delta.fullSyncRequired()) {
                fetchFull();
                return;
            }
            if (!delta.changes().isEmpty()) {
                StellnulaSnapshot previous = cache.snapshot();
                List<StellnulaConfigChange> changes = withPreviousEntries(delta.changes(), previous);
                StellnulaSnapshot snapshot = cache.apply(delta.toRevision(), delta.checksum(), changes);
                if (!snapshot.checksumMatches()) {
                    fetchFull();
                    return;
                }
                persist(snapshot);
                notifyListeners(StellnulaConfigChangeSource.RECOVERY, previous, snapshot, changes);
                state.set(StellnulaClientState.RUNNING);
            }
        } catch (Exception ignored) {
            // Retry loop will perform the next recovery attempt.
        }
    }

    private void heartbeatQuietly(StellnulaSnapshot snapshot) {
        long startedNanos = System.nanoTime();
        String transport = "http";
        try {
            StellnulaGrpcWatchClient client = watchClient;
            if (client == null) {
                httpTransport.heartbeat(snapshot, localFileLoaded);
            } else {
                transport = "grpc";
                client.reportClientState(snapshot, localFileLoaded);
            }
            telemetry.recordOperation("heartbeat", transport, "success", startedNanos);
        } catch (Exception ignored) {
            telemetry.recordOperation("heartbeat", transport, "failure", startedNanos);
            telemetry.recordError("heartbeat", transport, ignored);
            // Heartbeat failure must not break local config availability.
        }
    }

    private void refreshServerEndpointIfDue() throws IOException, InterruptedException {
        long now = System.nanoTime();
        if (lastServerRefreshNanos == 0
                || now - lastServerRefreshNanos >= options.serverRefreshInterval().toNanos()) {
            syncNow();
        }
    }

    private StellnulaSnapshot fetchFullFromDataPlane() throws IOException, InterruptedException {
        StellnulaGrpcWatchClient client = watchClient;
        if (client == null) {
            return fetchFullFromHttp();
        }
        try {
            return fetchFullFromGrpc(client);
        } catch (StellnulaClientException ex) {
            return fetchFullFromHttp();
        }
    }

    private StellnulaHttpTransport.DeltaResult fetchDeltaFromDataPlane(long fromRevision)
            throws IOException, InterruptedException {
        StellnulaGrpcWatchClient client = watchClient;
        if (client == null) {
            return fetchDeltaFromHttp(fromRevision);
        }
        try {
            return fetchDeltaFromGrpc(client, fromRevision);
        } catch (StellnulaClientException ex) {
            return fetchDeltaFromHttp(fromRevision);
        }
    }

    private void persist(StellnulaSnapshot snapshot) throws IOException {
        long startedNanos = System.nanoTime();
        try {
            snapshotStore.save(snapshot);
            telemetry.recordSnapshotOperation("save", "success", startedNanos);
        } catch (IOException | RuntimeException ex) {
            telemetry.recordSnapshotOperation("save", "failure", startedNanos);
            telemetry.recordError("snapshot_save", "local", ex);
            throw ex;
        }
        localFileLoaded = true;
    }

    private StellnulaHttpTransport.BootstrapResult bootstrapFromHttp()
            throws IOException, InterruptedException {
        long startedNanos = System.nanoTime();
        try {
            StellnulaHttpTransport.BootstrapResult result =
                    httpTransport.bootstrap(cache.snapshot().revision());
            telemetry.recordOperation("bootstrap", "http", "success", startedNanos);
            return result;
        } catch (IOException | InterruptedException | RuntimeException ex) {
            telemetry.recordOperation("bootstrap", "http", "failure", startedNanos);
            telemetry.recordError("bootstrap", "http", ex);
            throw ex;
        }
    }

    private StellnulaSnapshot fetchFullFromHttp() throws IOException, InterruptedException {
        long startedNanos = System.nanoTime();
        try {
            StellnulaSnapshot snapshot = httpTransport.fetchFull();
            telemetry.recordOperation("fetch_full", "http", "success", startedNanos);
            return snapshot;
        } catch (IOException | InterruptedException | RuntimeException ex) {
            telemetry.recordOperation("fetch_full", "http", "failure", startedNanos);
            telemetry.recordError("fetch_full", "http", ex);
            throw ex;
        }
    }

    private StellnulaSnapshot fetchFullFromGrpc(StellnulaGrpcWatchClient client)
            throws IOException, InterruptedException {
        long startedNanos = System.nanoTime();
        try {
            StellnulaSnapshot snapshot = client.fetchFull();
            telemetry.recordOperation("fetch_full", "grpc", "success", startedNanos);
            return snapshot;
        } catch (IOException | InterruptedException | RuntimeException ex) {
            telemetry.recordOperation("fetch_full", "grpc", "failure", startedNanos);
            telemetry.recordError("fetch_full", "grpc", ex);
            throw ex;
        }
    }

    private StellnulaHttpTransport.DeltaResult fetchDeltaFromHttp(long fromRevision)
            throws IOException, InterruptedException {
        long startedNanos = System.nanoTime();
        try {
            StellnulaHttpTransport.DeltaResult result = httpTransport.fetchDelta(fromRevision);
            telemetry.recordOperation("fetch_delta", "http", "success", startedNanos);
            return result;
        } catch (IOException | InterruptedException | RuntimeException ex) {
            telemetry.recordOperation("fetch_delta", "http", "failure", startedNanos);
            telemetry.recordError("fetch_delta", "http", ex);
            throw ex;
        }
    }

    private StellnulaHttpTransport.DeltaResult fetchDeltaFromGrpc(
            StellnulaGrpcWatchClient client, long fromRevision) throws IOException, InterruptedException {
        long startedNanos = System.nanoTime();
        try {
            StellnulaHttpTransport.DeltaResult result = client.fetchDelta(fromRevision);
            telemetry.recordOperation("fetch_delta", "grpc", "success", startedNanos);
            return result;
        } catch (IOException | InterruptedException | RuntimeException ex) {
            telemetry.recordOperation("fetch_delta", "grpc", "failure", startedNanos);
            telemetry.recordError("fetch_delta", "grpc", ex);
            throw ex;
        }
    }

    private StellnulaGrpcWatchClient.WatchResult watchFromGrpc(
            StellnulaGrpcWatchClient client, StellnulaSnapshot snapshot)
            throws IOException, InterruptedException {
        long startedNanos = System.nanoTime();
        try {
            StellnulaGrpcWatchClient.WatchResult result = client.watch(snapshot);
            telemetry.recordOperation("watch", "grpc", "success", startedNanos);
            return result;
        } catch (IOException | InterruptedException | RuntimeException ex) {
            telemetry.recordOperation("watch", "grpc", "failure", startedNanos);
            throw ex;
        }
    }

    private void notifyListeners(
            StellnulaConfigChangeSource source,
            StellnulaSnapshot previous,
            StellnulaSnapshot snapshot,
            List<StellnulaConfigChange> changes) {
        if (changes == null || changes.isEmpty()) {
            return;
        }
        for (StellnulaConfigListener listener : listeners) {
            listenerExecutor.execute(
                    () -> {
                        try {
                            listener.onConfigChanged(snapshot, changes);
                            telemetry.recordListenerNotification("legacy", "success");
                        } catch (RuntimeException ignored) {
                            telemetry.recordListenerNotification("legacy", "failure");
                            // Listener failures must not interrupt config synchronization.
                        }
                    });
        }
        StellnulaConfigChangeEvent event =
                new StellnulaConfigChangeEvent(source, previous, snapshot, changes);
        telemetry.recordConfigChanges(source, changes);
        for (FilteredListenerRegistration registration : eventListeners) {
            notifyEventListener(registration, event);
        }
    }

    private List<StellnulaConfigChange> diff(StellnulaSnapshot previous, StellnulaSnapshot current) {
        Map<String, StellnulaConfigEntry> previousEntries =
                new LinkedHashMap<>(previous.toConfigIdMap());
        List<StellnulaConfigChange> changes = new ArrayList<>();
        for (StellnulaConfigEntry entry : current.entries()) {
            StellnulaConfigEntry old = previousEntries.remove(entry.configId());
            if (!entry.equals(old)) {
                changes.add(new StellnulaConfigChange(StellnulaChangeType.UPSERT, entry, old));
            }
        }
        for (StellnulaConfigEntry deleted : previousEntries.values()) {
            changes.add(new StellnulaConfigChange(StellnulaChangeType.DELETE, deleted, deleted));
        }
        return changes;
    }

    private List<StellnulaConfigChange> withPreviousEntries(
            List<StellnulaConfigChange> changes, StellnulaSnapshot previous) {
        Map<String, StellnulaConfigEntry> previousEntries = previous.toConfigIdMap();
        List<StellnulaConfigChange> enriched = new ArrayList<>();
        for (StellnulaConfigChange change : changes) {
            if (change.previousEntry() != null) {
                enriched.add(change);
            } else {
                enriched.add(
                        new StellnulaConfigChange(
                                change.type(), change.entry(), previousEntries.get(change.entry().configId())));
            }
        }
        return enriched;
    }

    private StellnulaConfigChangeEvent initialEvent(StellnulaSnapshot snapshot) {
        List<StellnulaConfigChange> changes =
                snapshot.entries().stream()
                        .map(entry -> new StellnulaConfigChange(StellnulaChangeType.UPSERT, entry))
                        .toList();
        return new StellnulaConfigChangeEvent(
                StellnulaConfigChangeSource.INITIAL,
                new StellnulaSnapshot(0, "", List.of()),
                snapshot,
                changes);
    }

    private void notifyEventListener(
            FilteredListenerRegistration registration, StellnulaConfigChangeEvent event) {
        List<StellnulaConfigChange> matchedChanges =
                event.changes().stream().filter(registration.predicate::test).toList();
        if (matchedChanges.isEmpty()) {
            return;
        }
        StellnulaConfigChangeEvent filteredEvent =
                new StellnulaConfigChangeEvent(
                        event.source(), event.previousSnapshot(), event.currentSnapshot(), matchedChanges);
        listenerExecutor.execute(
                () -> {
                    try {
                        registration.listener.onConfigChanged(filteredEvent);
                        telemetry.recordListenerNotification("event", "success");
                    } catch (RuntimeException ignored) {
                        telemetry.recordListenerNotification("event", "failure");
                        // Listener failures must not interrupt config synchronization.
                    }
                });
    }

    private Map<String, Object> toNestedMap(Map<String, String> values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            putNested(result, entry.getKey(), entry.getValue());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private void putNested(Map<String, Object> target, String key, String configContent) {
        String[] parts = key.split("\\.");
        Map<String, Object> current = target;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isBlank()) {
                continue;
            }
            if (i == parts.length - 1) {
                current.put(part, configContent);
            } else {
                Object next = current.computeIfAbsent(part, ignored -> new LinkedHashMap<String, Object>());
                if (next instanceof Map<?, ?> map) {
                    current = (Map<String, Object>) map;
                } else {
                    Map<String, Object> replacement = new LinkedHashMap<>();
                    current.put(part, replacement);
                    current = replacement;
                }
            }
        }
    }

    private synchronized void replaceWatchClient(URI endpoint) {
        StellnulaGrpcWatchClient current = watchClient;
        if (current != null && current.endpoint().equals(endpoint)) {
            return;
        }
        if (current != null) {
            current.close();
        }
        watchClient = new StellnulaGrpcWatchClient(options, endpoint, httpTransport);
    }

    private synchronized void invalidateWatchClient() {
        StellnulaGrpcWatchClient current = watchClient;
        if (current != null) {
            current.close();
            watchClient = null;
        }
    }

    private void markCurrentWatchClientFailure() {
        StellnulaGrpcWatchClient current = watchClient;
        if (current != null) {
            httpTransport.markGrpcEndpointFailure(current.endpoint());
        }
    }

    private Duration retryDelay(StellnulaClientException ex, int attempt) {
        if (ex.retryAfterMillis() > 0) {
            return Duration.ofMillis(ex.retryAfterMillis());
        }
        StellnulaRetryBackoffHint hint =
                ex.retryBackoff() == null
                        ? StellnulaRetryBackoffHint.defaults(options.retryDelay())
                        : ex.retryBackoff();
        return hint.delay(attempt);
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            running.set(false);
        }
    }

    private URI resolve(String path) {
        String endpoint = options.endpoint().toString();
        String normalizedEndpoint =
                endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        return URI.create(normalizedEndpoint + path);
    }

    private static String encode(String text) {
        return java.net.URLEncoder.encode(text, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    private static void requireText(String text, String fieldName) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }

    @Override
    public void close() {
        running.set(false);
        state.set(StellnulaClientState.CLOSED);
        if (ownsWatchExecutor) {
            watchExecutor.shutdownNow();
        }
        if (ownsListenerExecutor) {
            listenerExecutor.shutdownNow();
        }
        StellnulaGrpcWatchClient current = watchClient;
        if (current != null) {
            current.close();
        }
        telemetry.close();
    }

    private static ExecutorService newWatchExecutor() {
        return Executors.newSingleThreadExecutor(new WatchThreadFactory());
    }

    private static ExecutorService newListenerExecutor() {
        return Executors.newSingleThreadExecutor(new ListenerThreadFactory());
    }

    private static final class WatchThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "stellnula-config-watch");
            thread.setDaemon(true);
            return thread;
        }
    }

    private static final class ListenerThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "stellnula-config-listener");
            thread.setDaemon(true);
            return thread;
        }
    }

    private final class FilteredListenerRegistration implements StellnulaListenerRegistration {

        private final StellnulaConfigChangePredicate predicate;
        private final StellnulaConfigEventListener listener;

        private FilteredListenerRegistration(
                StellnulaConfigChangePredicate predicate, StellnulaConfigEventListener listener) {
            this.predicate = predicate;
            this.listener = listener;
        }

        @Override
        public void close() {
            eventListeners.remove(this);
        }
    }
}
