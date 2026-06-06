package io.github.stellnula.client;

import io.github.stellnula.auth.*;
import io.github.stellnula.config.*;
import io.github.stellnula.grpc.*;
import io.github.stellnula.internal.*;
import io.github.stellnula.management.*;
import io.github.stellnula.store.*;
import io.github.stellnula.telemetry.*;
import io.github.stellnula.transport.*;

public final class StellnulaClientException extends RuntimeException {

    private final int statusCode;
    private final String responseBody;
    private final boolean retryable;
    private final boolean fullSyncRequired;
    private final long retryAfterMillis;
    private final String errorCode;
    private final String fullSyncReason;
    private final StellnulaRetryBackoffHint retryBackoff;

    public StellnulaClientException(int statusCode, String responseBody) {
        this(
                statusCode,
                responseBody,
                "",
                false,
                0,
                null,
                false,
                "",
                "Stellnula request failed with HTTP status " + statusCode);
    }

    public StellnulaClientException(
            int statusCode,
            String responseBody,
            String errorCode,
            boolean retryable,
            long retryAfterMillis,
            StellnulaRetryBackoffHint retryBackoff,
            boolean fullSyncRequired,
            String fullSyncReason,
            String message) {
        super(message == null || message.isBlank() ? "Stellnula request failed" : message);
        this.statusCode = statusCode;
        this.responseBody = responseBody == null ? "" : responseBody;
        this.errorCode = errorCode == null ? "" : errorCode;
        this.retryable = retryable;
        this.retryAfterMillis = retryAfterMillis;
        this.retryBackoff = retryBackoff;
        this.fullSyncRequired = fullSyncRequired;
        this.fullSyncReason = fullSyncReason == null ? "" : fullSyncReason;
    }

    public int statusCode() {
        return statusCode;
    }

    public String responseBody() {
        return responseBody;
    }

    public String errorCode() {
        return errorCode;
    }

    public boolean retryable() {
        return retryable;
    }

    public long retryAfterMillis() {
        return retryAfterMillis;
    }

    public StellnulaRetryBackoffHint retryBackoff() {
        return retryBackoff;
    }

    public boolean fullSyncRequired() {
        return fullSyncRequired;
    }

    public String fullSyncReason() {
        return fullSyncReason;
    }
}
