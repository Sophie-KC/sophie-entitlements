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
