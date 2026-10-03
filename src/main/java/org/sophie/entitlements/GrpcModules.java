package org.sophie.entitlements;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sophie.orgservice.grpc.GetOrgModulesRequest;
import org.sophie.orgservice.grpc.ModuleState;
import org.sophie.orgservice.grpc.OrgModules;
import org.sophie.orgservice.grpc.OrgServiceGrpc;
import org.springframework.stereotype.Component;

/**
 * Production {@link Modules}: near-cache -> org-service {@code GetOrgModules}, behind its own circuit
 * breaker (an org-service outage must not trip the subscription-service breaker, or vice versa).
 * Every consuming service already configures a {@code grpc.client.org-service} channel.
 */
@Component
class GrpcModules implements Modules {

    private static final Logger log = LoggerFactory.getLogger(GrpcModules.class);

    private final OrgServiceGrpc.OrgServiceBlockingStub stub;
    private final CircuitBreaker circuitBreaker;
    private final ModulesNearCache nearCache;

    GrpcModules(
            @GrpcClient("org-service") OrgServiceGrpc.OrgServiceBlockingStub stub,
            EntitlementsProperties properties,
            ModulesNearCache nearCache) {
        this.stub = stub;
        this.nearCache = nearCache;
        this.circuitBreaker = CircuitBreaker.of(
                "org-service-modules",
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(properties.getFailureRateThreshold())
                        .waitDurationInOpenState(Duration.ofSeconds(properties.getWaitDurationInOpenStateSeconds()))
                        .slidingWindowSize(properties.getSlidingWindowSize())
                        .build());
    }

    @Override
    public void requireEnabled(UUID orgId, String moduleKey) {
        if (!isEnabled(orgId, moduleKey)) {
            throw EntitlementDeniedException.moduleDisabled(moduleKey);
        }
    }

    @Override
    public boolean isEnabled(UUID orgId, String moduleKey) {
        if (orgId == null) {
            return true;
        }
        try {
            return !disabled(orgId).contains(moduleKey);
        } catch (RuntimeException e) {
            log.warn("org-service unavailable; failing OPEN for org {} module '{}'", orgId, moduleKey, e);
            return true;
        }
    }

    private Set<String> disabled(UUID orgId) {
        Set<String> cached = nearCache.getIfPresent(orgId);
        if (cached != null) {
            return cached;
        }
        OrgModules modules = circuitBreaker.decorateSupplier(() -> stub.getOrgModules(
                GetOrgModulesRequest.newBuilder().setOrgId(orgId.toString()).build())).get();
        Set<String> disabled = new HashSet<>();
        for (ModuleState state : modules.getModulesList()) {
            if (!state.getEnabled()) {
                disabled.add(state.getKey());
            }
        }
        Set<String> frozen = Set.copyOf(disabled);
        nearCache.put(orgId, frozen);
        return frozen;
    }
}
