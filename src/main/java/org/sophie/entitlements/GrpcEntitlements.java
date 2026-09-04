package org.sophie.entitlements;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sophie.subscriptionservice.grpc.EntitlementMap;
import org.sophie.subscriptionservice.grpc.EntitlementServiceGrpc;
import org.sophie.subscriptionservice.grpc.EntitlementValue;
import org.sophie.subscriptionservice.grpc.GetEntitlementsRequest;
import org.springframework.stereotype.Component;

/**
 * Production {@link Entitlements}: near-cache -> gRPC (master prompt §3.2's "near-cache -> Redis ->
 * gRPC" flow, minus Redis — still deferred, the 30s near-cache TTL is the outer staleness bound until
 * it's built), wrapped in a resilience4j circuit breaker for the gRPC leg. See {@link Entitlements} for
 * the fail-open/fail-closed contract per method — that split is hardcoded here rather than configured
 * per-key, which is a deliberate simplification: no caller today needs a feature-flag-style check that
 * should fail open, so the extra config surface isn't earned yet.
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
    public void requireFeature(UUID orgId, String key) {
        EntitlementMap map;
        try {
            map = resolve(orgId);
        } catch (RuntimeException e) {
            log.warn("subscription-service unavailable; failing CLOSED for org {} feature '{}'", orgId, key, e);
            throw EntitlementDeniedException.feature(key);
        }
        EntitlementValue value = map.getEntitlementsMap().get(key);
        boolean allowed = value != null && (value.getIsUnlimited() || value.getBoolValue());
        if (!allowed) {
            throw EntitlementDeniedException.feature(key);
        }
    }

    @Override
    public void requireQuota(UUID orgId, String key, long currentUsage) {
        EntitlementMap map;
        try {
            map = resolve(orgId);
        } catch (RuntimeException e) {
            log.warn("subscription-service unavailable; failing CLOSED for org {} quota '{}'", orgId, key, e);
            throw EntitlementDeniedException.quota(key, -1, currentUsage);
        }
        EntitlementValue value = map.getEntitlementsMap().get(key);
        if (value == null) {
            // Unknown key is a caller bug (a typo'd key name), not an outage — still fail closed on a
            // quota-consuming write rather than silently allowing it.
            throw EntitlementDeniedException.quota(key, -1, currentUsage);
        }
        if (value.getIsUnlimited()) {
            return;
        }
        long resolvedLimit = value.getNumberValue();
        if (currentUsage + 1 > resolvedLimit) {
            throw EntitlementDeniedException.quota(key, resolvedLimit, currentUsage);
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
            throw EntitlementDeniedException.accessDenied();
        }
        if (map.getAccessMode() == org.sophie.subscriptionservice.grpc.AccessMode.READ_ONLY) {
            throw EntitlementDeniedException.accessDenied();
        }
    }

    @Override
    public Entitlements.AccessMode accessMode(UUID orgId) {
        try {
            EntitlementMap map = resolve(orgId);
            return map.getAccessMode() == org.sophie.subscriptionservice.grpc.AccessMode.READ_ONLY
                    ? Entitlements.AccessMode.READ_ONLY
                    : Entitlements.AccessMode.FULL;
        } catch (RuntimeException e) {
            log.warn("subscription-service unavailable; failing OPEN (FULL access) for org {}", orgId, e);
            return Entitlements.AccessMode.FULL;
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
