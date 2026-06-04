package io.github.stellhub.stellnula.sdk;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class StellnulaClient implements AutoCloseable {

  private final StellnulaClientOptions options;
  private final HttpClient httpClient;

  public StellnulaClient(StellnulaClientOptions options) {
    this(options, HttpClient.newHttpClient());
  }

  StellnulaClient(StellnulaClientOptions options, HttpClient httpClient) {
    this.options = Objects.requireNonNull(options, "options must not be null");
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
  }

  public static StellnulaClient create(URI endpoint) {
    return new StellnulaClient(StellnulaClientOptions.builder().endpoint(endpoint).build());
  }

  public String getConfig(String namespace, String key) throws IOException, InterruptedException {
    HttpRequest request = requestBuilder(buildConfigUri(namespace, key)).GET().build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() >= 200 && response.statusCode() < 300) {
      return response.body();
    }
    throw new StellnulaClientException(response.statusCode(), response.body());
  }

  URI buildConfigUri(String namespace, String key) {
    requireText(namespace, "namespace");
    requireText(key, "key");
    return resolve("/api/v1/configs/" + encodePathSegment(namespace) + "/" + encodePathSegment(key));
  }

  private HttpRequest.Builder requestBuilder(URI uri) {
    HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
        .timeout(options.requestTimeout())
        .header("Accept", "application/json");
    if (!options.apiToken().isBlank()) {
      builder.header("Authorization", "Bearer " + options.apiToken());
    }
    return builder;
  }

  private URI resolve(String path) {
    String endpoint = options.endpoint().toString();
    String normalizedEndpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
    return URI.create(normalizedEndpoint + path);
  }

  private static String encodePathSegment(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static void requireText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
  }

  @Override
  public void close() {
  }
}
