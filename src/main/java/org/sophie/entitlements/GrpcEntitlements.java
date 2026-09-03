package org.sophie.entitlements;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sophie.subscriptionservice.grpc.AccessMode;
import org.sophie.subscriptionservice.grpc.EntitlementMap;
import org.sophie.subscriptionservice.grpc.EntitlementServiceGrpc;
import org.sophie.subscriptionservice.grpc.EntitlementValue;
import org.sophie.subscriptionservice.grpc.GetEntitlementsRequest;
import org.springframework.stereotype.Component;

/**
 * Production {@link Entitlements}: near-cache -> gRPC (master prompt §3.2's "near-cache -> Redis ->
 * gRPC" flow, minus Redis — still deferred, the 30s near-cache TTL is the outer staleness bound until
 * it's built), wrapped in a resilience4j circuit breaker for the gRPC leg. See {@link Entitlements} for
 * the fail-open/fail-closed contract per method — that split is hardcoded here (limit() open,
 * require()/requireWriteAccess() closed) rather than configured per-key, which is a deliberate
 * simplification: no caller today needs a feature-flag-style require() that should fail open, so the
 * extra config surface isn't earned yet.
 *
 * <p>Always resolves the WHOLE map per org (one cache entry, one gRPC call), never a single key —
 * {@code GetEntitlements} is "the one every service actually uses" per the proto's own doc comment.
 */
@Component
class GrpcEntitlements implements Entitlements {

    private static final Logger log = LoggerFactory.getLogger(GrpcEntitlements.class);

    private final EntitlementServiceGrpc.EntitlementServiceBlockingStub stub;
    private final CircuitBreaker circuitBreaker;
    private final EntitlementsNearCache nearCache;

    GrpcEntitlements(
            @GrpcClient("subscription-service") EntitlementServiceGrpc.EntitlementServiceBlockingStub stub,
            EntitlementsProperties properties,
            EntitlementsNearCache nearCache) {
        this.stub = stub;
        this.nearCache = nearCache;
        this.circuitBreaker = CircuitBreaker.of(
                "subscription-service",
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(properties.getFailureRateThreshold())
                        .waitDurationInOpenState(Duration.ofSeconds(properties.getWaitDurationInOpenStateSeconds()))
                        .slidingWindowSize(properties.getSlidingWindowSize())
                        .build());
    }

    @Override
    public void require(UUID orgId, String key) {
        EntitlementMap map;
        try {
            map = resolve(orgId);
        } catch (RuntimeException e) {
            log.warn("subscription-service unavailable; failing CLOSED for org {} key '{}'", orgId, key, e);
            throw new EntitlementDeniedException(key, -1, -1);
        }
        EntitlementValue value = map.getEntitlementsMap().get(key);
        if (value == null) {
            // Unknown key is a caller bug (a typo'd key name), not an outage — still fail closed on a
            // quota-consuming write rather than silently allowing it.
            throw new EntitlementDeniedException(key, -1, -1);
        }
        boolean allowed = value.getIsUnlimited() || value.getBoolValue() || value.getNumberValue() > 0;
        if (!allowed) {
            throw new EntitlementDeniedException(key, value.getNumberValue(), -1);
        }
    }

    @Override
    public long limit(UUID orgId, String key) {
        try {
            EntitlementMap map = resolve(orgId);
            EntitlementValue value = map.getEntitlementsMap().get(key);
            if (value == null) {
                return Long.MAX_VALUE; // unknown key: same fail-open contract as an outage
            }
            return value.getIsUnlimited() ? Long.MAX_VALUE : value.getNumberValue();
        } catch (RuntimeException e) {
            log.warn("subscription-service unavailable; failing OPEN (unlimited) for org {} key '{}'", orgId, key, e);
            return Long.MAX_VALUE;
        }
    }

    @Override
    public void requireWriteAccess(UUID orgId) {
        EntitlementMap map;
        try {
            map = resolve(orgId);
        } catch (RuntimeException e) {
            log.warn("subscription-service unavailable; failing CLOSED (denying write) for org {}", orgId, e);
            throw new EntitlementDeniedException("access_mode", -1, -1);
        }
        if (map.getAccessMode() == AccessMode.READ_ONLY) {
            throw new EntitlementDeniedException("access_mode", -1, -1);
        }
    }

    private EntitlementMap resolve(UUID orgId) {
        EntitlementMap cached = nearCache.getIfPresent(orgId);
        if (cached != null) {
            return cached;
        }
        EntitlementMap fetched = call(() -> stub.getEntitlements(
                GetEntitlementsRequest.newBuilder().setOrgId(orgId.toString()).build()));
        nearCache.put(orgId, fetched);
        return fetched;
    }

    private <T> T call(Supplier<T> grpcCall) {
        return circuitBreaker.decorateSupplier(grpcCall).get();
    }
}
