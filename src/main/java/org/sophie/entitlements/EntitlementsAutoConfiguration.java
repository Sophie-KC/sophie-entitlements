package org.sophie.entitlements;

import java.util.UUID;
import org.sophie.subscriptionservice.grpc.EntitlementServiceGrpc;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Add {@code implementation 'org.sophie:sophie-entitlements'} plus a
 * {@code grpc.client.subscription-service.address} property and a consuming service gets an
 * {@link Entitlements} bean for free — no other wiring needed, matching every other *-client
 * convention in this codebase (this one just ships as a separate published artifact instead of
 * living inside one service, since every service needs it).
 */
@AutoConfiguration
@ConditionalOnClass(EntitlementServiceGrpc.class)
@EnableConfigurationProperties(EntitlementsProperties.class)
@Import({EntitlementsNearCache.class, GrpcEntitlements.class})
public class EntitlementsAutoConfiguration {

    /**
     * The fanout-exchange invalidation listener (Phase 2 §0.3), split into its own nested
     * configuration so {@code sophie.entitlements.cache-invalidation.enabled=false} skips registering
     * any RabbitMQ topology at all — for a service with no broker to talk to, per the class doc on
     * {@link EntitlementsProperties.CacheInvalidation}.
     */
    @Configuration
    @ConditionalOnProperty(
            prefix = "sophie.entitlements.cache-invalidation",
            name = "enabled",
            matchIfMissing = true)
    @Import(CacheInvalidationListener.class)
    static class CacheInvalidationConfiguration {

        @Bean
        FanoutExchange entitlementsFanoutExchange(EntitlementsProperties properties) {
            return new FanoutExchange(properties.getCacheInvalidation().getExchangeName(), true, false);
        }

        // Exclusive + auto-delete + randomly-named: one per service INSTANCE. A shared durable queue
        // would mean only one instance ever sees a given event, leaving the others serving stale data
        // with no symptom — exactly the failure mode this section exists to avoid (Phase 2 §0.3).
        //
        // Deliberately NOT Spring AMQP's AnonymousQueue: it sets an "x-queue-leader-locator" argument
        // by default (a RabbitMQ cluster hint), which this broker rejects outright — PRECONDITION_FAILED
        // "invalid arg 'x-queue-leader-locator' ... of queue type rabbit_classic_queue", since that
        // argument is quorum/stream-queue-only, never valid on a classic queue. Plain Queue with a
        // random name gets the same practical effect (unique, exclusive, auto-delete, no arguments)
        // without tripping it.
        @Bean
        Queue entitlementsInvalidationQueue() {
            return new Queue("entitlements.invalidation." + UUID.randomUUID(), false, true, true);
        }

        @Bean
        Binding entitlementsInvalidationBinding(Queue entitlementsInvalidationQueue, FanoutExchange entitlementsFanoutExchange) {
            return BindingBuilder.bind(entitlementsInvalidationQueue).to(entitlementsFanoutExchange);
        }
    }
}
