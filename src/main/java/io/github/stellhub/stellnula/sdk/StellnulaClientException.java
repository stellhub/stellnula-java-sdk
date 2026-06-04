package io.github.stellhub.stellnula.sdk;

public final class StellnulaClientException extends RuntimeException {

  private final int statusCode;
  private final String responseBody;

  public StellnulaClientException(int statusCode, String responseBody) {
    super("Stellnula request failed with HTTP status " + statusCode);
    this.statusCode = statusCode;
    this.responseBody = responseBody;
  }

  public int statusCode() {
    return statusCode;
  }

  public String responseBody() {
    return responseBody;
  }
}
