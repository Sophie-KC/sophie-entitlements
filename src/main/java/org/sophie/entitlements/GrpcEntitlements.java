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
import org.sophie.subscriptionservice.grpc.CheckEntitlementRequest;
import org.sophie.subscriptionservice.grpc.EntitlementCheck;
import org.sophie.subscriptionservice.grpc.EntitlementMap;
import org.sophie.subscriptionservice.grpc.EntitlementServiceGrpc;
import org.sophie.subscriptionservice.grpc.GetEntitlementsRequest;
import org.springframework.stereotype.Component;

/**
 * Production {@link Entitlements}: calls subscription-service's EntitlementService over gRPC, wrapped
 * in a resilience4j circuit breaker. See {@link Entitlements} for the fail-open/fail-closed contract
 * per method — that split is hardcoded here (limit() open, require()/requireWriteAccess() closed)
 * rather than configured per-key, which is a deliberate Phase-1 simplification: no caller today needs
 * a feature-flag-style require() that should fail open, so the extra config surface isn't earned yet.
 */
@Component
class GrpcEntitlements implements Entitlements {

    private static final Logger log = LoggerFactory.getLogger(GrpcEntitlements.class);

    private final EntitlementServiceGrpc.EntitlementServiceBlockingStub stub;
    private final CircuitBreaker circuitBreaker;

    GrpcEntitlements(
            @GrpcClient("subscription-service") EntitlementServiceGrpc.EntitlementServiceBlockingStub stub,
            EntitlementsProperties properties) {
        this.stub = stub;
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
        EntitlementCheck check;
        try {
            check = call(() -> stub.checkEntitlement(CheckEntitlementRequest.newBuilder()
                    .setOrgId(orgId.toString())
                    .setKey(key)
                    .build()));
        } catch (RuntimeException e) {
            log.warn("subscription-service unavailable; failing CLOSED for org {} key '{}'", orgId, key, e);
            throw new EntitlementDeniedException(key, -1, -1);
        }
        if (!check.getAllowed()) {
            throw new EntitlementDeniedException(
                    key, check.getIsUnlimited() ? Long.MAX_VALUE : check.getLimit(), -1);
        }
    }

    @Override
    public long limit(UUID orgId, String key) {
        try {
            EntitlementCheck check = call(() -> stub.checkEntitlement(CheckEntitlementRequest.newBuilder()
                    .setOrgId(orgId.toString())
                    .setKey(key)
                    .build()));
            return check.getIsUnlimited() ? Long.MAX_VALUE : check.getLimit();
        } catch (RuntimeException e) {
            log.warn("subscription-service unavailable; failing OPEN (unlimited) for org {} key '{}'", orgId, key, e);
            return Long.MAX_VALUE;
        }
    }

    @Override
    public void requireWriteAccess(UUID orgId) {
        EntitlementMap map;
        try {
            map = call(() -> stub.getEntitlements(
                    GetEntitlementsRequest.newBuilder().setOrgId(orgId.toString()).build()));
        } catch (RuntimeException e) {
            log.warn("subscription-service unavailable; failing CLOSED (denying write) for org {}", orgId, e);
            throw new EntitlementDeniedException("access_mode", -1, -1);
        }
        if (map.getAccessMode() == AccessMode.READ_ONLY) {
            throw new EntitlementDeniedException("access_mode", -1, -1);
        }
    }

    private <T> T call(Supplier<T> grpcCall) {
        return circuitBreaker.decorateSupplier(grpcCall).get();
    }
}
