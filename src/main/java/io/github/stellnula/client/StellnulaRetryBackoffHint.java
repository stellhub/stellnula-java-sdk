package io.github.stellnula.client;

import java.time.Duration;

public record StellnulaRetryBackoffHint(
        long initialDelayMillis, long maxDelayMillis, double multiplier, double jitterRatio) {

    public StellnulaRetryBackoffHint {
        initialDelayMillis = Math.max(0, initialDelayMillis);
        maxDelayMillis = Math.max(initialDelayMillis, maxDelayMillis);
        multiplier = multiplier <= 0 ? 1.0 : multiplier;
        jitterRatio = Math.max(0, jitterRatio);
    }

    /** 计算下一次重试等待时间。 */
    public Duration delay(int attempt) {
        long base =
                (long)
                        Math.min(
                                maxDelayMillis, initialDelayMillis * Math.pow(multiplier, Math.max(0, attempt)));
        if (jitterRatio <= 0 || base <= 0) {
            return Duration.ofMillis(base);
        }
        long jitter = Math.max(1, Math.round(base * jitterRatio));
        long offset = Math.floorMod((long) System.nanoTime(), jitter + 1);
        return Duration.ofMillis(Math.min(maxDelayMillis, base + offset));
    }

    public static StellnulaRetryBackoffHint defaults(Duration retryDelay) {
        long delay = retryDelay == null ? 3000 : retryDelay.toMillis();
        return new StellnulaRetryBackoffHint(delay, Math.max(delay, 30000), 2.0, 0.2);
    }
}
