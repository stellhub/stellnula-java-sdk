# Stellnula Java SDK

Official Java SDK for [Stellnula Service](https://github.com/stellhub/stellnula-service), the Nebula configuration center server.

Stellnula 的中文名是“星云”，英文完整名是 Nebula。本仓库提供面向普通 Java 应用和未来 Spring Boot starter 的核心客户端实现。当前仓库只包含纯 Java SDK，不引入任何 Spring Boot、Spring Framework 或自动装配相关依赖。

## 1. Problem analysis

`stellnula-service` 的客户端交互分为客户端数据面和配置管理面：

- 客户端数据面路径为 `/api/v1/client/*`，用于启动 bootstrap、全量配置拉取、增量配置拉取、配置内容读取和客户端心跳。
- 配置管理面路径为 `/api/v1/configs/{configId}`，用于配置查询、新增/更新、删除和公共配置跨环境复制发布。
- 运行态变更通知使用 gRPC `StellnulaConfigService/Watch`。该调用是长轮询语义：客户端带本地 revision 发起请求，服务端在有新 revision 时立即返回，没有变更时阻塞到超时后返回 `NO_CHANGE`。
- SDK 读取远端配置后必须同时更新本地内存快照和本地快照文件。本地文件只作为故障恢复来源，不作为服务端事实来源。
- Spring Boot 接入应由后续独立 starter 完成，本仓库仅暴露可被 starter 包装的核心 API。
- SDK 根包为 `io.github.stellnula`，按职责拆分为 `client`、`config`、`management`、`transport`、`grpc`、`store`、`telemetry`、`auth` 和 `internal` 等子包；Maven 坐标仍为 `io.github.stellhub:stellnula-java-sdk`。

## 2. Design

### 2.1 启动同步流程

```text
1. 读取本地 snapshot 文件。
2. 如果本地 snapshot 存在且格式有效，先填充本地内存。
3. 调用 HTTP POST /api/v1/client/bootstrap。
4. 使用远端 bootstrap 返回的完整配置覆盖本地内存。
5. 使用临时文件 + 原子替换写入本地 snapshot 文件。
6. 根据 bootstrap 返回的 gRPC 地址启动 Watch 长轮询。
```

如果远端暂时不可用，SDK 可以先暴露本地文件中的最后一次成功配置，并在后台继续重试远端同步。

### 2.2 数据 CRUD 流程

配置管理 API 贴合服务端当前实现：

```text
GET    /api/v1/configs/{configId}?env=&region=&zone=&cluster=
PUT    /api/v1/configs/{configId}
DELETE /api/v1/configs/{configId}
POST   /api/v1/configs/{configId}/replications
```

SDK 的 CRUD 方法只负责协议封装、请求体构建、响应解析和错误归一化，不在客户端侧缓存管理面查询结果。真正会更新本地内存和本地文件的是客户端数据面 bootstrap、full、delta 和 watch 结果。

### 2.3 本地内存与文件模型

SDK 内存中维护一份完整配置快照：

```text
revision + checksum + Map<configId, ConfigEntry>
```

本地文件默认保存到：

```text
${user.home}/.stellnula/${appId}/${env}/${cluster}/config-snapshot.json
```

文件写入规则：

- 每次远端全量同步成功后写入完整 snapshot。
- 每次 watch 增量变更成功应用后写入完整 snapshot。
- 写文件使用同目录临时文件，再执行原子替换，避免半文件。
- 本地 snapshot 文件带 schema 信息；加载时会校验 checksum，损坏或不匹配的文件会被隔离为 `.corrupt` 文件。
- 如果增量应用失败、checksum 不一致或服务端返回 `CLIENT_TOO_OLD`，执行全量拉取修复。
- HTTP bootstrap、full 和 delta 会自动处理分页，直到服务端 `hasMore=false`。
- `deliveryMode=REFERENCE` 的大文件配置会通过 `/api/v1/client/configs/content` 补齐实际内容后再进入本地快照。

### 2.4 Watch 长轮询流程

```text
1. 使用当前内存 revision 和 checksum 构造 WatchRequest。
2. gRPC Watch 返回 CHANGED 时应用 changes。
3. 返回 NO_CHANGE 时继续下一轮长轮询。
4. 返回 CLIENT_TOO_OLD 或 fullSyncRequired=true 时走 HTTP full 拉取。
5. Watch 异常时按退避策略重试，并可使用 HTTP delta/full 作为补偿路径。
6. 更新内存和本地文件后再通知 listener。
```

SDK 会解析 HTTP 错误响应和 gRPC trailer 中的 `retryable`、`retryAfterMillis`、`retryBackoff`、`fullSyncRequired` 和 `fullSyncReason`，并据此决定退避、全量同步或重选服务端。

gRPC 数据面已接入 `Watch`、`FetchFull`、`FetchDelta` 和 `ReportClientState`。HTTP full、delta 和 heartbeat 保留为 fallback 路径。

### 2.5 Spring Boot 接入边界

当前核心 SDK 应暴露稳定的纯 Java 类型：

- `io.github.stellnula.client.StellnulaClientOptions`
- `io.github.stellnula.client.StellnulaClient`
- `io.github.stellnula.config.StellnulaSnapshot`
- `io.github.stellnula.config.StellnulaConfigEntry`
- `io.github.stellnula.config.StellnulaConfigListener`
- `io.github.stellnula.client.StellnulaClientState`
- `io.github.stellnula.client.StellnulaRetryBackoffHint`
- `io.github.stellnula.auth.StellnulaTokenProvider`
- `io.github.stellnula.transport.StellnulaServerSelector`
- `io.github.stellnula.transport.StellnulaServerEndpoint`

未来 Spring Boot starter 只需要读取 `application.yaml`，构造 `StellnulaClientOptions`，创建 `StellnulaClient`，再把配置注入 Spring Environment 或业务 Bean。本仓库不包含 starter、auto configuration、annotation 或 Spring 依赖。

## 3. Implementation

当前实现计划：

1. 使用外部传入的 OkHttp `OkHttpClient` 封装 HTTP bootstrap、full、delta、content、heartbeat 和管理面 CRUD。
2. 使用 Jackson 处理服务端 JSON 请求/响应，避免手写 JSON 字符串。
3. 使用服务端 proto 契约生成 gRPC client stub，实现 `Watch` 长轮询。
4. 使用线程安全内存快照维护完整配置，并按服务端算法校验 checksum。
5. 使用本地 snapshot store 实现启动恢复和远端成功同步后的原子落盘。
6. 对外提供 `start()`、`syncNow()`、`fetchFull()`、`state()`、`asMap()`、`getValue()`、`getRequiredValue()`、`snapshot()`、`upsertConfig()`、`deleteConfig()` 等核心 API。
7. 使用 weighted rendezvous hash 从 bootstrap 返回的健康 gRPC 节点中选择 watch 目标。
8. 额外提供治理规则和配置灰度规则管理面 API，贴合服务端 `/api/v1/governance/rules` 与 `/api/v1/configs/{configId}/gray-rules`。
9. OpenTelemetry 指标的基础维度遵从 stellflux 资源规范，使用 `service.name`、`service.namespace`、`deployment.environment.name`、`k8s.cluster.name`、`cloud.region` 和 `cloud.availability_zone` 等 Resource Attribute，不再自定义 `stellnula.app_id`、`stellnula.env` 这类应用环境维度。

## 4. Complete code

构建和测试：

```bash
mvn test
```

基础使用：

```java
import io.github.stellnula.client.StellnulaClient;
import io.github.stellnula.client.StellnulaClientOptions;

import java.net.URI;

public class Demo {
    public static void main(String[] args) throws Exception {
        StellnulaClientOptions options = StellnulaClientOptions.builder()
                .endpoint(URI.create("http://localhost:8080"))
                .appId("trade.order-service")
                .clientId("trade-order-local")
                .env("dev")
                .namespace("application")
                .group("default")
                .build();

        try (StellnulaClient client = new StellnulaClient(options)) {
            client.start();
            String serverPort = client.getValue("server.port").orElse("8080");
            System.out.println(serverPort);
        }
    }
}
```
