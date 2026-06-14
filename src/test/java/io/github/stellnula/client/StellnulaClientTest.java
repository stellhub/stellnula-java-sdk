package io.github.stellnula.client;


import io.github.stellnula.config.*;
import io.github.stellnula.grpc.*;
import io.github.stellnula.store.*;
import io.github.stellnula.transport.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.stellnula.protocol.grpc.v1.ChangeType;
import io.github.stellnula.protocol.grpc.v1.ConfigChange;
import io.github.stellnula.protocol.grpc.v1.ConfigEntry;
import io.github.stellnula.protocol.grpc.v1.ConfigSnapshot;
import io.github.stellnula.protocol.grpc.v1.ClientStateRequest;
import io.github.stellnula.protocol.grpc.v1.ClientStateResponse;
import io.github.stellnula.protocol.grpc.v1.FetchFullRequest;
import io.github.stellnula.protocol.grpc.v1.ProtocolMeta;
import io.github.stellnula.protocol.grpc.v1.WatchRequest;
import io.github.stellnula.protocol.grpc.v1.WatchResponse;
import io.github.stellnula.protocol.grpc.v1.WatchStatus;
import io.github.stellnula.protocol.grpc.v1.StellnulaConfigServiceGrpc;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StellnulaClientTest {

  @Test
  void buildsEncodedConfigUri() {
    StellnulaClient client = StellnulaClient.create(URI.create("http://localhost:8080"));

    URI uri = client.buildConfigUri("default env", "application/dev.yaml");

    assertEquals(
        "http://localhost:8080/api/v1/client/configs/full?namespace=default%20env&subscriptions=CONFIG:application%2Fdev.yaml",
        uri.toString());
  }

  @Test
  void rejectsBlankConfigKey() {
    StellnulaClient client = StellnulaClient.create(URI.create("http://localhost:8080"));

    assertThrows(IllegalArgumentException.class, () -> client.buildConfigUri("default", " "));
  }

  @Test
  void rejectsNonPositiveTimeout() {
    assertThrows(IllegalArgumentException.class, () -> StellnulaClientOptions.builder()
        .endpoint(URI.create("http://localhost:8080"))
        .requestTimeout(Duration.ZERO)
        .build());
  }

  @Test
  void persistsAndLoadsSnapshot(@TempDir Path tempDir) throws IOException {
    Path snapshotDirectory = tempDir.resolve("snapshot");
    StellnulaSnapshotStore store = new StellnulaSnapshotStore(snapshotDirectory, new ObjectMapper());
    StellnulaConfigEntry entry = entry("server.port", "8080", 7, false);
    StellnulaConfigEntry nested = entry("application/dev.yaml", "enabled: true", 7, false);
    StellnulaSnapshot snapshot =
        new StellnulaSnapshot(7, StellnulaChecksum.calculate(List.of(entry, nested)), List.of(entry, nested));

    store.save(snapshot);

    assertEquals("8080", Files.readString(snapshotDirectory.resolve("configs").resolve("server.port")));
    assertEquals(
        "enabled: true",
        Files.readString(snapshotDirectory.resolve("configs").resolve("application").resolve("dev.yaml")));
    StellnulaSnapshot loaded = store.load().orElseThrow();
    assertEquals(7, loaded.revision());
    assertEquals("8080", loaded.findValue("server.port").orElseThrow());
    assertEquals("enabled: true", loaded.findValue("application/dev.yaml").orElseThrow());
  }

  @Test
  @Disabled
  void connectsLocalStellnulaServiceAndPrintsKeyValues(@TempDir Path tempDir) throws Exception {
    URI endpoint = URI.create("http://localhost:8060");
    org.junit.jupiter.api.Assumptions.assumeTrue(
        localServiceAvailable(endpoint),
        "local stellnula-service is not listening on http://localhost:8060");

    StellnulaClientOptions options =
        StellnulaClientOptions.builder()
            .endpoint(endpoint)
            .apiToken(System.getProperty("stellnula.local.token", ""))
            .appId("stellhub.core.middleware.stellcloud.admin")
            .clientId("stellnula-java-sdk-local-test")
            .env(System.getProperty("stellnula.local.env", "dev"))
            .namespace(System.getProperty("stellnula.local.namespace", "default"))
            .cluster(System.getProperty("stellnula.local.cluster", "default"))
            .group(System.getProperty("stellnula.local.group", "default"))
            .requestTimeout(Duration.ofSeconds(5))
            .watchEnabled(false)
            .snapshotDirectory(tempDir.resolve("stellnula-local-snapshot"))
            .build();

    try (StellnulaClient client = new StellnulaClient(options, new OkHttpClient())) {
      StellnulaSnapshot snapshot = client.syncNow();

      assertTrue(snapshot.checksumMatches());
      System.out.printf(
          "Stellnula local snapshot revision=%d checksum=%s entries=%d%n",
          snapshot.revision(), snapshot.checksum(), snapshot.entries().size());
      if (snapshot.entries().isEmpty()) {
        System.out.println("Stellnula local config: no key-value entries returned");
      }
      snapshot.entries().stream()
          .sorted(Comparator.comparing(StellnulaConfigEntry::configKey))
          .forEach(
              entry ->
                  System.out.printf(
                      "Stellnula local config: key=%s, value=%s, configId=%s, contentType=%s, revision=%d%n",
                      entry.configKey(),
                      entry.configValue(),
                      entry.configId(),
                      entry.contentType(),
                      entry.revision()));
    }
  }

  @Test
  void appliesDeltaToMemorySnapshot() {
    StellnulaConfigCache cache = new StellnulaConfigCache();
    cache.replace(
        new StellnulaSnapshot(
            1,
            "sha256:one",
            List.of(entry("server.port", "8080", 1, false), entry("feature.x", "false", 1, false))));

    StellnulaSnapshot snapshot =
        cache.apply(
            2,
            "sha256:two",
            List.of(
                new StellnulaConfigChange(
                    StellnulaChangeType.UPSERT, entry("server.port", "9090", 2, false)),
                new StellnulaConfigChange(
                    StellnulaChangeType.DELETE, entry("feature.x", "", 2, true))));

    assertEquals("9090", snapshot.findValue("server.port").orElseThrow());
    assertTrue(snapshot.findValue("feature.x").isEmpty());
    assertEquals(2, snapshot.revision());
  }

  @Test
  void updatesMetadataForInvisibleRevision() {
    StellnulaConfigCache cache = new StellnulaConfigCache();
    cache.replace(new StellnulaSnapshot(1, "sha256:one", List.of(entry("server.port", "8080", 1, false))));

    StellnulaSnapshot snapshot = cache.updateMetadata(2, "sha256:one");

    assertEquals(2, snapshot.revision());
    assertEquals("8080", snapshot.requireValue("server.port"));
  }

  @Test
  void syncNowNotifiesListenersAndIsolatesListenerFailure() throws Exception {
    StellnulaConfigEntry entry = entry("server.port", "8080", 7, false);
    String checksum = StellnulaChecksum.calculate(List.of(entry));
    HttpServer server =
        httpServer(
            exchange -> {
              if (exchange.getRequestURI().getPath().equals("/api/v1/client/bootstrap")) {
                writeJson(
                    exchange,
                    """
                    {
                      "protocol": {"hasMore": false, "nextPageToken": ""},
                      "revision": 7,
                      "snapshotChecksum": "%s",
                      "configs": [
                        {
                          "configId": "server.port",
                          "configKey": "server.port",
                          "contentType": "KV",
                          "value": "8080",
                          "version": 7,
                          "revision": 7,
                          "encrypted": false,
                          "deleted": false,
                          "matchedType": "BASE",
                          "valueEncoding": "identity",
                          "deliveryMode": "INLINE",
                          "valueSizeBytes": 4,
                          "valueRef": "",
                          "scope": {"env": "dev", "region": "default", "zone": "default", "cluster": "default"}
                        }
                      ],
                      "servers": []
                    }
                    """
                        .formatted(checksum));
                return;
              }
              if (exchange.getRequestURI().getPath().equals("/api/v1/client/heartbeat")) {
                writeJson(exchange, "{\"accepted\":true,\"serverRevision\":7}");
                return;
              }
              exchange.sendResponseHeaders(404, -1);
            });
    try (StellnulaClient client = new StellnulaClient(options(server.getAddress().getPort()))) {
      CountDownLatch latch = new CountDownLatch(1);
      client.addListener((snapshot, changes) -> {
        throw new IllegalStateException("listener should be isolated");
      });
      client.addListener((snapshot, changes) -> latch.countDown());

      client.syncNow();

      assertTrue(latch.await(5, TimeUnit.SECONDS));
      assertEquals("8080", client.getRequiredValue("server.port"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void usesProvidedExecutorsAndDoesNotShutdownThemOnClose() throws Exception {
    StellnulaConfigEntry entry = entry("server.port", "8080", 7, false);
    String checksum = StellnulaChecksum.calculate(List.of(entry));
    ExecutorService watchExecutor = Executors.newSingleThreadExecutor();
    ExecutorService listenerExecutor =
        Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "external-listener"));
    HttpServer server =
        httpServer(
            exchange -> {
              if (exchange.getRequestURI().getPath().equals("/api/v1/client/bootstrap")) {
                writeJson(exchange, bootstrapBody(7, checksum, List.of(entry)));
                return;
              }
              if (exchange.getRequestURI().getPath().equals("/api/v1/client/heartbeat")) {
                writeJson(exchange, "{\"accepted\":true,\"serverRevision\":7}");
                return;
              }
              exchange.sendResponseHeaders(404, -1);
            });
    try {
      AtomicReference<String> listenerThread = new AtomicReference<>();
      CountDownLatch latch = new CountDownLatch(1);
      StellnulaClient client =
          new StellnulaClient(
              options(server.getAddress().getPort()),
              new OkHttpClient(),
              watchExecutor,
              listenerExecutor);
      client.addListener(
          (snapshot, changes) -> {
            listenerThread.set(Thread.currentThread().getName());
            latch.countDown();
          });

      client.syncNow();
      assertTrue(latch.await(5, TimeUnit.SECONDS));
      assertEquals("external-listener", listenerThread.get());

      client.close();

      assertFalse(watchExecutor.isShutdown());
      assertFalse(listenerExecutor.isShutdown());
    } finally {
      server.stop(0);
      watchExecutor.shutdownNow();
      listenerExecutor.shutdownNow();
    }
  }

  @Test
  void typedAccessAndPrefixBinding() throws Exception {
    StellnulaConfigEntry port = entry("server.port", "8080", 7, false);
    StellnulaConfigEntry enabled = entry("server.enabled", "true", 7, false);
    StellnulaConfigEntry timeout = entry("client.timeout", "5s", 7, false);
    String checksum = StellnulaChecksum.calculate(List.of(timeout, port, enabled));
    HttpServer server =
        httpServer(
            exchange -> {
              if (exchange.getRequestURI().getPath().equals("/api/v1/client/bootstrap")) {
                writeJson(exchange, bootstrapBody(7, checksum, List.of(port, enabled, timeout)));
                return;
              }
              if (exchange.getRequestURI().getPath().equals("/api/v1/client/heartbeat")) {
                writeJson(exchange, "{\"accepted\":true,\"serverRevision\":7}");
                return;
              }
              exchange.sendResponseHeaders(404, -1);
            });
    try (StellnulaClient client = new StellnulaClient(options(server.getAddress().getPort()))) {
      client.syncNow();

      assertEquals(8080, client.getInt("server.port").orElseThrow());
      assertTrue(client.getBoolean("server.enabled").orElseThrow());
      assertEquals(Duration.ofSeconds(5), client.getDuration("client.timeout").orElseThrow());
      assertEquals("8080", client.getByPrefix("server").get("port"));
      assertEquals(new ServerBinding(8080, true), client.bindPrefix("server", ServerBinding.class));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void prefixListenerReceivesFilteredEventWithPreviousValue() throws Exception {
    AtomicInteger bootstrapCalls = new AtomicInteger();
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<StellnulaConfigChangeEvent> eventRef = new AtomicReference<>();
    HttpServer server =
        httpServer(
            exchange -> {
              if (exchange.getRequestURI().getPath().equals("/api/v1/client/bootstrap")) {
                boolean firstCall = bootstrapCalls.incrementAndGet() == 1;
                StellnulaConfigEntry port = entry("server.port", firstCall ? "8080" : "9090", firstCall ? 1 : 2, false);
                StellnulaConfigEntry feature = entry("feature.enabled", "true", firstCall ? 1 : 2, false);
                String checksum = StellnulaChecksum.calculate(List.of(port, feature));
                writeJson(exchange, bootstrapBody(firstCall ? 1 : 2, checksum, List.of(port, feature)));
                return;
              }
              if (exchange.getRequestURI().getPath().equals("/api/v1/client/heartbeat")) {
                writeJson(exchange, "{\"accepted\":true,\"serverRevision\":2}");
                return;
              }
              exchange.sendResponseHeaders(404, -1);
            });
    try (StellnulaClient client = new StellnulaClient(options(server.getAddress().getPort()))) {
      client.syncNow();
      StellnulaListenerRegistration registration =
          client.listenPrefix("server", event -> {
            eventRef.set(event);
            latch.countDown();
          });

      client.syncNow();

      assertTrue(latch.await(5, TimeUnit.SECONDS));
      StellnulaConfigChangeEvent event = eventRef.get();
      assertEquals(StellnulaConfigChangeSource.BOOTSTRAP, event.source());
      assertEquals(1, event.changes().size());
      assertEquals("server.port", event.changes().get(0).entry().configKey());
      assertEquals("8080", event.changes().get(0).previousValue());
      assertEquals("9090", event.changes().get(0).currentValue());
      registration.close();
    } finally {
      server.stop(0);
    }
  }

  @Test
  void recordsOpenTelemetryMetrics() throws Exception {
    InMemoryMetricReader reader = InMemoryMetricReader.create();
    SdkMeterProvider meterProvider = SdkMeterProvider.builder().registerMetricReader(reader).build();
    OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder().setMeterProvider(meterProvider).build();
    StellnulaConfigEntry port = entry("server.port", "8080", 7, false);
    String checksum = StellnulaChecksum.calculate(List.of(port));
    HttpServer server =
        httpServer(
            exchange -> {
              if (exchange.getRequestURI().getPath().equals("/api/v1/client/bootstrap")) {
                writeJson(exchange, bootstrapBody(7, checksum, List.of(port)));
                return;
              }
              if (exchange.getRequestURI().getPath().equals("/api/v1/client/heartbeat")) {
                writeJson(exchange, "{\"accepted\":true,\"serverRevision\":7}");
                return;
              }
              exchange.sendResponseHeaders(404, -1);
            });
    try (StellnulaClient client =
        new StellnulaClient(
            StellnulaClientOptions.builder()
                .endpoint(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
                .watchEnabled(false)
                .openTelemetry(openTelemetry)
                .build())) {
      client.syncNow();

      Set<String> metricNames = metricNames(reader.collectAllMetrics());
      assertTrue(metricNames.contains("stellnula.client.operations"));
      assertTrue(metricNames.contains("stellnula.client.operation.duration"));
      assertTrue(metricNames.contains("stellnula.client.config.changes"));
      assertTrue(metricNames.contains("stellnula.client.snapshot.operations"));
      assertTrue(metricNames.contains("stellnula.client.revision"));
      assertTrue(metricNames.contains("stellnula.client.config.entries"));
    } finally {
      server.stop(0);
      meterProvider.close();
    }
  }

  @Test
  void stellnulaClientUsesProvidedOkHttpClient() throws Exception {
    AtomicBoolean customHeaderSeen = new AtomicBoolean();
    StellnulaConfigEntry port = entry("server.port", "8080", 7, false);
    String checksum = StellnulaChecksum.calculate(List.of(port));
    HttpServer server =
        httpServer(
            exchange -> {
              if (exchange.getRequestURI().getPath().equals("/api/v1/client/bootstrap")) {
                customHeaderSeen.set("yes".equals(exchange.getRequestHeaders().getFirst("X-Custom-OkHttp")));
                writeJson(exchange, bootstrapBody(7, checksum, List.of(port)));
                return;
              }
              if (exchange.getRequestURI().getPath().equals("/api/v1/client/heartbeat")) {
                writeJson(exchange, "{\"accepted\":true,\"serverRevision\":7}");
                return;
              }
              exchange.sendResponseHeaders(404, -1);
            });
    OkHttpClient okHttpClient =
        new OkHttpClient.Builder()
            .addInterceptor(
                chain ->
                    chain.proceed(
                        chain.request().newBuilder().header("X-Custom-OkHttp", "yes").build()))
            .build();
    try (StellnulaClient client =
        new StellnulaClient(options(server.getAddress().getPort()), okHttpClient)) {
      client.syncNow();

      assertTrue(customHeaderSeen.get());
      assertEquals("8080", client.getRequiredValue("server.port"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void fetchFullReadsPagesAndReferenceContent() throws Exception {
    StellnulaConfigEntry inline = entry("server.port", "8080", 7, false);
    StellnulaConfigEntry file = entry("logging.file", "logs/app.log", 7, false);
    String checksum = StellnulaChecksum.calculate(List.of(file, inline));
    HttpServer server =
        httpServer(
            exchange -> {
              String path = exchange.getRequestURI().getPath();
              String query = exchange.getRequestURI().getRawQuery();
              if (path.equals("/api/v1/client/configs/full") && query.contains("pageToken=1")) {
                writeJson(
                    exchange,
                    """
                    {
                      "protocol": {"hasMore": false, "nextPageToken": ""},
                      "revision": 7,
                      "checksum": "%s",
                      "entries": [
                        {
                          "configId": "logging.file",
                          "configKey": "logging.file",
                          "contentType": "FILE",
                          "value": "",
                          "version": 7,
                          "revision": 7,
                          "encrypted": false,
                          "deleted": false,
                          "matchedType": "BASE",
                          "valueEncoding": "identity",
                          "deliveryMode": "REFERENCE",
                          "valueSizeBytes": 12,
                          "valueRef": "stellnula://configs/logging.file/revisions/7",
                          "scope": {"env": "dev", "region": "default", "zone": "default", "cluster": "default"}
                        }
                      ]
                    }
                    """
                        .formatted(checksum));
                return;
              }
              if (path.equals("/api/v1/client/configs/full")) {
                writeJson(
                    exchange,
                    """
                    {
                      "protocol": {"hasMore": true, "nextPageToken": "1"},
                      "revision": 7,
                      "checksum": "%s",
                      "entries": [
                        {
                          "configId": "server.port",
                          "configKey": "server.port",
                          "contentType": "KV",
                          "value": "8080",
                          "version": 7,
                          "revision": 7,
                          "encrypted": false,
                          "deleted": false,
                          "matchedType": "BASE",
                          "valueEncoding": "identity",
                          "deliveryMode": "INLINE",
                          "valueSizeBytes": 4,
                          "valueRef": "",
                          "scope": {"env": "dev", "region": "default", "zone": "default", "cluster": "default"}
                        }
                      ]
                    }
                    """
                        .formatted(checksum));
                return;
              }
              if (path.equals("/api/v1/client/configs/content")) {
                writeJson(
                    exchange,
                    """
                    {
                      "protocol": {"hasMore": false, "nextPageToken": ""},
                      "content": {
                        "configId": "logging.file",
                        "configKey": "logging.file",
                        "contentType": "FILE",
                        "value": "logs/app.log",
                        "version": 7,
                        "revision": 7,
                        "encrypted": false,
                        "deleted": false,
                        "matchedType": "BASE",
                        "valueEncoding": "identity",
                        "deliveryMode": "INLINE",
                        "valueSizeBytes": 12,
                        "valueRef": "",
                        "scope": {"env": "dev", "region": "default", "zone": "default", "cluster": "default"}
                      }
                    }
                    """);
                return;
              }
              exchange.sendResponseHeaders(404, -1);
            });
    try {
      StellnulaHttpTransport transport = transport(server);

      StellnulaSnapshot snapshot = transport.fetchFull();

      assertEquals(7, snapshot.revision());
      assertEquals("8080", snapshot.requireValue("server.port"));
      assertEquals("logs/app.log", snapshot.requireValue("logging.file"));
      assertTrue(snapshot.checksumMatches());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void parsesHttpErrorMetadata() throws Exception {
    HttpServer server =
        httpServer(
            exchange ->
                writeJson(
                    exchange,
                    429,
                    """
                    {
                      "code": "TOO_MANY_WATCHES",
                      "message": "too many concurrent watch requests",
                      "retryable": true,
                      "retryAfterMillis": 1234,
                      "retryBackoff": {
                        "initialDelayMillis": 100,
                        "maxDelayMillis": 5000,
                        "multiplier": 2.0,
                        "jitterRatio": 0.0
                      },
                      "fullSyncRequired": true,
                      "fullSyncReason": "EVENT_WINDOW_EXPIRED"
                    }
                    """));
    try {
      StellnulaClientException ex =
          assertThrows(StellnulaClientException.class, () -> transport(server).fetchFull());

      assertEquals("TOO_MANY_WATCHES", ex.errorCode());
      assertTrue(ex.retryable());
      assertEquals(1234, ex.retryAfterMillis());
      assertTrue(ex.fullSyncRequired());
      assertEquals("EVENT_WINDOW_EXPIRED", ex.fullSyncReason());
      assertEquals(100, ex.retryBackoff().initialDelayMillis());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void grpcWatchReturnsChangedResult() throws Exception {
    Server server =
        NettyServerBuilder.forPort(0)
            .addService(
                new StellnulaConfigServiceGrpc.StellnulaConfigServiceImplBase() {
                  @Override
                  public void watch(
                      WatchRequest request, StreamObserver<WatchResponse> responseObserver) {
                    responseObserver.onNext(
                        WatchResponse.newBuilder()
                            .setStatus(WatchStatus.CHANGED)
                            .setLatestRevision(2)
                            .setLatestChecksum("sha256:next")
                            .build());
                    responseObserver.onCompleted();
                  }
                })
            .build()
            .start();
    try (StellnulaGrpcWatchClient client =
        new StellnulaGrpcWatchClient(options(server.getPort()), URI.create("grpc://127.0.0.1:" + server.getPort()))) {
      StellnulaGrpcWatchClient.WatchResult result =
          client.watch(new StellnulaSnapshot(1, "sha256:old", List.of()));

      assertEquals(WatchStatus.CHANGED, result.status());
      assertEquals(2, result.latestRevision());
    } finally {
      server.shutdownNow();
    }
  }

  @Test
  void grpcWatchReadsPaginatedChanges() throws Exception {
    Server server =
        NettyServerBuilder.forPort(0)
            .addService(
                new StellnulaConfigServiceGrpc.StellnulaConfigServiceImplBase() {
                  @Override
                  public void watch(
                      WatchRequest request, StreamObserver<WatchResponse> responseObserver) {
                    boolean secondPage = "1".equals(request.getOptions().getPageToken());
                    responseObserver.onNext(
                        WatchResponse.newBuilder()
                            .setStatus(WatchStatus.CHANGED)
                            .setLatestRevision(2)
                            .setLatestChecksum("sha256:next")
                            .setMeta(
                                ProtocolMeta.newBuilder()
                                    .setHasMore(!secondPage)
                                    .setNextPageToken(secondPage ? "" : "1")
                                    .build())
                            .addChanges(
                                ConfigChange.newBuilder()
                                    .setType(ChangeType.UPSERT)
                                    .setEntry(grpcEntry(secondPage ? "b" : "a", secondPage ? "2" : "1"))
                                    .build())
                            .build());
                    responseObserver.onCompleted();
                  }
                })
            .build()
            .start();
    try (StellnulaGrpcWatchClient client =
        new StellnulaGrpcWatchClient(options(server.getPort()), URI.create("grpc://127.0.0.1:" + server.getPort()))) {
      StellnulaGrpcWatchClient.WatchResult result =
          client.watch(new StellnulaSnapshot(1, "sha256:old", List.of()));

      assertEquals(2, result.changes().size());
    } finally {
      server.shutdownNow();
    }
  }

  @Test
  void grpcFetchFullAndReportClientState() throws Exception {
    AtomicBoolean reported = new AtomicBoolean();
    StellnulaConfigEntry sdkEntry = entry("server.port", "8080", 7, false);
    String checksum = StellnulaChecksum.calculate(List.of(sdkEntry));
    Server server =
        NettyServerBuilder.forPort(0)
            .addService(
                new StellnulaConfigServiceGrpc.StellnulaConfigServiceImplBase() {
                  @Override
                  public void fetchFull(
                      FetchFullRequest request, StreamObserver<ConfigSnapshot> responseObserver) {
                    responseObserver.onNext(
                        ConfigSnapshot.newBuilder()
                            .setRevision(7)
                            .setChecksum(checksum)
                            .addEntries(grpcEntry("server.port", "8080"))
                            .build());
                    responseObserver.onCompleted();
                  }

                  @Override
                  public void reportClientState(
                      ClientStateRequest request, StreamObserver<ClientStateResponse> responseObserver) {
                    reported.set(request.getLocalRevision() == 7);
                    responseObserver.onNext(
                        ClientStateResponse.newBuilder().setAccepted(true).setServerRevision(7).build());
                    responseObserver.onCompleted();
                  }
                })
            .build()
            .start();
    try (StellnulaGrpcWatchClient client =
        new StellnulaGrpcWatchClient(options(server.getPort()), URI.create("grpc://127.0.0.1:" + server.getPort()))) {
      StellnulaSnapshot snapshot = client.fetchFull();
      client.reportClientState(snapshot, true);

      assertEquals("8080", snapshot.requireValue("server.port"));
      assertTrue(reported.get());
    } finally {
      server.shutdownNow();
    }
  }

  @Test
  void grpcFetchFullResolvesReferenceContentThroughHttpFallback() throws Exception {
    StellnulaConfigEntry file = entry("logging.file", "logs/app.log", 7, false);
    String checksum = StellnulaChecksum.calculate(List.of(file));
    HttpServer httpServer =
        httpServer(
            exchange -> {
              if (exchange.getRequestURI().getPath().equals("/api/v1/client/configs/content")) {
                writeJson(
                    exchange,
                    """
                    {
                      "protocol": {"hasMore": false, "nextPageToken": ""},
                      "content": {
                        "configId": "logging.file",
                        "configKey": "logging.file",
                        "contentType": "KV",
                        "value": "logs/app.log",
                        "version": 7,
                        "revision": 7,
                        "encrypted": false,
                        "deleted": false,
                        "matchedType": "BASE",
                        "valueEncoding": "identity",
                        "deliveryMode": "INLINE",
                        "valueSizeBytes": 12,
                        "valueRef": "",
                        "scope": {"env": "dev", "region": "default", "zone": "default", "cluster": "default"}
                      }
                    }
                    """);
                return;
              }
              exchange.sendResponseHeaders(404, -1);
            });
    Server grpcServer =
        NettyServerBuilder.forPort(0)
            .addService(
                new StellnulaConfigServiceGrpc.StellnulaConfigServiceImplBase() {
                  @Override
                  public void fetchFull(
                      FetchFullRequest request, StreamObserver<ConfigSnapshot> responseObserver) {
                    responseObserver.onNext(
                        ConfigSnapshot.newBuilder()
                            .setRevision(7)
                            .setChecksum(checksum)
                            .addEntries(grpcReferenceEntry("logging.file", 7))
                            .build());
                    responseObserver.onCompleted();
                  }
                })
            .build()
            .start();
    try (StellnulaGrpcWatchClient client =
        new StellnulaGrpcWatchClient(
            options(httpServer.getAddress().getPort()),
            URI.create("grpc://127.0.0.1:" + grpcServer.getPort()))) {
      StellnulaSnapshot snapshot = client.fetchFull();

      assertEquals("logs/app.log", snapshot.requireValue("logging.file"));
      assertTrue(snapshot.checksumMatches());
    } finally {
      grpcServer.shutdownNow();
      httpServer.stop(0);
    }
  }

  @Test
  void grpcClientReadsTokenProviderForEveryRpc() throws Exception {
    AtomicInteger tokenSequence = new AtomicInteger();
    List<String> authorizations = new CopyOnWriteArrayList<>();
    StellnulaConfigEntry sdkEntry = entry("server.port", "8080", 7, false);
    String checksum = StellnulaChecksum.calculate(List.of(sdkEntry));
    ServerInterceptor authCapture =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call,
              Metadata metadata,
              ServerCallHandler<ReqT, RespT> next) {
            authorizations.add(
                metadata.get(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)));
            return next.startCall(call, metadata);
          }
        };
    Server server =
        NettyServerBuilder.forPort(0)
            .addService(
                ServerInterceptors.intercept(
                    new StellnulaConfigServiceGrpc.StellnulaConfigServiceImplBase() {
                      @Override
                      public void fetchFull(
                          FetchFullRequest request, StreamObserver<ConfigSnapshot> responseObserver) {
                        responseObserver.onNext(
                            ConfigSnapshot.newBuilder()
                                .setRevision(7)
                                .setChecksum(checksum)
                                .addEntries(grpcEntry("server.port", "8080"))
                                .build());
                        responseObserver.onCompleted();
                      }

                      @Override
                      public void reportClientState(
                          ClientStateRequest request,
                          StreamObserver<ClientStateResponse> responseObserver) {
                        responseObserver.onNext(
                            ClientStateResponse.newBuilder()
                                .setAccepted(true)
                                .setServerRevision(7)
                                .build());
                        responseObserver.onCompleted();
                      }
                    },
                    authCapture))
            .build()
            .start();
    try (StellnulaGrpcWatchClient client =
        new StellnulaGrpcWatchClient(
            StellnulaClientOptions.builder()
                .endpoint(URI.create("http://127.0.0.1:" + server.getPort()))
                .tokenProvider(() -> "token-" + tokenSequence.incrementAndGet())
                .build(),
            URI.create("grpc://127.0.0.1:" + server.getPort()))) {
      StellnulaSnapshot snapshot = client.fetchFull();
      client.reportClientState(snapshot, false);

      assertEquals(List.of("Bearer token-1", "Bearer token-2"), authorizations);
    } finally {
      server.shutdownNow();
    }
  }

  @Test
  void bootstrapSkipsFailedGrpcEndpointDuringCooldown() throws Exception {
    String checksum = StellnulaChecksum.calculate(List.of());
    HttpServer server =
        httpServer(
            exchange -> {
              if (exchange.getRequestURI().getPath().equals("/api/v1/client/bootstrap")) {
                writeJson(
                    exchange,
                    """
                    {
                      "protocol": {"hasMore": false, "nextPageToken": ""},
                      "revision": 1,
                      "snapshotChecksum": "%s",
                      "configs": [],
                      "servers": [
                        {
                          "serverId": "first",
                          "httpAddress": "http://127.0.0.1:8081",
                          "grpcAddress": "grpc://127.0.0.1:1111",
                          "weight": 100,
                          "region": "default",
                          "zone": "default",
                          "healthy": true,
                          "status": "UP"
                        },
                        {
                          "serverId": "second",
                          "httpAddress": "http://127.0.0.1:8082",
                          "grpcAddress": "grpc://127.0.0.1:2222",
                          "weight": 1,
                          "region": "default",
                          "zone": "default",
                          "healthy": true,
                          "status": "UP"
                        }
                      ]
                    }
                    """
                        .formatted(checksum));
                return;
              }
              exchange.sendResponseHeaders(404, -1);
            });
    try {
      StellnulaClientOptions options =
          StellnulaClientOptions.builder()
              .endpoint(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
              .serverFailureCooldown(Duration.ofSeconds(30))
              .serverSelector((servers, key) -> Optional.of(servers.get(0)))
              .build();
      StellnulaHttpTransport transport =
          new StellnulaHttpTransport(options, new OkHttpClient(), new ObjectMapper());

      transport.markGrpcEndpointFailure(URI.create("grpc://127.0.0.1:1111"));

      URI endpoint = transport.bootstrap(0).grpcEndpoint().orElseThrow();
      assertEquals(URI.create("grpc://127.0.0.1:2222"), endpoint);
    } finally {
      server.stop(0);
    }
  }

  @Test
  void watchLoopRefreshesGrpcEndpointFromBootstrap(@TempDir Path tempDir) throws Exception {
    String checksum = StellnulaChecksum.calculate(List.of());
    AtomicInteger bootstrapCalls = new AtomicInteger();
    CountDownLatch secondServerWatched = new CountDownLatch(1);
    Server firstGrpcServer =
        NettyServerBuilder.forPort(0)
            .addService(
                new StellnulaConfigServiceGrpc.StellnulaConfigServiceImplBase() {
                  @Override
                  public void watch(
                      WatchRequest request, StreamObserver<WatchResponse> responseObserver) {
                    try {
                      Thread.sleep(20);
                    } catch (InterruptedException ex) {
                      Thread.currentThread().interrupt();
                    }
                    responseObserver.onNext(
                        WatchResponse.newBuilder()
                            .setStatus(WatchStatus.NO_CHANGE)
                            .setLatestRevision(1)
                            .setLatestChecksum(checksum)
                            .build());
                    responseObserver.onCompleted();
                  }
                })
            .build()
            .start();
    Server secondGrpcServer =
        NettyServerBuilder.forPort(0)
            .addService(
                new StellnulaConfigServiceGrpc.StellnulaConfigServiceImplBase() {
                  @Override
                  public void watch(
                      WatchRequest request, StreamObserver<WatchResponse> responseObserver) {
                    secondServerWatched.countDown();
                    responseObserver.onNext(
                        WatchResponse.newBuilder()
                            .setStatus(WatchStatus.NO_CHANGE)
                            .setLatestRevision(1)
                            .setLatestChecksum(checksum)
                            .build());
                    responseObserver.onCompleted();
                  }
                })
            .build()
            .start();
    HttpServer httpServer =
        httpServer(
            exchange -> {
              if (exchange.getRequestURI().getPath().equals("/api/v1/client/bootstrap")) {
                boolean firstCall = bootstrapCalls.incrementAndGet() == 1;
                int grpcPort = firstCall ? firstGrpcServer.getPort() : secondGrpcServer.getPort();
                writeJson(
                    exchange,
                    """
                    {
                      "protocol": {"hasMore": false, "nextPageToken": ""},
                      "revision": 1,
                      "snapshotChecksum": "%s",
                      "configs": [],
                      "servers": [
                        {
                          "serverId": "%s",
                          "httpAddress": "http://127.0.0.1:%s",
                          "grpcAddress": "grpc://127.0.0.1:%s",
                          "weight": 1,
                          "region": "default",
                          "zone": "default",
                          "healthy": true,
                          "status": "UP"
                        }
                      ]
                    }
                    """
                        .formatted(
                            checksum,
                            firstCall ? "first" : "second",
                            grpcPort,
                            grpcPort));
                return;
              }
              exchange.sendResponseHeaders(404, -1);
            });
    try (StellnulaClient client =
        new StellnulaClient(
            StellnulaClientOptions.builder()
                .endpoint(URI.create("http://127.0.0.1:" + httpServer.getAddress().getPort()))
                .snapshotDirectory(tempDir.resolve("snapshot"))
                .requestTimeout(Duration.ofSeconds(2))
                .watchTimeout(Duration.ofMillis(100))
                .retryDelay(Duration.ofMillis(10))
                .serverRefreshInterval(Duration.ofMillis(1))
                .build())) {
      client.start();

      assertTrue(secondServerWatched.await(5, TimeUnit.SECONDS));
      assertTrue(bootstrapCalls.get() >= 2);
    } finally {
      httpServer.stop(0);
      firstGrpcServer.shutdownNow();
      secondGrpcServer.shutdownNow();
    }
  }

  @Test
  void grpcWatchMapsTrailersToClientException() throws Exception {
    Server server =
        NettyServerBuilder.forPort(0)
            .addService(
                new StellnulaConfigServiceGrpc.StellnulaConfigServiceImplBase() {
                  @Override
                  public void watch(
                      WatchRequest request, StreamObserver<WatchResponse> responseObserver) {
                    Metadata metadata = new Metadata();
                    metadata.put(
                        Metadata.Key.of("stellnula-error-code", Metadata.ASCII_STRING_MARSHALLER),
                        "FULL_SYNC_REQUIRED");
                    metadata.put(
                        Metadata.Key.of("stellnula-retryable", Metadata.ASCII_STRING_MARSHALLER),
                        "true");
                    metadata.put(
                        Metadata.Key.of(
                            "stellnula-retry-after-millis", Metadata.ASCII_STRING_MARSHALLER),
                        "789");
                    metadata.put(
                        Metadata.Key.of(
                            "stellnula-full-sync-required", Metadata.ASCII_STRING_MARSHALLER),
                        "true");
                    metadata.put(
                        Metadata.Key.of(
                            "stellnula-full-sync-reason", Metadata.ASCII_STRING_MARSHALLER),
                        "EVENT_WINDOW_EXPIRED");
                    responseObserver.onError(
                        Status.FAILED_PRECONDITION
                            .withDescription("full sync required")
                            .asRuntimeException(metadata));
                  }
                })
            .build()
            .start();
    try (StellnulaGrpcWatchClient client =
        new StellnulaGrpcWatchClient(options(server.getPort()), URI.create("grpc://127.0.0.1:" + server.getPort()))) {
      StellnulaClientException ex =
          assertThrows(
              StellnulaClientException.class,
              () -> client.watch(new StellnulaSnapshot(1, "sha256:old", List.of())));

      assertEquals("FULL_SYNC_REQUIRED", ex.errorCode());
      assertEquals(789, ex.retryAfterMillis());
      assertTrue(ex.fullSyncRequired());
    } finally {
      server.shutdownNow();
    }
  }

  @Test
  void quarantinesInvalidSnapshotFile(@TempDir Path tempDir) throws IOException {
    Path snapshotDirectory = tempDir.resolve("snapshot");
    Files.createDirectories(snapshotDirectory);
    Path metadataFile = snapshotDirectory.resolve(".stellnula-snapshot.json");
    Files.writeString(metadataFile, "{not-json");
    StellnulaSnapshotStore store = new StellnulaSnapshotStore(snapshotDirectory, new ObjectMapper());

    assertTrue(store.load().isEmpty());
    assertFalse(Files.exists(metadataFile));
    assertTrue(
        Files.list(snapshotDirectory).anyMatch(path -> path.getFileName().toString().endsWith(".corrupt")));
  }

  @Test
  void tokenProviderAddsAuthorizationHeader() throws Exception {
    AtomicBoolean authorized = new AtomicBoolean();
    HttpServer server =
        httpServer(
            exchange -> {
              authorized.set("Bearer dynamic-token".equals(exchange.getRequestHeaders().getFirst("Authorization")));
              writeJson(exchange, 500, "{}");
            });
    try {
      StellnulaHttpTransport transport =
          new StellnulaHttpTransport(
              StellnulaClientOptions.builder()
                  .endpoint(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
                  .tokenProvider(() -> "dynamic-token")
                  .build(),
              new OkHttpClient(),
              new ObjectMapper());

      assertThrows(StellnulaClientException.class, transport::fetchFull);
      assertTrue(authorized.get());
    } finally {
      server.stop(0);
    }
  }

  private static StellnulaConfigEntry entry(
      String configKey, String configContent, long revision, boolean deleted) {
    return new StellnulaConfigEntry(
        configKey,
        configKey,
        "KV",
        configContent,
        revision,
        revision,
        false,
        deleted,
        "BASE",
        null,
        null,
        null,
        "identity",
        "INLINE",
        configContent.length(),
        "",
        new StellnulaConfigScope("dev", "default", "default", "default"));
  }

  private static String bootstrapBody(
      long revision, String checksum, List<StellnulaConfigEntry> entries) {
    String configs =
        entries.stream()
            .map(StellnulaClientTest::entryJson)
            .collect(java.util.stream.Collectors.joining(","));
    return """
        {
          "protocol": {"hasMore": false, "nextPageToken": ""},
          "revision": %s,
          "snapshotChecksum": "%s",
          "configs": [%s],
          "servers": []
        }
        """
        .formatted(revision, checksum, configs);
  }

  private static String entryJson(StellnulaConfigEntry entry) {
    return """
        {
          "configId": "%s",
          "configKey": "%s",
          "contentType": "%s",
          "value": "%s",
          "version": %s,
          "revision": %s,
          "encrypted": %s,
          "deleted": %s,
          "matchedType": "%s",
          "valueEncoding": "%s",
          "deliveryMode": "%s",
          "valueSizeBytes": %s,
          "valueRef": "%s",
          "scope": {"env": "dev", "region": "default", "zone": "default", "cluster": "default"}
        }
        """
        .formatted(
            entry.configId(),
            entry.configKey(),
            entry.contentType(),
            entry.configValue(),
            entry.version(),
            entry.revision(),
            entry.encrypted(),
            entry.deleted(),
            entry.matchedType(),
            entry.valueEncoding(),
            entry.deliveryMode(),
            entry.valueSizeBytes(),
            entry.valueRef());
  }

  private static Set<String> metricNames(Collection<MetricData> metrics) {
    return metrics.stream()
        .filter(metric -> !metric.isEmpty())
        .map(MetricData::getName)
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  private record ServerBinding(int port, boolean enabled) {}

  private static ConfigEntry grpcEntry(String key, String configContent) {
    return ConfigEntry.newBuilder()
        .setConfigId(key)
        .setConfigKey(key)
        .setContentType("KV")
        .setValue(configContent)
        .setVersion(7)
        .setRevision(7)
        .setMatchedType("BASE")
        .setValueEncoding("identity")
        .setDeliveryMode("INLINE")
        .setValueSizeBytes(configContent.length())
        .build();
  }

  private static ConfigEntry grpcReferenceEntry(String key, long revision) {
    return ConfigEntry.newBuilder()
        .setConfigId(key)
        .setConfigKey(key)
        .setContentType("KV")
        .setValue("")
        .setVersion(revision)
        .setRevision(revision)
        .setMatchedType("BASE")
        .setValueEncoding("identity")
        .setDeliveryMode("REFERENCE")
        .setValueSizeBytes(12)
        .setValueRef("stellnula://configs/" + key + "/revisions/" + revision)
        .build();
  }

  private static StellnulaHttpTransport transport(HttpServer server) {
    return new StellnulaHttpTransport(
        options(server.getAddress().getPort()), new OkHttpClient(), new ObjectMapper());
  }

  private static StellnulaClientOptions options(int port) {
    return StellnulaClientOptions.builder()
        .endpoint(URI.create("http://127.0.0.1:" + port))
        .appId("trade.order-service")
        .clientId("trade-order-local")
        .env("dev")
        .namespace("default")
        .group("default")
        .watchEnabled(false)
        .build();
  }

  private static boolean localServiceAvailable(URI endpoint) {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(endpoint.getHost(), endpoint.getPort()), 500);
      return true;
    } catch (IOException ex) {
      return false;
    }
  }

  private static HttpServer httpServer(ExchangeHandler handler) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", handler::handle);
    server.start();
    return server;
  }

  private static void writeJson(HttpExchange exchange, String body) throws IOException {
    writeJson(exchange, 200, body);
  }

  private static void writeJson(HttpExchange exchange, int statusCode, String body)
      throws IOException {
    byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(statusCode, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  @FunctionalInterface
  private interface ExchangeHandler {
    void handle(HttpExchange exchange) throws IOException;
  }
}
