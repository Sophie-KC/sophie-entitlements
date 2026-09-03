package org.sophie.entitlements;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sophie.entitlements")
public class EntitlementsProperties {

    /** Percentage of failed calls (0-100) that trips the breaker open. */
    private float failureRateThreshold = 50f;

    /** How long the breaker stays open before allowing a trial call through. */
    private int waitDurationInOpenStateSeconds = 30;

    /** Call window the failure rate is computed over. */
    private int slidingWindowSize = 20;

    private final CacheInvalidation cacheInvalidation = new CacheInvalidation();

    public CacheInvalidation getCacheInvalidation() {
        return cacheInvalidation;
    }

    /** {@code sophie.entitlements.cache-invalidation.*} — the fanout-exchange listener (Phase 2 §0.3).
     *  Set {@code enabled: false} for a service with no RabbitMQ broker to talk to; the near-cache
     *  still works, it just won't be invalidated early (falls back to its 30s TTL). */
    public static class CacheInvalidation {

        private boolean enabled = true;

        /** Must match the fanout exchange name subscription-service's outbox relay publishes to. */
        private String exchangeName = "subscription-events";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getExchangeName() {
            return exchangeName;
        }

        public void setExchangeName(String exchangeName) {
            this.exchangeName = exchangeName;
        }
    }

    public float getFailureRateThreshold() {
        return failureRateThreshold;
    }

    public void setFailureRateThreshold(float failureRateThreshold) {
        this.failureRateThreshold = failureRateThreshold;
    }

    public int getWaitDurationInOpenStateSeconds() {
        return waitDurationInOpenStateSeconds;
    }

    public void setWaitDurationInOpenStateSeconds(int waitDurationInOpenStateSeconds) {
        this.waitDurationInOpenStateSeconds = waitDurationInOpenStateSeconds;
    }

    public int getSlidingWindowSize() {
        return slidingWindowSize;
    }

    public void setSlidingWindowSize(int slidingWindowSize) {
        this.slidingWindowSize = slidingWindowSize;
    }
}
