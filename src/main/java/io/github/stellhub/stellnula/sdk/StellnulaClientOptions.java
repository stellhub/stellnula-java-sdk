package io.github.stellhub.stellnula.sdk;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

public record StellnulaClientOptions(
    URI endpoint,
    String apiToken,
    Duration requestTimeout
) {

  public StellnulaClientOptions {
    Objects.requireNonNull(endpoint, "endpoint must not be null");
    apiToken = apiToken == null ? "" : apiToken;
    requestTimeout = requestTimeout == null ? Duration.ofSeconds(10) : requestTimeout;
    if (requestTimeout.isZero() || requestTimeout.isNegative()) {
      throw new IllegalArgumentException("requestTimeout must be positive");
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private URI endpoint;
    private String apiToken = "";
    private Duration requestTimeout = Duration.ofSeconds(10);

    public Builder endpoint(URI endpoint) {
      this.endpoint = endpoint;
      return this;
    }

    public Builder apiToken(String apiToken) {
      this.apiToken = apiToken;
      return this;
    }

    public Builder requestTimeout(Duration requestTimeout) {
      this.requestTimeout = requestTimeout;
      return this;
    }

    public StellnulaClientOptions build() {
      return new StellnulaClientOptions(endpoint, apiToken, requestTimeout);
    }
  }
}
