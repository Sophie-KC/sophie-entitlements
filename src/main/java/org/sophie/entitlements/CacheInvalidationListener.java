package org.sophie.entitlements;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes subscription-service's {@code subscription-events} fanout exchange (Phase 2 §0.3) via an
 * exclusive, auto-delete, server-named queue bound in {@link EntitlementsAutoConfiguration} — one such
 * queue per service INSTANCE, not a shared durable queue, so every instance invalidates its own
 * near-cache rather than one instance draining events the others never see.
 *
 * <p>Deliberately reads the routing key rather than a field inside the JSON body: {@code
 * SubscriptionOutboxRelay} publishes with the event type AS the routing key, and a fanout exchange
 * ignores it for delivery but still records it on the delivered message.
 */
@Component
class CacheInvalidationListener {

    private static final Logger log = LoggerFactory.getLogger(CacheInvalidationListener.class);
    private static final String EVENT_SUBSCRIPTION_CHANGED = "subscription.changed";
    private static final String EVENT_PLAN_ENTITLEMENTS_CHANGED = "plan.entitlements_changed";

    // Constructed directly rather than injected: Spring Boot 4 does not autowire a Jackson 2
    // ObjectMapper by default, and this listener has no reason to depend on the consuming
    // application's own Jackson configuration (or lack of one) just to parse this one small payload.
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final EntitlementsNearCache nearCache;

    CacheInvalidationListener(EntitlementsNearCache nearCache) {
        this.nearCache = nearCache;
    }

    @RabbitListener(queues = "#{entitlementsInvalidationQueue.name}")
    void onMessage(Message message) {
        String routingKey = message.getMessageProperties().getReceivedRoutingKey();
        if (EVENT_SUBSCRIPTION_CHANGED.equals(routingKey)) {
            evictOrg(message);
        } else if (EVENT_PLAN_ENTITLEMENTS_CHANGED.equals(routingKey)) {
            // Carries plan_id, not org ids — flushing everything is correct and cheap at this event's
            // frequency (a handful of times a year), per the master prompt §3.2.
            log.info("plan.entitlements_changed received; flushing the entire entitlements near-cache");
            nearCache.flushAll();
        } else {
            log.debug("Ignoring subscription-events message with unrecognized routing key '{}'", routingKey);
        }
    }

    private void evictOrg(Message message) {
        try {
            JsonNode root = MAPPER.readTree(message.getBody());
            String orgId = root.path("orgId").asText(null);
            if (orgId == null) {
                log.warn("subscription.changed payload had no orgId; flushing the entire near-cache as a safe fallback");
                nearCache.flushAll();
                return;
            }
            nearCache.evict(UUID.fromString(orgId));
        } catch (Exception e) {
            log.warn("Failed to parse subscription.changed payload; flushing the entire near-cache as a safe fallback", e);
            nearCache.flushAll();
        }
    }
}
