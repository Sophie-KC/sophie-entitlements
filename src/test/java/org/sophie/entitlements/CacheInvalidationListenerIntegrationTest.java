package org.sophie.entitlements;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.stub.StreamObserver;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.devh.boot.grpc.server.service.GrpcService;
import org.junit.jupiter.api.Test;
import org.sophie.subscriptionservice.grpc.AccessMode;
import org.sophie.subscriptionservice.grpc.EntitlementMap;
import org.sophie.subscriptionservice.grpc.EntitlementServiceGrpc;
import org.sophie.subscriptionservice.grpc.EntitlementSource;
import org.sophie.subscriptionservice.grpc.EntitlementValue;
import org.sophie.subscriptionservice.grpc.GetEntitlementsRequest;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Phase 2 §0.3's own required test: publish {@code subscription.changed}, assert a subsequent {@code
 * limit()} reflects the new value without waiting out the 30s near-cache TTL. Runs against a real
 * local RabbitMQ (this codebase's established convention — see org-service's
 * SignupIntegrationTestSupport) and an in-process fake EntitlementService, so no real
 * subscription-service instance is needed.
 */
@SpringBootTest(
        classes = CacheInvalidationListenerIntegrationTest.TestApp.class,
        properties = {
            "grpc.server.in-process-name=entitlements-cache-invalidation-test",
            "grpc.server.port=-1",
            "grpc.client.subscription-service.address=in-process:entitlements-cache-invalidation-test",
            "spring.rabbitmq.host=localhost"
        })
class CacheInvalidationListenerIntegrationTest {

    @SpringBootApplication
    static class TestApp {
        // Explicit @Bean rather than relying on component-scan to find a nested static test class —
        // net.devh's server discovers @GrpcService beans by annotation regardless of how the bean was
        // registered, so this sidesteps any scanning ambiguity around nested classes.
        @org.springframework.context.annotation.Bean
        FakeEntitlementService fakeEntitlementService() {
            return new FakeEntitlementService();
        }
    }

    /** Serves whatever seats.max FakeEntitlementService.SEATS_MAX currently holds — mutated mid-test
     *  to prove a second {@code limit()} call reflects a NEW value, not a cached stale one. */
    @GrpcService
    static class FakeEntitlementService extends EntitlementServiceGrpc.EntitlementServiceImplBase {

        static final AtomicLong SEATS_MAX = new AtomicLong(5);

        @Override
        public void getEntitlements(GetEntitlementsRequest request, StreamObserver<EntitlementMap> responseObserver) {
            EntitlementValue value = EntitlementValue.newBuilder()
                    .setIsUnlimited(false)
                    .setNumberValue(SEATS_MAX.get())
                    .setSource(EntitlementSource.PLAN)
                    .build();
            responseObserver.onNext(EntitlementMap.newBuilder()
                    .putEntitlements("seats.max", value)
                    .setSubscriptionStatus("ACTIVE")
                    .setAccessMode(AccessMode.FULL)
                    .build());
            responseObserver.onCompleted();
        }
    }

    @Autowired
    private Entitlements entitlements;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Test
    void subscriptionChangedEvictsTheOrgWithoutWaitingOutTheTtl() throws InterruptedException {
        UUID orgId = UUID.randomUUID();
        FakeEntitlementService.SEATS_MAX.set(5);

        assertThat(entitlements.limit(orgId, "seats.max")).isEqualTo(5);

        FakeEntitlementService.SEATS_MAX.set(50);
        // Still cached — the near-cache's 30s TTL hasn't elapsed, so this must still read 5.
        assertThat(entitlements.limit(orgId, "seats.max")).isEqualTo(5);

        rabbitTemplate.convertAndSend(
                "subscription-events", "subscription.changed", "{\"orgId\":\"" + orgId + "\"}");

        assertThat(eventuallyReflects(orgId, 50)).as("limit() reflects 50 within 5s of the invalidation event").isTrue();
    }

    @Test
    void planEntitlementsChangedFlushesEveryOrgNotJustOne() throws InterruptedException {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        FakeEntitlementService.SEATS_MAX.set(5);
        assertThat(entitlements.limit(orgA, "seats.max")).isEqualTo(5);
        assertThat(entitlements.limit(orgB, "seats.max")).isEqualTo(5);

        FakeEntitlementService.SEATS_MAX.set(100);
        rabbitTemplate.convertAndSend("subscription-events", "plan.entitlements_changed", "{\"planId\":\"irrelevant\"}");

        assertThat(eventuallyReflects(orgA, 100)).as("org A re-resolves after a plan-wide flush").isTrue();
        assertThat(eventuallyReflects(orgB, 100)).as("org B re-resolves after a plan-wide flush").isTrue();
    }

    /** Rabbit delivery + listener dispatch is async — poll briefly rather than assert immediately. */
    private boolean eventuallyReflects(UUID orgId, long expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (entitlements.limit(orgId, "seats.max") == expected) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }
}
