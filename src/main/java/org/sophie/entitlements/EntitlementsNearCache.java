package org.sophie.entitlements;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.UUID;
import org.sophie.subscriptionservice.grpc.EntitlementMap;
import org.springframework.stereotype.Component;

/**
 * Per-instance near-cache of resolved entitlement maps (master prompt §3.2: "Caffeine near-cache in
 * every consuming service, TTL 30s, max 10k entries" — living in the shared library rather than
 * duplicated per service). Survives a Redis blip in the design this is meant to grow into; for now
 * (Phase 2) it's the only cache layer — Redis is still deferred, so this cache's 30s TTL is also the
 * outer bound on how stale a value can be even with no invalidation event at all.
 *
 * <p>{@link CacheInvalidationListener} is what makes staleness shorter than 30s in the common case —
 * evicting an org (or flushing everything) the moment {@code subscription.changed} /
 * {@code plan.entitlements_changed} arrives.
 */
@Component
class EntitlementsNearCache {

    private final Cache<UUID, EntitlementMap> cache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofSeconds(30))
            .build();

    EntitlementMap getIfPresent(UUID orgId) {
        return cache.getIfPresent(orgId);
    }

    void put(UUID orgId, EntitlementMap value) {
        cache.put(orgId, value);
    }

    void evict(UUID orgId) {
        cache.invalidate(orgId);
    }

    void flushAll() {
        cache.invalidateAll();
    }
}
