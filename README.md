# Stellnula Java SDK

Official Java SDK for [Stellnula Service](https://github.com/stellhub/stellnula-service), the Nebula configuration center server.

Stellnula's Chinese name is 星云, and its English full name is Nebula. This SDK provides Java client-side access to Stellnula configuration lookup, delivery, synchronization, and future runtime change propagation APIs.

## Responsibilities

- Provide a Java client for interacting with `stellnula-service`.
- Encapsulate HTTP transport, endpoint configuration, authentication headers, and request timeouts.
- Expose configuration lookup APIs for Java applications and middleware integrations.
- Keep room for OpenAPI-generated models and richer configuration watch APIs as the service contract evolves.
- Offer a stable SDK foundation for Spring Boot and non-Spring Java clients.

## Requirements

- JDK 21 or later
- Maven 3.9 or later

## Build

```bash
mvn test
```

## Basic Usage

```java
import io.github.stellhub.stellnula.sdk.StellnulaClient;
import io.github.stellhub.stellnula.sdk.StellnulaClientOptions;

import java.net.URI;

var options = StellnulaClientOptions.builder()
    .endpoint(URI.create("http://localhost:8080"))
    .apiToken("your-token")
    .build();

try (var client = new StellnulaClient(options)) {
  String value = client.getConfig("default", "application.yaml");
  System.out.println(value);
}
```

## Repository Role

This repository contains the Java SDK implementation for Stellnula Service.
