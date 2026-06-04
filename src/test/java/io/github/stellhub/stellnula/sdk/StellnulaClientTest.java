package io.github.stellhub.stellnula.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class StellnulaClientTest {

  @Test
  void buildsEncodedConfigUri() {
    StellnulaClient client = StellnulaClient.create(URI.create("http://localhost:8080"));

    URI uri = client.buildConfigUri("default env", "application/dev.yaml");

    assertEquals("http://localhost:8080/api/v1/configs/default%20env/application%2Fdev.yaml", uri.toString());
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
}
